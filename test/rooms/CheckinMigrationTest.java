package rooms;

import java.time.LocalDate;

final class CheckinMigrationTest {
    static void run() throws Exception {
        String url="jdbc:derby:memory:checkinMigration;create=true";
        Acceptance.Time clock=new Acceptance.Time();
        clock.now=clock.now.plusSeconds(7*86400L);
        LocalDate today=LocalDate.now(clock);
        BookingStore initial=new BookingStore(url,clock);
        Accounts accounts=new Accounts(initial,clock);
        accounts.register("tester","a-secure-user-password");
        accounts.register("mixed","a-secure-user-password");
        initial.transaction(c -> {
            Database.update(c,"ALTER TABLE bookings DROP COLUMN checkin_required RESTRICT");
            Database.update(c,"DELETE FROM schema_history WHERE version=4");
            for(int i=0;i<3;i++) {
                seed(c,"old"+i,"tester","CONFIRMED",today.minusDays(i+1),false);
                seed(c,"penalty"+i,"mixed","NO_SHOW",today.minusDays(i+1),false);
            }
            seed(c,"genuine","mixed","NO_SHOW",today.minusDays(1),true);
            seed(c,"ongoing","tester","CONFIRMED",today,false);
            Database.update(c,"INSERT INTO slots VALUES (1,?,600,'ongoing')",java.sql.Date.valueOf(today));
            Database.update(c,"UPDATE accounts SET strikes=1,suspended_until=? WHERE username='mixed'",clock.millis()+604800000L);
            return null;
        });
        clock.now=clock.now.plusSeconds(15*60);
        for(int restart=0;restart<2;restart++) {
            BookingStore db=new BookingStore(url,clock);
            db.tick();
            StoreTest.check(db.query("SELECT id FROM bookings WHERE owner_name='tester' AND status='COMPLETED'").size()==3,"historical meetings completed");
            StoreTest.check(db.query("SELECT minute_of_day FROM slots WHERE booking='ongoing'").size()==1,"ongoing legacy meeting retains slot");
            StoreTest.check(db.query("SELECT username FROM accounts WHERE username='tester' AND strikes=0 AND suspended_until=0").size()==1,"legacy meetings carry no penalty");
            StoreTest.check(db.query("SELECT username FROM accounts WHERE username='mixed' AND strikes=1 AND suspended_until=0").size()==1,"repair preserves genuine strike");
            StoreTest.check(db.query("SELECT target FROM admin_log WHERE operation='correct-no-show'").size()==3,"repair audited once");
            String report=Reports.usage(db,today.minusDays(3),today,"month");
            StoreTest.check(report.contains("\"username\":\"mixed\",\"bookings\":4,\"noShows\":1,\"noShowRate\":1.0"),"exclude exempt bookings from rate");
        }
        BookingStore db=new BookingStore(url,clock);
        Accounts.User tester=new Accounts.User("tester",false);
        for(int i=1;i<=3;i++) {
            LocalDate day=today.plusDays(i);
            db.book(tester,1,Acceptance.w(day,600,660),"",1);
            clock.now=day.atTime(10,15).atZone(clock.getZone()).toInstant();
            db.tick();
        }
        StoreTest.check(StoreTest.expect(403,()->db.book(tester,1,Acceptance.w(today.plusDays(4),600,660),"",1)).code.equals("BOOKING_SUSPENDED"),"new bookings enforce the policy");
        System.out.println("PASS migration, restart, penalty repair and eligible no-show rate");
    }

    private static void seed(java.sql.Connection c,String id,String owner,String status,LocalDate day,boolean eligible) throws java.sql.SQLException {
        Database.update(c,"INSERT INTO bookings(id,room,booked_day,first_min,last_min,holder,owner_name,status) VALUES (?,1,?,600,660,?,?,?)",
                id,java.sql.Date.valueOf(day),owner,owner,status);
        if(eligible) Database.update(c,"INSERT INTO admin_log(actor,operation,target,reason) VALUES (?,'create-booking',?,'CONFIRMED')",owner,id);
        if(status.equals("NO_SHOW")) Database.update(c,"INSERT INTO admin_log(actor,operation,target,reason) VALUES ('/system','state',?,'NO_SHOW')",id);
    }
}
