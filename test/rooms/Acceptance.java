package rooms;

import java.time.*;
import java.util.*;

final class Acceptance {
    static class Time extends Clock {
        Instant now = Instant.parse("2030-06-03T02:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return BookingStore.ZONE; }
        public Clock withZone(ZoneId z) { if(!z.equals(getZone())) throw new IllegalArgumentException(); return this; }
    }
    static BookingStore.Window w(LocalDate d,int from,int end) { return new BookingStore.Window(d,from,end); }
    final Time clock = new Time();
    final BookingStore db;
    final Accounts accounts;
    final Accounts.User user = new Accounts.User("tester",false), admin = new Accounts.User("admin",true);
    final LocalDate today = LocalDate.now(clock);

    Acceptance() throws java.sql.SQLException {
        db = new BookingStore("jdbc:derby:memory:accept"+UUID.randomUUID()+";create=true",clock);
        accounts = new Accounts(db,clock);
        accounts.bootstrap("a-secure-admin-password");
        accounts.register("tester","a-secure-user-password");
    }

    void bookingHorizon() throws Exception {
        for(LocalDate invalid:Arrays.asList(today.minusDays(1),today.plusDays(31)))
            StoreTest.check(StoreTest.expect(400,()->db.book(user,1,w(invalid,600,660),"",1)).code.equals("TIME_OUT_OF_RANGE"),"time horizon");
        StoreTest.expect(400,()->db.book(user,1,w(today,600,870),"",1));
    }

    void loginLimits() throws Exception {
        for(int i=0;i<5;i++) StoreTest.expect(401,()->accounts.login("tester","incorrect","ip-a"));
        StoreTest.check(StoreTest.expect(429,()->accounts.login("tester","a-secure-user-password","ip-b")).code.equals("ACCOUNT_LOCKED"),"account lock");
        clock.now=clock.now.plusSeconds(899);
        StoreTest.expect(429,()->accounts.login("tester","a-secure-user-password","ip-b"));
        clock.now=clock.now.plusSeconds(1);
        accounts.login("tester","a-secure-user-password","ip-b");
        for(int i=0;i<20;i++) StoreTest.expect(401,()->accounts.login("absent","incorrect","ip-c"));
        StoreTest.expect(429,()->accounts.login("absent","incorrect","ip-c"));
        StoreTest.expect(401,()->accounts.login("absent","incorrect","ip-d"));
    }

    void calendarOverrides() throws Exception {
        LocalDate start=today.plusDays(1);
        int room=db.saveRoom(admin,null,"Lab",8,"camera","F2","Lab","",false,"open");
        db.calendar(admin,room,"weekly",null,start.getDayOfWeek().getValue(),0,0,"closed");
        StoreTest.check(StoreTest.expect(409,()->db.book(user,room,w(start,600,660),"",1)).code.equals("ROOM_CLOSED"),"weekly hours");
        db.calendar(admin,room,"weekly",null,start.getDayOfWeek().getValue(),480,1200,"open");
        db.calendar(admin,0,"holiday",start,0,0,0,"holiday");
        StoreTest.expect(409,()->db.book(user,room,w(start,600,660),"",1));
        db.calendar(admin,room,"override",start,0,540,1080,"business need");
        db.book(user,room,w(start,600,660),"",1);
    }

    void recurringRollbackAndReschedule() throws Exception {
        LocalDate start=today.plusDays(1);
        int room=db.saveRoom(admin,null,"Lab",8,"camera","F2","Lab","",false,"open");
        String blocker=db.book(user,room,w(start.plusWeeks(2),600,660),"",1);
        StoreTest.expect(409,()->db.recurring(user,room,w(start,600,660),"",2,4));
        StoreTest.check(db.agenda(start,user.name,false).isEmpty(),"batch rollback");
        db.cancel(user,blocker,null);
        List<String[]> series=db.recurring(user,room,w(start,600,660),"",2,4);
        db.moveSeries(user,series.get(1)[0],null,room,w(start.plusWeeks(1),720,780),3,true);
        StoreTest.check(db.agenda(start,user.name,false).get(0)[2].equals("10:00"),"past series unchanged");
        StoreTest.check(db.agenda(start.plusWeeks(3),user.name,false).get(0)[2].equals("12:00"),"future series moved");
        StoreTest.expect(409,()->db.deleteRoom(admin,room,"delete"));
        StoreTest.check(!Reports.usage(db,start,start,"week").isEmpty(),"report");
    }

    void checkinAndPenalties() throws Exception {
        LocalDate start=today.plusDays(1);
        int room=1;
        String id=db.book(user,room,w(start,600,660),"",2);
        clock.now=start.atTime(10,0).atZone(clock.getZone()).toInstant();
        db.checkin(user,id); db.checkin(user,id);
        clock.now=clock.now.plusSeconds(3600); db.tick();
        StoreTest.check(db.agenda(start,user.name,false).get(0)[6].equals("COMPLETED"),"completion");
        for(int i=0;i<3;i++) {
            LocalDate date=start.plusDays(i+1);
            db.book(user,room,w(date,600,660),"",2);
            clock.now=date.atTime(10,15).atZone(clock.getZone()).toInstant();
            db.tick();
        }
        StoreTest.check(StoreTest.expect(403,()->db.book(user,room,w(start.plusDays(5),600,660),"",1)).code.equals("BOOKING_SUSPENDED"),"suspension");
    }
}
