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
    static void handle(BookingStore db,HttpExchange x) throws IOException {
        try {
            String path=x.getRequestURI().getPath(), method=x.getRequestMethod();
            if(!Arrays.asList("/rooms","/bookings","/cancel").contains(path)) {
                send(x,404,"{\"error\":\"Unknown path\"}"); return;
            }
            if(method.equals("GET") && path.equals("/rooms")) {
                send(x,200,array(db.rooms(),"id","name","capacity")); return;
            }
            if(method.equals("GET") && path.equals("/bookings")) {
                Map<String,String> p=params(x.getRequestURI().getRawQuery());
                send(x,200,array(db.agenda(LocalDate.parse(field(p,"date"))),"id","room","start","end","holder")); return;
            }
            if(!method.equals("POST") || path.equals("/rooms")) {
                x.getResponseHeaders().set("Allow",path.equals("/rooms")?"GET":path.equals("/cancel")?"POST":"GET, POST");
                send(x,405,"{\"error\":\"Method not allowed\"}"); return;
            }
            String type=x.getRequestHeaders().getFirst("Content-Type");
            if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")){
                send(x,415,"{\"error\":\"Expected form data\"}");return;
            }
            ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[1024]; int n;
            while((n=x.getRequestBody().read(buf))!=-1){
                if(b.size()+n>4096){send(x,413,"{\"error\":\"Form too large\"}");return;} b.write(buf,0,n);
            }
            Map<String,String> p=params(b.toString("UTF-8"));
            if(path.equals("/cancel")){
                boolean ok=db.cancel(field(p,"id"),field(p,"cancelKey"));
                send(x,ok?200:404,ok?"{\"cancelled\":true}":"{\"error\":\"Booking or key not found\"}");
            } else {
                BookingStore.Ticket t=db.book(Integer.parseInt(field(p,"room")),LocalDate.parse(field(p,"date")),
                    minute(field(p,"start")),minute(field(p,"end")),field(p,"holder"));
                send(x,201,"{\"id\":"+q(t.id)+",\"cancelKey\":"+q(t.cancelKey)+"}");
            }
        } catch(BookingStore.Conflict e){send(x,409,"{\"error\":\"Time slot already reserved\"}");}
          catch(DateTimeParseException | IllegalArgumentException e){send(x,400,"{\"error\":"+q(e.getMessage())+"}");}
          catch(java.sql.SQLException e){e.printStackTrace();send(x,500,"{\"error\":\"Database error\"}");}
          finally {x.close();}
    }
    public static void main(String[] args) throws Exception {
        int port=args.length==0?8080:Integer.parseInt(args[0]);
        BookingStore db=new BookingStore("jdbc:derby:data/meeting;create=true");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",x->handle(db,x)); server.setExecutor(Executors.newFixedThreadPool(8));
        server.start(); System.out.println("Meeting rooms: http://127.0.0.1:"+port+"/rooms");
    }
}
