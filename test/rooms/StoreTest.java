package rooms;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

public class StoreTest {
    static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
    }
    interface Action { void run() throws Exception; }
    static Problem expect(int status, Action action) throws Exception {
        try { action.run(); }
        catch (Problem e) {
            check(e.status == status,"expected "+status+" but got "+e.status+" "+e.getMessage());
            return e;
        }
        throw new AssertionError("expected "+status);
    }
    static BookingStore.Window at(int start,int end) {
        return new BookingStore.Window(LocalDate.now().plusDays(3),start,end);
    }
    static List<String> available(BookingStore db, BookingStore.Window w, int people, String needs) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (String[] room : db.availableRooms(w,people,needs)) ids.add(room[0]);
        return ids;
    }
    public static void main(String[] args) throws Exception {
        BookingStore db = new BookingStore("jdbc:derby:memory:roomtest;create=true");
        Accounts accounts = new Accounts(db);
        accounts.bootstrap("test-admin-secret");
        accounts.register("alice","test-alice-secret");
        accounts.register("bobby","test-bobby-secret");
        expect(409,() -> accounts.register("ADMIN","test-claim-admin"));
        expect(409,() -> accounts.register("Alice","test-duplicate"));
        expect(401,() -> accounts.login("alice","incorrect"));
        String token = accounts.login("ALICE","test-alice-secret");
        Accounts.User alice = accounts.authenticate(token);
        Accounts.User bob = accounts.authenticate(accounts.login("bobby","test-bobby-secret"));
        Accounts.User admin = accounts.authenticate(accounts.login("admin","test-admin-secret"));
        BookingStore.Window hour = at(600,660);
        String id = db.book(alice,3,hour,"camera,projector");
        expect(403,() -> db.cancel(bob,id,null,"fake"));
        expect(403,() -> db.reschedule(bob,id,null,null,3,at(660,720)));
        expect(400,() -> db.cancel(admin,id,null,""));
        check(db.agenda(hour.date.toLocalDate(),bob.name).isEmpty(),"my bookings isolation");
        expect(409,() -> db.reschedule(alice,id,null,null,2,hour));
        expect(409,() -> db.book(alice,1,hour,"projector"));
        check(available(db,at(660,690),5,"whiteboard, camera").equals(Arrays.asList("3")),"all equipment + capacity + adjacent");
        check(available(db,hour,5,"projector").equals(Arrays.asList("2")),"exclude occupied room");
        check(available(db,hour,13,"").isEmpty(),"no room large enough");
        expect(400,() -> db.availableRooms(hour,1,"unknown"));
        expect(400,() -> at(605,660));
        expect(403,() -> db.setEnabled(bob,1,false,"closed"));
        expect(403,() -> db.addMaintenance(bob,1,hour,"repair"));
        check(expect(409,() -> db.addMaintenance(admin,3,at(570,630),"repair")).conflicts.get(0)[0].equals(id),"conflict list");
        expect(409,() -> db.setEnabled(admin,3,false,"closed"));
        String block = db.addMaintenance(admin,3,at(660,720),"repair projector");
        check(db.maintenance(hour.date.toLocalDate()).size()==1,"list maintenance");
        expect(409,() -> db.addMaintenance(admin,3,at(690,750),"overlap"));
        expect(409,() -> db.reschedule(alice,id,null,null,3,at(630,690)));
        expect(409,() -> db.book(bob,3,at(690,720),""));
        check(available(db,at(690,720),1,"camera").isEmpty(),"maintenance hidden");
        check(available(db,at(720,750),1,"camera").size()==1,"end boundary available");
        check(!available(db,hour,1,"").contains("3"),"failed move preserves old slots");
        db.removeMaintenance(admin,block,"completed");
        db.reschedule(admin,id,null,"owner requested",3,at(630,690));
        check(available(db,at(600,630),1,"camera").size()==1,"old slot released on overlapping move");
        db.cancel(admin,id,null,"meeting cancelled");
        db.setEnabled(admin,3,false,"closed");
        expect(409,() -> db.book(bob,3,hour,""));
        String other = db.book(bob,2,hour,"");
        expect(409,() -> db.reschedule(bob,other,null,null,3,hour));
        check(available(db,hour,1,"camera").isEmpty(),"disabled hidden");
        db.setEnabled(admin,3,true,"reopened");
        check(available(db,hour,1,"camera").size()==1,"re-enabled");
        check(db.auditLog(admin).size()==6,"only committed admin actions audited");
        expect(403,() -> db.auditLog(bob));
        accounts.logout(token);
        expect(401,() -> accounts.authenticate(token));
        testLegacy(db,alice);
        testRace(db,bob,admin,hour,true);
        BookingStore.Window later = new BookingStore.Window(hour.date.toLocalDate().plusDays(1),600,660);
        testRace(db,bob,admin,later,false);
        expect(409,() -> db.book(bob,1,new BookingStore.Window(later.date.toLocalDate(),570,630),""));
        String adjacent = db.book(bob,1,new BookingStore.Window(later.date.toLocalDate(),570,600),"");
        db.cancel(bob,adjacent,null,null);
        System.out.println("PASS login, ownership, audit, equipment, closures, rollback, legacy and concurrent booking");
    }

    static void testLegacy(BookingStore db, Accounts.User user) throws Exception {
        try (Connection c = db.open()) {
            Database.update(c,"INSERT INTO bookings (id,secret,room,booked_day,first_min,last_min,holder) VALUES ('legacy','old-key',1,CURRENT_DATE,540,570,?)",user.name);
        }
        expect(403,() -> db.cancel(user,"legacy",null,null));
        db.cancel(user,"legacy","old-key",null);
    }
    static void testRace(BookingStore db, Accounts.User user, Accounts.User admin, BookingStore.Window w,
                         boolean includeMaintenance) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12), go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i=0; i<12; i++) {
                final boolean maintenance = includeMaintenance && i==0;
                results.add(workers.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        if (maintenance) db.addMaintenance(admin,1,w,"concurrent repair");
                        else db.book(user,1,w,"");
                        return true;
                    } catch (Problem e) { if (e.status!=409) throw e; return false; }
                }));
            }
            check(ready.await(10,TimeUnit.SECONDS),"workers ready");
            go.countDown();
            int wins=0;
            for (Future<Boolean> result : results) if (result.get(30,TimeUnit.SECONDS)) wins++;
            check(wins==1,"one writer owns the time slot");
        } finally { go.countDown(); workers.shutdownNow(); }
    }
}
