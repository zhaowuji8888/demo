package rooms;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.*;

public final class UsageBenchmark {
    static final LocalDate FIRST = LocalDate.of(2030,6,1); // Saturday
    static BookingStore fixture(String name) throws SQLException {
        BookingStore db = new BookingStore("jdbc:derby:memory:"+name+";create=true");
        db.transaction(c -> {
            Database.update(c,"UPDATE rooms SET enabled=0 WHERE id=3");
            Database.update(c,"INSERT INTO room_hours VALUES (1,6,540,660)");
            Database.update(c,"INSERT INTO holidays VALUES (?)",java.sql.Date.valueOf(FIRST.plusDays(1)));
            Database.update(c,"INSERT INTO day_override VALUES (1,?,600,690)",java.sql.Date.valueOf(FIRST.plusDays(1)));
            Database.update(c,"INSERT INTO maintenance VALUES ('repair',1,?,570,600,'test')",java.sql.Date.valueOf(FIRST));
            Database.update(c,"INSERT INTO accounts (username,salt,verifier,is_admin) VALUES ('alice','-','-',0)");
            for(int i=0;i<365;i++) {
                java.sql.Date date = java.sql.Date.valueOf(FIRST.plusDays(i));
                for(int room=1;room<=2;room++) {
                    Database.update(c,"INSERT INTO bookings(id,room,booked_day,first_min,last_min,holder,owner_name,status,checkin_required) "+
                        "VALUES (?,?,?,540,630,'alice','alice','COMPLETED',1)","b"+i+"r"+room,room,date);
                }
            }
            Database.update(c,"INSERT INTO bookings(id,room,booked_day,first_min,last_min,holder,owner_name,status,checkin_required) "+
                "VALUES ('cancel',1,?,630,660,'alice','alice','CANCELLED',0),('miss',2,?,630,660,'alice','alice','NO_SHOW',1)",
                java.sql.Date.valueOf(FIRST),java.sql.Date.valueOf(FIRST));
            return null;
        });
        return db;
    }

    static final class Counter extends Handler {
        int count;
        public void publish(LogRecord record) { count++; }
        public void flush() {}
        public void close() {}
    }

    public static void main(String[] args) throws Exception {
        BookingStore db = fixture("benchmark");
        Logger logger = Logger.getLogger("rooms.sql");
        Counter counter = new Counter();
        logger.setLevel(Level.FINE);
        logger.addHandler(counter);
        for (int days : new int[]{7,365}) {
            LocalDate end = FIRST.plusDays(days-1);
            for (int warmup=0; warmup<3; warmup++) Reports.usage(db,FIRST,end,"week");
            long[] nanos = new long[7];
            String report="";
            for(int i=0;i<nanos.length;i++) {
                counter.count=0;
                long before=System.nanoTime();
                report=Reports.usage(db,FIRST,end,"week");
                nanos[i]=System.nanoTime()-before;
            }
            Arrays.sort(nanos);
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(report.getBytes("UTF-8"));
            System.out.printf(Locale.ROOT,"%d days: %d SELECTs, median %.2f ms, SHA-256 %064x%n",days,counter.count,nanos[3]/1e6,new java.math.BigInteger(1,hash));
        }
    }
}
