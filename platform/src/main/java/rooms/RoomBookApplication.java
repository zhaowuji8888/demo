package rooms;

import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RoomBookApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoomBookApplication.class, args);
    }

    @Bean
    @DependsOnDatabaseInitialization
    BookingStore bookingStore(DataSource dataSource) {
        return new BookingStore(dataSource, Clock.system(BookingStore.ZONE));
    }

    @Bean
    Accounts accounts(BookingStore store, @Value("${ROOMBOOK_ADMIN_PASSWORD:}") String adminPassword)
            throws SQLException {
        Accounts accounts = new Accounts(store);
        accounts.bootstrap(adminPassword);
        return accounts;
    }
}
