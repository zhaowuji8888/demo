package rooms;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Executors;

public class Server {
    static String q(String s) {
        StringBuilder b=new StringBuilder("\"");
        for (char ch:s.toCharArray()) {
            if (ch=='"' || ch=='\\') b.append('\\');
            if (ch<32) b.append(String.format("\\u%04x",(int)ch)); else b.append(ch);
        }
        return b.append('"').toString();
    }
    static String array(List<String[]> rows,String... keys) {
        StringJoiner list=new StringJoiner(",","[","]");
        for(String[] row:rows) {
            StringJoiner item=new StringJoiner(",","{","}");
            for(int i=0;i<keys.length;i++) item.add(q(keys[i])+":"+q(row[i]));
            list.add(item.toString());
        }
        return list.toString();
    }
    static void send(HttpExchange x,int status,String body) throws IOException {
        byte[] data=body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(status,data.length);
        try(OutputStream stream=x.getResponseBody()){stream.write(data);}
    }
    static Map<String,String> params(String raw) throws IOException {
        Map<String,String> p=new HashMap<>();
        if(raw!=null && !raw.isEmpty()) for(String pair:raw.split("&")) {
            String[] kv=pair.split("=",2);
            if(kv.length!=2 || p.put(URLDecoder.decode(kv[0],"UTF-8"),URLDecoder.decode(kv[1],"UTF-8"))!=null)
                throw new IllegalArgumentException("Invalid form fields.");
        }
        return p;
    }
    static String field(Map<String,String> p,String name) {
        if(!p.containsKey(name)) throw new IllegalArgumentException("Missing field: "+name);
        return p.get(name);
    }
    static int minute(String value) {
        if(!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("Use HH:mm.");
        LocalTime t=LocalTime.parse(value); return t.getHour()*60+t.getMinute();
    }
    private static BookingStore.Window window(Map<String,String> form) {
        return new BookingStore.Window(LocalDate.parse(field(form,"date")),
                minute(field(form,"start")), minute(field(form,"end")));
    }

    private static Map<String,String> form(HttpExchange x) throws IOException {
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded"))
            throw new Problem(415,"Expected form data");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = x.getRequestBody().read(buffer)) != -1) {
            if (body.size() + count > 4096) throw new Problem(413,"Form too large");
            body.write(buffer,0,count);
        }
        return params(body.toString("UTF-8"));
    }

    private static String get(BookingStore db, Accounts.User user, String path, Map<String,String> p) throws java.sql.SQLException {
        switch (path) {
            case "/rooms":
                return array(db.rooms(),"id","name","capacity","equipment","enabled");
            case "/availability":
                return array(db.availableRooms(window(p),Integer.parseInt(p.getOrDefault("people","1")),p.getOrDefault("equipment","")),
                        "id","name","capacity","equipment");
            case "/bookings":
            case "/my-bookings":
                return array(db.agenda(LocalDate.parse(field(p,"date")),path.equals("/my-bookings") ? user.name : null),
                        "id","room","start","end","holder");
            case "/maintenance":
                return array(db.maintenance(LocalDate.parse(field(p,"date"))),"id","room","start","end","reason");
            case "/admin/audit":
                return array(db.auditLog(user),"time","actor","action","target","reason");
            default: throw new Problem(404,"Unknown path");
        }
    }

    private static void post(BookingStore db, Accounts accounts, Accounts.User user, String token, String path,
                             Map<String,String> p, HttpExchange x) throws IOException, java.sql.SQLException {
        switch (path) {
            case "/register":
                accounts.register(field(p,"username"),field(p,"password"));
                send(x,201,"{\"registered\":true}");
                break;
            case "/login":
                String session = accounts.login(field(p,"username"),field(p,"password"));
                send(x,200,"{\"token\":"+q(session)+"}");
                break;
            case "/logout":
                accounts.logout(token);
                send(x,200,"{\"loggedOut\":true}");
                break;
            case "/bookings":
                String id = db.book(user,Integer.parseInt(field(p,"room")),window(p),p.getOrDefault("equipment",""));
                send(x,201,"{\"id\":"+q(id)+"}");
                break;
            case "/cancel":
                db.cancel(user,field(p,"id"),p.get("cancelKey"),p.get("reason"));
                send(x,200,"{\"cancelled\":true}");
                break;
            case "/reschedule":
                db.reschedule(user,field(p,"id"),p.get("cancelKey"),p.get("reason"),Integer.parseInt(field(p,"room")),window(p));
                send(x,200,"{\"rescheduled\":true}");
                break;
            case "/admin/room-status":
                String state = field(p,"enabled");
                if (!(state.equals("true") || state.equals("false"))) throw new Problem(400,"enabled must be true or false");
                db.setEnabled(user,Integer.parseInt(field(p,"room")),Boolean.parseBoolean(state),field(p,"reason"));
                send(x,200,"{\"updated\":true}");
                break;
            case "/admin/maintenance":
                String block = db.addMaintenance(user,Integer.parseInt(field(p,"room")),window(p),field(p,"reason"));
                send(x,201,"{\"id\":"+q(block)+"}");
                break;
            case "/admin/maintenance/remove":
                db.removeMaintenance(user,field(p,"id"),field(p,"reason"));
                send(x,200,"{\"removed\":true}");
                break;
            default: throw new Problem(404,"Unknown path");
        }
    }

    static final Set<String> READ = new HashSet<>(Arrays.asList("/rooms","/availability","/bookings","/my-bookings","/maintenance","/admin/audit"));
    static final Set<String> WRITE = new HashSet<>(Arrays.asList("/register","/login","/logout","/bookings","/reschedule","/cancel",
            "/admin/room-status","/admin/maintenance","/admin/maintenance/remove"));

    static void handle(BookingStore db, Accounts accounts, HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();
            boolean read = READ.contains(path), write = WRITE.contains(path);
            if (!read && !write) throw new Problem(404,"Unknown path");
            if (!(read && method.equals("GET") || write && method.equals("POST"))) {
                x.getResponseHeaders().set("Allow",read && write ? "GET, POST" : read ? "GET" : "POST");
                throw new Problem(405,"Method not allowed");
            }
            String bearer = x.getRequestHeaders().getFirst("Authorization");
            String token = bearer != null && bearer.startsWith("Bearer ") ? bearer.substring(7) : "";
            Accounts.User user = null;
            if (!path.equals("/register") && !path.equals("/login")) {
                user = accounts.authenticate(token);
                if (path.startsWith("/admin/")) user.requireAdmin();
            }
            if (method.equals("GET")) send(x,200,get(db,user,path,params(x.getRequestURI().getRawQuery())));
            else post(db,accounts,user,token,path,form(x),x);
        } catch (Problem e) {
            String conflicts = e.conflicts.isEmpty() ? "" : ",\"conflicts\":"+array(e.conflicts,"id","holder","date","startMinute","endMinute");
            send(x,e.status,"{\"error\":"+q(e.getMessage())+conflicts+"}");
        } catch (DateTimeParseException | IllegalArgumentException e) {
            send(x,400,"{\"error\":"+q(e.getMessage())+"}");
        } catch (java.sql.SQLException e) {
            e.printStackTrace();
            send(x,500,"{\"error\":\"Database error\"}");
        } finally { x.close(); }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        BookingStore db = new BookingStore("jdbc:derby:data/meeting;create=true");
        Accounts accounts = new Accounts(db);
        accounts.bootstrap(System.getenv("ROOMBOOK_ADMIN_PASSWORD"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",x -> handle(db,accounts,x));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("RoomBook listening on http://127.0.0.1:"+server.getAddress().getPort());
    }
}
