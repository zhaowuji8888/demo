package rooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import java.time.LocalDate;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.Assert.*;

public class ReportSuite {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static BookingStore db;
    private static final LocalDate start=UsageBenchmark.FIRST;

    @BeforeClass public static void seed() throws Exception {
        db=UsageBenchmark.fixture("reportSuite");
    }

    @Test public void partialWeekUsesActualCalendarAndEligibleBookings() throws Exception {
        JsonNode weeks=JSON.readTree(Reports.usage(db,start,start.plusDays(2),"week"));
        assertEquals(2,weeks.size());
        JsonNode weekend=weeks.get(0);
        assertEquals("2030-06-01",weekend.path("from").asText());
        assertEquals("2030-06-02",weekend.path("to").asText());
        JsonNode room=weekend.path("rooms").get(0);
        assertEquals(180,room.path("availableMinutes").intValue()); // Sat 120-30, Sun holiday override 90
        assertEquals(90,room.path("bookedMinutes").intValue());     // Sat 60, Sun 30
        assertEquals(0.5,room.path("utilization").doubleValue(),0);
        assertEquals(540,weekend.path("rooms").get(1).path("availableMinutes").intValue());
        assertTrue(weekend.path("rooms").get(2).get("utilization").isNull());
        assertEquals("[2,1]",weekend.path("idleTop10").toString());
        assertEquals(JSON.readTree("{\"6@09:00\":2,\"6@09:30\":1,\"6@10:00\":2,\"7@10:00\":1}"),weekend.path("heatmap"));
        JsonNode user=weekend.path("users").get(0);
        assertEquals(6,user.get("bookings").intValue());
        assertEquals(1,user.get("noShows").intValue());
        assertEquals(0.2,user.get("noShowRate").doubleValue(),0);
        JsonNode monday=weeks.get(1);
        assertEquals("2030-06-03",monday.get("from").asText());
        assertEquals("2030-06-03",monday.get("to").asText());
        assertEquals(90,monday.path("rooms").get(0).get("bookedMinutes").intValue());
    }

    @Test public void monthBoundaryClipsToRequestedDays() throws Exception {
        JsonNode months=JSON.readTree(Reports.usage(db,start.plusDays(29),start.plusDays(30),"month"));
        assertEquals(2,months.size());
        assertEquals("2030-06-30",months.get(0).get("to").asText());
        assertEquals("2030-07-01",months.get(1).get("from").asText());
        assertEquals(2,months.get(0).path("users").get(0).get("bookings").intValue());
        assertEquals(2,months.get(1).path("users").get(0).get("bookings").intValue());
    }

    @Test public void selectCountIsIndependentOfRangeAndGranularity() throws Exception {
        Logger sql = Logger.getLogger("rooms.sql");
        Level previous = sql.getLevel();
        UsageBenchmark.Counter queries = new UsageBenchmark.Counter();
        sql.addHandler(queries);
        try {
            sql.setLevel(Level.FINE);
            Reports.usage(db, start, start.plusDays(6), "week");
            int weeklyQueries = queries.count;
            assertTrue("Expected a bounded set of bulk reads", weeklyQueries > 0 && weeklyQueries <= 6);
            queries.count = 0;
            Reports.usage(db, start, start.plusDays(365), "month");
            assertEquals(weeklyQueries, queries.count);
        } finally {
            sql.removeHandler(queries);
            sql.setLevel(previous);
        }
    }
}
