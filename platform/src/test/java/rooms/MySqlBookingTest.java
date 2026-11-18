package rooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ROOMBOOK_MYSQL_TEST_URL", matches = "jdbc:mysql:.+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${ROOMBOOK_MYSQL_TEST_URL}",
    "roombook.jobs.enabled=false"
})
class MySqlBookingTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void concurrentRequestsCommitExactlyOneBooking() throws Exception {
        LocalDate date = LocalDate.now(BookingStore.ZONE).plusDays(3);
        String form = window(date, "10:00", "11:00");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tokens.add(registerAndLogin());
        }

        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (String token : tokens) {
            pending.add(http.sendAsync(request("POST", "/api/bookings", token, form),
                HttpResponse.BodyHandlers.ofString()));
        }
        List<String> winners = new ArrayList<>();
        String winnerToken = null;
        for (int i = 0; i < pending.size(); i++) {
            HttpResponse<String> response = pending.get(i).get();
            if (response.statusCode() == 201) {
                winners.add(json.readTree(response.body()).path("data").path("id").asText());
                winnerToken = tokens.get(i);
            } else {
                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("SLOT_CONFLICT");
            }
        }
        assertThat(winners).hasSize(1);
        String winner = winners.get(0);
        assertThat(jdbc.queryForList(
            "SELECT id FROM bookings WHERE room=1 AND booked_day=? AND status='CONFIRMED'", String.class, date
        )).containsExactly(winner);
        assertThat(slots(winner)).containsExactly(600, 630);
        data("POST", "/api/bookings/" + winner + "/cancel", winnerToken, "", 200);
    }

    @Test
    void failedRescheduleRollsBackBothWindowsAndCancelReleasesSlots() throws Exception {
        LocalDate date = LocalDate.now(BookingStore.ZONE).plusDays(4);
        String token = registerAndLogin();
        String id = data("POST", "/api/bookings", token, window(date, "10:00", "11:00"), 201).get("id").asText();
        String blocker = data("POST", "/api/bookings", token, window(date, "11:30", "12:00"), 201).get("id").asText();
        String move = "/api/bookings/" + id;
        String newWindow = window(date, "11:00", "12:00");
        HttpResponse<String> failed = send("PUT", move, token, newWindow);
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(json.readTree(failed.body()).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(slots(id)).containsExactly(600, 630);
        assertThat(slots(blocker)).containsExactly(690);
        assertThat(jdbc.queryForObject("SELECT first_min FROM bookings WHERE id=?", Integer.class, id)).isEqualTo(600);

        data("POST", "/api/bookings/" + blocker + "/cancel", token, "", 200);
        data("PUT", move, token, newWindow, 200);
        assertThat(slots(id)).containsExactly(660, 690);
        data("POST", move + "/cancel", token, "", 200);
        assertThat(slots(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, id)).isEqualTo("CANCELLED");
        JsonNode available = data("GET", "/api/availability?date=" + date
            + "&start=11:00&end=12:00&equipment=whiteboard", token, null, 200);
        assertThat(available.get(0).get("id").asInt()).isEqualTo(1);
    }

    private List<Integer> slots(String id) {
        return jdbc.queryForList("SELECT minute_of_day FROM slots WHERE booking=? ORDER BY minute_of_day", Integer.class, id);
    }

    private String registerAndLogin() throws Exception {
        String form = "username=it_" + UUID.randomUUID().toString().replace("-", "") + "&password=room-test-password";
        data("POST", "/api/register", null, form, 201);
        return data("POST", "/api/login", null, form, 200).get("token").asText();
    }

    private static String window(LocalDate date, String start, String end) {
        return "room=1&attendees=1&date=" + date + "&start=" + start + "&end=" + end;
    }

    private JsonNode data(String method, String path, String token, String form, int status) throws Exception {
        HttpResponse<String> response = send(method, path, token, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body()).get("data");
    }

    private HttpResponse<String> send(String method, String path, String token, String form) throws Exception {
        return http.send(request(method, path, token, form), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String token, String form) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (form == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/x-www-form-urlencoded")
            .method(method, HttpRequest.BodyPublishers.ofString(form)).build();
    }
}
