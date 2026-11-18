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
    final Acceptance.Time clock = new Acceptance.Time();
    final BookingStore db;
    final Accounts accounts;
    final Accounts.User alice, bob, admin;
    final String aliceToken;

    StoreTest() throws SQLException {
        db = new BookingStore("jdbc:derby:memory:store"+UUID.randomUUID()+";create=true", clock);
        accounts = new Accounts(db, clock);
        accounts.bootstrap("test-admin-password");
        accounts.register("alice","test-alice-password");
        accounts.register("bobby","test-bobby-password");
        aliceToken = accounts.login("ALICE","test-alice-password","local");
        alice = accounts.authenticate(aliceToken);
        bob = accounts.authenticate(accounts.login("bobby","test-bobby-password","local"));
        admin = accounts.authenticate(accounts.login("admin","test-admin-password","local"));
    }

    BookingStore.Window at(int start,int end) {
        return new BookingStore.Window(LocalDate.now(clock).plusDays(3),start,end);
    }
    static List<String> available(BookingStore db, BookingStore.Window w, int people, String needs) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (String[] room : db.availableRooms(w,people,needs)) ids.add(room[0]);
        return ids;
    }
    void registrationAndLogout() throws Exception {
        expect(409,() -> accounts.register("ADMIN","test-claim-admin"));
        expect(409,() -> accounts.register("Alice","test-duplicate"));
        expect(401,() -> accounts.login("alice","incorrect","local"));
        accounts.logout(aliceToken);
        expect(401,() -> accounts.authenticate(aliceToken));
    }

    void bookingOwnership() throws Exception {
        BookingStore.Window hour = at(600,660);
        String id = db.book(alice,3,hour,"camera,projector",1);
        expect(403,() -> db.cancel(bob,id,"fake"));
        expect(403,() -> db.reschedule(bob,id,null,3,at(660,720),1));
        expect(400,() -> db.cancel(admin,id,""));
        check(db.agenda(hour.date.toLocalDate(),bob.name,false).isEmpty(),"my bookings isolation");
    }

    void equipmentAndCapacity() throws Exception {
        BookingStore.Window hour = at(600,660);
        String id = db.book(alice,3,hour,"camera,projector",1);
        expect(409,() -> db.reschedule(alice,id,null,2,hour,1));
        expect(409,() -> db.book(alice,1,hour,"projector",1));
        check(available(db,at(660,690),5,"whiteboard, camera").equals(Arrays.asList("3")),"all equipment + capacity + adjacent");
        check(available(db,hour,5,"projector").equals(Arrays.asList("2")),"exclude occupied room");
        check(available(db,hour,13,"").isEmpty(),"no room large enough");
        expect(400,() -> db.availableRooms(hour,1,"unknown"));
        expect(400,() -> at(605,660));
        expect(409,() -> db.book(alice,1,hour,"",20));
    }

    void maintenanceAndRescheduleRollback() throws Exception {
        BookingStore.Window hour = at(600,660);
        String id = db.book(alice,3,hour,"camera,projector",1);
        expect(403,() -> db.setEnabled(bob,1,false,"closed"));
        expect(403,() -> db.addMaintenance(bob,1,hour,"repair"));
        check(expect(409,() -> db.addMaintenance(admin,3,at(570,630),"repair")).conflicts.get(0)[0].equals(id),"conflict list");
        expect(409,() -> db.setEnabled(admin,3,false,"closed"));
        String block = db.addMaintenance(admin,3,at(660,720),"repair projector");
        check(db.maintenance(hour.date.toLocalDate()).size()==1,"list maintenance");
        expect(409,() -> db.addMaintenance(admin,3,at(690,750),"overlap"));
        expect(409,() -> db.reschedule(alice,id,null,3,at(630,690),1));
        expect(409,() -> db.book(bob,3,at(690,720),"",1));
        check(available(db,at(690,720),1,"camera").isEmpty(),"maintenance hidden");
        check(available(db,at(720,750),1,"camera").size()==1,"end boundary available");
        check(!available(db,hour,1,"").contains("3"),"failed move preserves old slots");
        db.removeMaintenance(admin,block,"completed");
        db.reschedule(admin,id,"owner requested",3,at(630,690),1);
        check(available(db,at(600,630),1,"camera").size()==1,"old slot released on overlapping move");
        db.cancel(admin,id,"meeting cancelled");
        List<String> actions = new ArrayList<>();
        for (String[] row : db.auditLog(admin)) actions.add(row[2]);
        check(actions.containsAll(Arrays.asList("add-maintenance","remove-maintenance","reschedule","cancel")), "committed changes audited");
        check(Collections.frequency(actions,"add-maintenance")==1 && Collections.frequency(actions,"reschedule")==1, "failed changes leave no audit entry");
        expect(403,() -> db.auditLog(bob));
    }

    void disabledRooms() throws Exception {
        BookingStore.Window hour = at(600,660);
        db.setEnabled(admin,3,false,"closed");
        expect(409,() -> db.book(bob,3,hour,"",1));
        String other = db.book(bob,2,hour,"",1);
        expect(409,() -> db.reschedule(bob,other,null,3,hour,1));
        check(available(db,hour,1,"camera").isEmpty(),"disabled hidden");
        db.setEnabled(admin,3,true,"reopened");
        check(available(db,hour,1,"camera").size()==1,"re-enabled");
    }

    void concurrentMaintenanceAndBookings() throws Exception {
        testRace(db,Collections.nCopies(12,bob),admin,at(600,660),true);
    }

    void concurrentBookingsAndAdjacentSlots() throws Exception {
        BookingStore.Window later = at(600,660);
        testRace(db,Collections.nCopies(12,bob),admin,later,false);
        expect(409,() -> db.book(bob,1,new BookingStore.Window(later.date.toLocalDate(),570,630),"",1));
        String adjacent = db.book(bob,1,new BookingStore.Window(later.date.toLocalDate(),570,600),"",1);
        db.cancel(bob,adjacent,null);
    }

    void distinctEmployeesCompeteForOneRoom() throws Exception {
        List<Accounts.User> employees=new ArrayList<>();
        for(int i=0;i<12;i++) {
            String name="employee"+i;
            accounts.register(name,"employee-password");
            employees.add(new Accounts.User(name,false));
        }
        BookingStore.Window hour=at(600,660);
        String winner=testRace(db,employees,admin,hour,false);
        List<String[]> bookings=db.query("SELECT id FROM bookings WHERE room=1 AND booked_day=?",hour.date);
        check(bookings.size()==1 && winner.equals(bookings.get(0)[0]),"only the winning booking exists");
        List<String[]> slots=db.query("SELECT minute_of_day,booking FROM slots WHERE room=1 AND booked_day=? ORDER BY minute_of_day",hour.date);
        check(slots.size()==2,"one slot for each half hour");
        for(int i=0;i<slots.size();i++) {
            check(Integer.parseInt(slots.get(i)[0])==600+30*i && winner.equals(slots.get(i)[1]),"winning booking owns each slot");
        }
    }

    public static void main(String[] args) throws Exception {
        new StoreTest().registrationAndLogout();
        new StoreTest().bookingOwnership();
        new StoreTest().equipmentAndCapacity();
        new StoreTest().maintenanceAndRescheduleRollback();
        new StoreTest().disabledRooms();
        new StoreTest().concurrentMaintenanceAndBookings();
        new StoreTest().concurrentBookingsAndAdjacentSlots();
        new StoreTest().distinctEmployeesCompeteForOneRoom();
        new Acceptance().bookingHorizon();
        new Acceptance().loginLimits();
        new Acceptance().calendarOverrides();
        new Acceptance().recurringRollbackAndReschedule();
        new Acceptance().checkinAndPenalties();
        StaleScanTest.run();
        CheckinMigrationTest.run();
        System.out.println("PASS domain scenarios");
    }

    static String testRace(BookingStore db, List<Accounts.User> users, Accounts.User admin, BookingStore.Window w,
                         boolean includeMaintenance) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(users.size());
        CountDownLatch ready = new CountDownLatch(users.size()), go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i=0; i<users.size(); i++) {
                Accounts.User user=users.get(i);
                final boolean maintenance = includeMaintenance && i==0;
                results.add(workers.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        if (maintenance) return db.addMaintenance(admin,1,w,"concurrent repair");
                        return db.book(user,1,w,"",1);
                    } catch (Problem e) { if (e.status!=409) throw e; return null; }
                }));
            }
            check(ready.await(10,TimeUnit.SECONDS),"workers ready");
            go.countDown();
            List<String> winners=new ArrayList<>();
            for (Future<String> result : results) {
                String id=result.get(30,TimeUnit.SECONDS);
                if(id!=null) winners.add(id);
            }
            check(winners.size()==1,"one writer owns the time slot");
            return winners.get(0);
        } finally { go.countDown(); workers.shutdownNow(); }
    }
}
