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
    private final Acceptance.Time clock = new Acceptance.Time();
    private BookingStore db;
    private Accounts accounts;
    private String alice, bob, day;

    @Before public void start() throws Exception {
        db = new BookingStore("jdbc:derby:memory:http"+UUID.randomUUID()+";create=true",clock);
        accounts = new Accounts(db,clock);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> Server.handle(db,accounts,exchange));
        server.start();
        day = LocalDate.now(clock).plusDays(1).toString();
        alice = signUp("alice");
        bob = signUp("bobby");
    }

    @After public void stop() { if (server != null) server.stop(0); }

    private JsonNode request(String method,String path,String token,String form,int expected,String code) throws Exception {
        return response(method,path,token,form,expected,code).get("data");
    }

    private JsonNode response(String method,String path,String token,String form,int expected,String code) throws Exception {
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
                return response;
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

    private String loginAdmin() throws Exception {
        accounts.bootstrap("http-admin-password");
        return request("POST","/login",null,"username=admin&password=http-admin-password",200,"OK").get("token").asText();
    }

    @Test public void decisionDistinguishesRejectionFromTimeout() throws Exception {
        String admin = loginAdmin();
        request("POST","/admin/rooms",admin,"id=1&name=Cedar&capacity=4&approval=true&reason=policy",200,"OK");
        String id = request("POST","/bookings",alice,booking(1),201,"OK").get("id").asText();
        String deny = "id="+id+"&approve=false";
        JsonNode error = response("POST","/admin/decision",admin,deny,400,"INVALID_REQUEST");
        assertEquals("Missing field: reason", error.get("message").asText());
        request("POST","/admin/decision",admin,deny+"&reason=unavailable",200,"OK");
        assertEquals("REJECTED",request("GET","/my-bookings?date="+day,alice,null,200,"OK").get(0).get("status").asText());
        request("POST","/bookings",bob,booking(1),201,"OK");
        clock.now = LocalDate.parse(day).atTime(10,0).atZone(clock.getZone()).toInstant();
        db.tick();
        assertEquals("EXPIRED",request("GET","/my-bookings?date="+day,bob,null,200,"OK").get(0).get("status").asText());
        assertTrue(db.query("SELECT booking FROM slots WHERE room=1").isEmpty());
    }

    @Test public void missedCheckinsNotifyOwnerOnceAndGiveExactSuspensionEnd() throws Exception {
        for (int i=0; i<3; i++) {
            String id = request("POST","/bookings",alice,booking(1),201,"OK").get("id").asText();
            clock.now = LocalDate.parse(day).atTime(10,15).atZone(clock.getZone()).toInstant();
            db.tick();
            db.tick();
            int matches = 0;
            for (JsonNode notice : request("GET","/notifications",alice,null,200,"OK")) {
                if (!"NO_SHOW".equals(notice.path("kind").asText()) || !id.equals(notice.path("booking").asText())) continue;
                matches++;
                String message = notice.get("message").asText();
                assertTrue(message.contains(day+"T10:00"));
                assertEquals(i==2,message.contains("suspended until"));
                if (i==2) assertTrue(message.contains(clock.now.plus(java.time.Duration.ofDays(7)).atZone(clock.getZone()).toString()));
            }
            assertEquals(1,matches);
            day = LocalDate.parse(day).plusDays(1).toString();
        }
        assertEquals(0,request("GET","/notifications",bob,null,200,"OK").size());
        request("POST","/bookings",alice,booking(1),403,"BOOKING_SUSPENDED");
        clock.now = clock.now.plus(java.time.Duration.ofDays(7));
        day = LocalDate.now(clock).plusDays(1).toString();
        request("POST","/bookings",alice,booking(1),201,"OK");
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

    @Test public void usageReportRequiresAdminAndShowsBookedMinutes() throws Exception {
        String path = "/admin/reports/usage?from="+day+"&to="+day;
        request("POST","/bookings",alice,booking(1),201,"OK");
        request("GET",path,null,null,401,"UNAUTHORIZED");
        request("GET",path,bob,null,403,"FORBIDDEN");
        String admin = loginAdmin();
        JsonNode report = request("GET",path,admin,null,200,"OK");
        assertEquals(1, report.size());
        JsonNode room = report.get(0).path("rooms").get(0);
        assertEquals(1, room.get("room").intValue());
        assertEquals(60, room.get("bookedMinutes").intValue());
        assertEquals(540, room.get("availableMinutes").intValue());
        assertEquals("alice", report.get(0).path("users").get(0).get("username").asText());
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
