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
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
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
            for(int i=0;i<keys.length;i++) {
                String key=keys[i], value=q(row[i]);
                if (Arrays.asList("roomId","capacity","room","attendees","startMinute","endMinute").contains(key)) value=Integer.toString(Integer.parseInt(row[i]));
                if ((key.equals("enabled") || key.equals("approval"))) value="1".equals(row[i])?"true":"false";
                item.add(q(key.equals("roomId")?"id":key)+":"+value);
            }
            list.add(item.toString());
        }
        return list.toString();
    }
    static void send(HttpExchange x,int status,String body) throws IOException {
        reply(x,status,status<400?"OK":"INTERNAL_ERROR",status<400?"success":"Request failed",body);
    }
    static void reply(HttpExchange x,int status,String code,String message,String body) throws IOException {
        String trace=TRACE.get();
        body="{\"code\":"+q(code)+",\"message\":"+q(message)+",\"traceId\":"+q(trace)+",\"data\":"+body+"}";
        System.out.println("{\"traceId\":"+q(trace)+",\"status\":"+status+",\"code\":"+q(code)+"}");
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
        if(!p.containsKey(name)) throw new Problem(400,"INVALID_REQUEST","Missing field: "+name);
        return p.get(name);
    }
    static String time(String raw) {
        int m=Integer.parseInt(raw);
        return String.format(Locale.ROOT,"%02d:%02d",m/60,m%60);
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

    static String get(BookingStore db, Accounts.User user, String path, Map<String,String> p) throws java.sql.SQLException {
        switch (path) {
            case "/rooms":
                return array(db.rooms(),"roomId","name","capacity","equipment","enabled","location","description","image","approval");
            case "/availability":
                return array(db.availableRooms(window(p),Integer.parseInt(p.getOrDefault("people","1")),p.getOrDefault("equipment","")),
                        "roomId","name","capacity","equipment");
            case "/bookings":
            case "/my-bookings":
                LocalDate day=LocalDate.parse(field(p,"date"));
                String scope=p.getOrDefault("scope","default");
                if (!Arrays.asList("self","all","default").contains(scope)) throw new Problem(400,"Unknown scope");
                if(scope.equals("all")) user.requireAdmin();
                boolean mine=path.equals("/my-bookings") || scope.equals("self");
                if (!mine && !user.admin) {
                    List<String[]> busy=db.query("SELECT DISTINCT room,first_min,last_min FROM bookings WHERE booked_day=? AND status IN ('CONFIRMED','CHECKED_IN','PENDING_APPROVAL') ORDER BY room,first_min",java.sql.Date.valueOf(day));
                    StringJoiner out=new StringJoiner(",","[","]");
                    for(String[] r:busy) out.add("{\"room\":"+r[0]+",\"start\":"+q(time(r[1]))+",\"end\":"+q(time(r[2]))+",\"busy\":true}");
                    return out.toString();
                }
                return array(db.agenda(day,mine?user.name:null,Boolean.parseBoolean(p.get("includeCancelled"))),
                        "id","room","start","end","holder","attendees","status");
            case "/maintenance":
                return array(db.maintenance(LocalDate.parse(field(p,"date"))),"id","room","start","end","reason");
            case "/notifications":
                return array(db.query("SELECT id,booking,kind,body,created FROM notices WHERE recipient=? ORDER BY created DESC FETCH FIRST 100 ROWS ONLY",user.name),"id","booking","kind","message","created");
            case "/admin/dead-letters":
                return array(db.query("SELECT id,recipient,kind,body FROM notices WHERE state='DEAD' ORDER BY created"),"id","recipient","kind","message");
            case "/admin/reports/usage":
                return Reports.usage(db,LocalDate.parse(field(p,"from")),LocalDate.parse(field(p,"to")),p.getOrDefault("granularity","week"));
            case "/admin/audit":
                return array(db.auditPage(user,p),"time","actor","action","target","reason");
            default: throw new Problem(404,"Unknown path");
        }
    }

    static class Response {
        final int status; final String json;
        Response(int status,String json) { this.status=status; this.json=json; }
    }
    static Response post(BookingStore db, Accounts accounts, Accounts.User user, String token, String path,
                             Map<String,String> p, String ip) throws IOException, java.sql.SQLException {
        switch (path) {
            case "/register":
                accounts.register(field(p,"username"),field(p,"password"));
                return new Response(201,"{\"registered\":true}");
            case "/login":
                String session = accounts.login(field(p,"username"),field(p,"password"),ip);
                return new Response(200,"{\"token\":"+q(session)+"}");
            case "/logout":
                accounts.logout(token);
                return new Response(200,"{\"loggedOut\":true}");
            case "/bookings":
                String id = db.book(user,Integer.parseInt(field(p,"room")),window(p),p.getOrDefault("equipment",""),Integer.parseInt(field(p,"attendees")));
                return new Response(201,"{\"id\":"+q(id)+"}");
            case "/cancel":
                db.cancel(user,field(p,"id"),p.get("reason"));
                return new Response(200,"{\"cancelled\":true}");
            case "/reschedule":
                String scope=p.getOrDefault("scope","THIS");
                if(!scope.equals("THIS") && !scope.equals("THIS_AND_FUTURE")) throw new Problem(400,"Invalid series scope");
                db.moveSeries(user,field(p,"id"),p.get("reason"),Integer.parseInt(field(p,"room")),window(p),p.containsKey("attendees")?Integer.valueOf(p.get("attendees")):null,scope.equals("THIS_AND_FUTURE"));
                return new Response(200,"{\"rescheduled\":true}");
            case "/bookings/recurring":
                String rule=field(p,"rrule");
                if(!rule.matches("WEEKLY;COUNT=[1-5]")) throw new Problem(400,"Use WEEKLY;COUNT=1..5");
                return new Response(201,array(db.recurring(user,Integer.parseInt(field(p,"room")),window(p),p.getOrDefault("equipment",""),
                    Integer.parseInt(field(p,"attendees")),Integer.parseInt(rule.substring("WEEKLY;COUNT=".length()))),"id","date"));
            case "/checkin":
                db.checkin(user,field(p,"id")); return new Response(200,"true");
            case "/admin/decision":
                db.decide(user,field(p,"id"),flag(p,"approve"),field(p,"reason")); return new Response(200,"true");
            case "/admin/rooms":
                int room=db.saveRoom(user,p.containsKey("id")?Integer.valueOf(p.get("id")):null,field(p,"name"),Integer.parseInt(field(p,"capacity")),
                    p.getOrDefault("equipment",""),p.getOrDefault("location",""),p.getOrDefault("description",""),p.getOrDefault("image",""),
                    p.containsKey("approval") && flag(p,"approval"),field(p,"reason"));
                return new Response(200,"{\"id\":"+room+"}");
            case "/admin/rooms/delete":
                db.deleteRoom(user,Integer.parseInt(field(p,"id")),field(p,"reason")); return new Response(200,"true");
            case "/admin/calendar":
                db.calendar(user,Integer.parseInt(p.getOrDefault("room","0")),field(p,"kind"),
                    p.containsKey("date")?LocalDate.parse(p.get("date")):null,Integer.parseInt(p.getOrDefault("weekday","0")),
                    minute(p.getOrDefault("start","00:00")),minute(p.getOrDefault("end","00:00")),field(p,"reason"));
                return new Response(200,"true");
            case "/profile/email":
                String email=field(p,"email");
                if(email.length()>254 || !email.matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")) throw new Problem(400,"Invalid email address");
                db.transaction(c -> Database.update(c,"UPDATE accounts SET email=? WHERE username=?",email,user.name));
                return new Response(200,"true");
            case "/admin/dead-letters/replay":
                int changed=db.transaction(c -> Database.update(c,"UPDATE notices SET state='QUEUED',attempts=0 WHERE id=? AND state='DEAD'",field(p,"id")));
                if(changed==0) throw new Problem(404,"Dead letter not found");
                return new Response(200,"true");
            case "/admin/room-status":
                String state = field(p,"enabled");
                if (!(state.equals("true") || state.equals("false"))) throw new Problem(400,"enabled must be true or false");
                db.setEnabled(user,Integer.parseInt(field(p,"room")),Boolean.parseBoolean(state),field(p,"reason"));
                return new Response(200,"{\"updated\":true}");
            case "/admin/maintenance":
                String block = db.addMaintenance(user,Integer.parseInt(field(p,"room")),window(p),field(p,"reason"));
                return new Response(201,"{\"id\":"+q(block)+"}");
            case "/admin/maintenance/remove":
                db.removeMaintenance(user,field(p,"id"),field(p,"reason"));
                return new Response(200,"{\"removed\":true}");
            default: throw new Problem(404,"Unknown path");
        }
    }

    static boolean flag(Map<String,String> p,String key) {
        String value=field(p,key);
        if(!value.equals("true") && !value.equals("false")) throw new Problem(400,"Expected a boolean");
        return Boolean.parseBoolean(value);
    }
    static final Set<String> READ = new HashSet<>(Arrays.asList("/rooms","/availability","/bookings","/my-bookings","/maintenance","/admin/audit","/notifications","/admin/dead-letters","/admin/reports/usage"));
    static final Set<String> WRITE = new HashSet<>(Arrays.asList("/register","/login","/logout","/bookings","/reschedule","/cancel",
            "/bookings/recurring","/checkin","/admin/decision","/admin/calendar","/admin/rooms","/admin/rooms/delete","/profile/email","/admin/dead-letters/replay","/admin/room-status","/admin/maintenance","/admin/maintenance/remove"));

    static void handle(BookingStore db, Accounts accounts, HttpExchange x) throws IOException {
        try {
            TRACE.set(UUID.randomUUID().toString());
            String path = x.getRequestURI().getPath();
            if (path.startsWith("/api/")) path=path.substring(4);
            String method = x.getRequestMethod();
            String resourceId=null;
            if(path.matches("/bookings/[a-fA-F0-9-]{36}(/cancel|/checkin)?")) {
                String[] parts=path.split("/");
                resourceId=parts[2];
                if(parts.length==3 && method.equals("PUT")) { path="/reschedule"; method="POST"; }
                else if(parts.length==4 && method.equals("POST")) path="/"+parts[3];
            }
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
            else {
                Map<String,String> fields=form(x);
                if(resourceId!=null) fields.put("id",resourceId);
                try { Response r=post(db,accounts,user,token,path,fields,x.getRemoteAddress().getAddress().getHostAddress()); send(x,r.status,r.json); }
                catch(Problem failure) {
                    if(!failure.code.equals("SLOT_CONFLICT") || !path.equals("/bookings")) throw failure;
                    int room=Integer.parseInt(field(fields,"room")), people=Integer.parseInt(field(fields,"attendees"));
                    BookingStore.Window w=window(fields);
                    String needs=fields.getOrDefault("equipment","");
                    String slots=array(db.suggestions(w,people,needs,room,true),"start","end");
                    String rooms=array(db.suggestions(w,people,needs,room,false),"roomId","name","capacity","equipment");
                    reply(x,409,failure.code,failure.getMessage(),"{\"suggestions\":{\"slots\":"+slots+",\"rooms\":"+rooms+"}}");
                }
            }
        } catch (Problem e) {
            String data=e.conflicts.isEmpty()?"null":"{\"conflicts\":"+array(e.conflicts,"id","holder","date","startMinute","endMinute")+"}";
            reply(x,e.status,e.code,e.getMessage(),data);
        } catch(DateTimeParseException e) {
            reply(x,400,"INVALID_DATE_FORMAT","Use a valid ISO date","null");
        } catch(IllegalArgumentException e) {
            reply(x,400,"INVALID_REQUEST","Check request parameters","null");
        } catch(java.sql.SQLException | RuntimeException e) {
            System.err.println("traceId="+TRACE.get()+" "+e.getClass().getName());
            reply(x,500,"INTERNAL_ERROR","Unable to process request","null");
        } finally { TRACE.remove(); x.close(); }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        BookingStore db = new BookingStore("jdbc:derby:data/meeting;create=true");
        Accounts accounts = new Accounts(db);
        accounts.bootstrap(System.getenv("ROOMBOOK_ADMIN_PASSWORD"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",x -> handle(db,accounts,x));
        server.setExecutor(Executors.newFixedThreadPool(8));
        java.util.concurrent.ScheduledExecutorService jobs=Executors.newScheduledThreadPool(2);
        jobs.scheduleWithFixedDelay(() -> { try { db.tick(); } catch(Exception e) { e.printStackTrace(); } },0,15,java.util.concurrent.TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(new Delivery(db),0,30,java.util.concurrent.TimeUnit.SECONDS);
        server.start();
        System.out.println("RoomBook listening on http://127.0.0.1:"+server.getAddress().getPort());
    }
}
