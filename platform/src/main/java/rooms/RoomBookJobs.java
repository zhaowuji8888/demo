package rooms;

import java.sql.SQLException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "roombook.jobs.enabled", matchIfMissing = true)
final class RoomBookJobs {
    private final BookingStore store;
    private final Delivery delivery;

    RoomBookJobs(BookingStore store) {
        this.store = store;
        this.delivery = new Delivery(store);
    }

    @Scheduled(fixedDelay = 15_000)
    void advanceBookings() throws SQLException {
        store.tick();
    }

    @Scheduled(fixedDelay = 30_000)
    void deliverNotifications() {
        delivery.run();
    }
}
