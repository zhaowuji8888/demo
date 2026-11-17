package rooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.Assert.*;

public class HttpSuite {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private String alice, bob, day;

    @Before public void start() throws Exception {
        Acceptance.Time clock = new Acceptance.Time();
        BookingStore db = new BookingStore("jdbc:derby:memory:http"+UUID.randomUUID()+";create=true",clock);
        Accounts accounts = new Accounts(db,clock);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> Server.handle(db,accounts,exchange));
        server.start();
        day = LocalDate.now(clock).plusDays(1).toString();
        alice = signUp("alice");
        bob = signUp("bobby");
    }

    @After public void stop() { if (server != null) server.stop(0); }

    private JsonNode request(String method,String path,String token,String form,int expected,String code) throws Exception {
        URL url = new URL("http://127.0.0.1:"+server.getAddress().getPort()+"/api"+path);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            if (token != null) connection.setRequestProperty("Authorization","Bearer "+token);
            if (form != null) {
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(form.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            try (InputStream in = status>=400 ? connection.getErrorStream() : connection.getInputStream()) {
                JsonNode response = JSON.readTree(in);
                assertEquals(response.toString(),expected,status);
                assertEquals(code,response.path("code").asText());
                assertFalse(response.path("traceId").asText().isEmpty());
                assertTrue(response.has("message") && response.has("data"));
                return response.get("data");
            }
        } finally { connection.disconnect(); }
    }

    private String signUp(String username) throws Exception {
        String fields = "username="+username+"&password=http-test-password";
        request("POST","/register",null,fields,201,"OK");
        return request("POST","/login",null,fields,200,"OK").get("token").asText();
    }

    private String booking(int room) {
        return "date="+day+"&room="+room+"&start=10:00&end=11:00&attendees=2";
    }

    @Test public void roleAndPrivacyContract() throws Exception {
        request("GET","/rooms",null,null,401,"UNAUTHORIZED");
        JsonNode room = request("GET","/rooms",bob,null,200,"OK").get(0);
        assertTrue(room.get("id").isIntegralNumber() && room.get("capacity").isIntegralNumber() && room.get("enabled").isBoolean());
        String id = request("POST","/bookings",alice,booking(1),201,"OK").get("id").asText();
        JsonNode busy = request("GET","/bookings?date="+day,bob,null,200,"OK").get(0);
        assertTrue(busy.get("busy").booleanValue());
        assertFalse(busy.has("id") || busy.has("holder"));
        request("GET","/bookings?scope=all&date="+day,bob,null,403,"FORBIDDEN");
        request("POST","/bookings/"+id+"/cancel",bob,"",403,"FORBIDDEN");
        request("POST","/admin/room-status",bob,"room=1&enabled=false&reason=testing",403,"FORBIDDEN");
        request("GET","/my-bookings?date=invalid",bob,null,400,"INVALID_DATE_FORMAT");
    }

    @Test public void conflictingMovePreservesOriginalReservation() throws Exception {
        String id = request("POST","/bookings",alice,booking(1),201,"OK").get("id").asText();
        request("POST","/bookings",bob,booking(2),201,"OK");
        JsonNode conflict = request("POST","/bookings",bob,booking(1),409,"SLOT_CONFLICT");
        assertTrue(conflict.path("suggestions").path("rooms").size()>0);
        assertTrue(conflict.path("suggestions").path("slots").size()>0);
        request("PUT","/bookings/"+id,alice,booking(2),409,"SLOT_CONFLICT");
        JsonNode original = request("GET","/my-bookings?date="+day,alice,null,200,"OK").get(0);
        assertEquals(id,original.get("id").asText());
        assertEquals(1,original.get("room").intValue());
        request("POST","/bookings",bob,booking(1),409,"SLOT_CONFLICT");
        request("POST","/bookings/"+id+"/cancel",alice,"",200,"OK");
        request("POST","/bookings",bob,booking(1),201,"OK");
    }
}
