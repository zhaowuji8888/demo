package rooms;

import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

class Accounts {
    static final class User {
        final String name;
        final boolean admin;
        User(String name, boolean admin) { this.name = name; this.admin = admin; }
        void requireAdmin() { if (!admin) throw new Problem(403, "Administrator required"); }
    }

    private static final class Session {
        final User user;
        final long expires = System.currentTimeMillis() + 8 * 3600_000L;
        Session(User user) { this.user = user; }
    }

    private final Database db;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final java.time.Clock clock;
    private final Map<String,Deque<Long>> attempts = new HashMap<>();
    Accounts(Database db) { this(db,java.time.Clock.systemUTC()); }
    Accounts(Database db,java.time.Clock clock) { this.db=db; this.clock=clock; }
    synchronized void limitIp(String ip,long now) {
        attempts.values().removeIf(queue -> queue.isEmpty() || queue.getLast()<=now-60000);
        Deque<Long> queue=attempts.computeIfAbsent(ip,k -> new ArrayDeque<>());
        while(!queue.isEmpty() && queue.getFirst()<=now-60000) queue.removeFirst();
        if(queue.size()>=20) throw new Problem(429,"RATE_LIMITED","Try again in one minute");
        queue.addLast(now);
    }

    private String username(String raw) {
        if (!raw.matches("[A-Za-z0-9_]{3,40}")) throw new Problem(400,"Use 3-40 letters, digits or underscores for username");
        return raw.toLowerCase(Locale.ROOT);
    }

    private byte[] hash(String password, byte[] salt) {
        PBEKeySpec key = new PBEKeySpec(password.toCharArray(),salt,210000,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { key.clearPassword(); }
    }

    void bootstrap(String password) throws SQLException {
        if (password != null && !password.isEmpty() && db.query("SELECT username FROM accounts WHERE username='admin'").isEmpty())
            create("admin",password,true);
    }

    void register(String raw, String password) throws SQLException {
        String name = username(raw);
        if (name.equals("admin")) throw new Problem(409,"Reserved username");
        create(name,password,false);
    }

    private void create(String name, String password, boolean admin) throws SQLException {
        if (password.length()<10 || password.length()>128) throw new Problem(400,"Password must contain 10-128 characters");
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        try (Connection c = db.open()) {
            Database.update(c,"INSERT INTO accounts(username,salt,verifier,is_admin) VALUES (?,?,?,?)",name,Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(hash(password,salt)),admin ? 1 : 0);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()) || e.getErrorCode()==1062) throw new Problem(409,"Username already exists");
            throw e;
        }
    }

    String login(String raw,String password,String ip) throws SQLException {
        long now=clock.millis();
        limitIp(ip,now);
        String name=username(raw);
        // Serialize attempts for one account; failures and their audit record commit together.
        String outcome=db.transaction(c -> {
            Database.update(c,"UPDATE accounts SET is_admin=is_admin WHERE username=?",name);
            List<String[]> records=Database.rows(c,"SELECT salt,verifier,is_admin FROM accounts WHERE username=?",name);
            List<String[]> guard=Database.rows(c,"SELECT failures,locked_until FROM login_guard WHERE username=?",name);
            long until=guard.isEmpty()?0:Long.parseLong(guard.get(0)[1]);
            if(until>now) return Long.toString((until-now+999)/1000);
            if(!records.isEmpty()) {
                String[] record=records.get(0);
                if(MessageDigest.isEqual(Base64.getDecoder().decode(record[1]),hash(password,Base64.getDecoder().decode(record[0])))) {
                    Database.update(c,"DELETE FROM login_guard WHERE username=?",name);
                    return "1".equals(record[2])?"ADMIN":"EMPLOYEE";
                }
            }
            int failures=guard.isEmpty() || until>0?1:Integer.parseInt(guard.get(0)[0])+1;
            if(!records.isEmpty()) {
                Database.update(c,"DELETE FROM login_guard WHERE username=?",name);
                Database.update(c,"INSERT INTO login_guard VALUES (?,?,?)",name,failures,failures>=5?now+900000:0L);
            }
            Database.update(c,"INSERT INTO admin_log(actor,operation,target,reason) VALUES (?,?,?,?)",name,
                failures==5?"login-lock":"login-failure",name,"Invalid credentials");
            return "DENIED";
        });
        if(outcome.equals("DENIED")) throw new Problem(401,"Invalid credentials");
        if(!outcome.equals("ADMIN") && !outcome.equals("EMPLOYEE"))
            throw new Problem(429,"ACCOUNT_LOCKED","Account locked for "+outcome+" seconds");
        sessions.values().removeIf(session -> session.expires <= System.currentTimeMillis());
        byte[] token = new byte[32];
        random.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        saveSession(value,new User(name,outcome.equals("ADMIN")));
        return value;
    }

    void saveSession(String token,User user) { sessions.put(token,new Session(user)); }
    User authenticate(String token) {
        Session session = sessions.get(token);
        if (session == null || session.expires <= System.currentTimeMillis()) throw new Problem(401,"Login required");
        return session.user;
    }

    void logout(String token) { sessions.remove(token); }
}
