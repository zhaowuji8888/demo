package rooms;

import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Accounts {
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
    Accounts(Database db) { this.db = db; }

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
            Database.update(c,"INSERT INTO accounts VALUES (?,?,?,?)",name,Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(hash(password,salt)),admin ? 1 : 0);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) throw new Problem(409,"Username already exists");
            throw e;
        }
    }

    String login(String raw, String password) throws SQLException {
        String name = username(raw);
        List<String[]> records = db.query("SELECT salt,verifier,is_admin FROM accounts WHERE username=?",name);
        if (records.isEmpty()) throw new Problem(401,"Invalid credentials");
        String[] record = records.get(0);
        if (!MessageDigest.isEqual(Base64.getDecoder().decode(record[1]), hash(password,Base64.getDecoder().decode(record[0]))))
            throw new Problem(401,"Invalid credentials");
        sessions.values().removeIf(session -> session.expires <= System.currentTimeMillis());
        byte[] token = new byte[32];
        random.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        sessions.put(value,new Session(new User(name,"1".equals(record[2]))));
        return value;
    }

    User authenticate(String token) {
        Session session = sessions.get(token);
        if (session == null || session.expires <= System.currentTimeMillis()) throw new Problem(401,"Login required");
        return session.user;
    }

    void logout(String token) { sessions.remove(token); }
}
