package rooms;

import java.time.LocalDate;
import java.time.LocalDateTime;

final class StaleScanTest {
    static void run() throws Exception {
        Acceptance.Time clock = new Acceptance.Time();
        BookingStore db = new BookingStore("jdbc:derby:memory:staleScan;create=true", clock);
        new Accounts(db,clock).register("alice","test-alice-password");
        Accounts.User alice = new Accounts.User("alice",false);
        LocalDate today = LocalDate.now(clock);
        String id = db.book(alice,1,Acceptance.w(today,600,660),"",2);

        // Commit the move between the real scan read and write, independent of thread timing.
        BookingStore.ScanEntry snapshot = new BookingStore.ScanEntry(db.scanCandidates(today).get(0));
        clock.now = clock.now.plusSeconds(15*60);
        LocalDate tomorrow = today.plusDays(1);
        db.reschedule(alice,id,null,1,Acceptance.w(tomorrow,600,660),2);
        db.applyScan(snapshot,LocalDateTime.now(clock));

        StoreTest.check("CONFIRMED".equals(db.agenda(tomorrow,"alice",false).get(0)[6]),"stale scan incorrectly penalized rescheduled booking");
        StoreTest.check(db.query("SELECT minute_of_day FROM slots WHERE booking=? AND booked_day=?",id,java.sql.Date.valueOf(tomorrow)).size()==2,"rescheduled slots retained");
        StoreTest.check(db.query("SELECT username FROM accounts WHERE username='alice' AND strikes=0 AND suspended_until=0").size()==1,"no penalty after reschedule");
    }
}
