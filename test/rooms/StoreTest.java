package rooms;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

public class StoreTest {
    static void check(boolean yes,String what){if(!yes)throw new AssertionError(what);}
    public static void main(String[] args) throws Exception {
        BookingStore db=new BookingStore("jdbc:derby:memory:roomtest;create=true");
        LocalDate d=LocalDate.of(2030,1,10);
        ExecutorService pool=Executors.newFixedThreadPool(12);
        CountDownLatch ready=new CountDownLatch(12), go=new CountDownLatch(1);
        BookingStore.Ticket winner=null; int conflicts=0;
        try {
            List<Future<BookingStore.Ticket>> results=new ArrayList<>();
            for(int i=0;i<12;i++)results.add(pool.submit(()->{
                ready.countDown(); go.await();
                try{return db.book(1,d,600,660,"Alice");}
                catch(BookingStore.Conflict expected){return null;}
            }));
            check(ready.await(10,TimeUnit.SECONDS),"start barrier"); go.countDown();
            for(Future<BookingStore.Ticket> f:results){
                BookingStore.Ticket t=f.get(30,TimeUnit.SECONDS);
                if(t==null) conflicts++; else winner=t;
            }
        } finally {go.countDown();pool.shutdownNow();}
        check(winner!=null && conflicts==11 && db.agenda(d).size()==1,"single winner");
        System.out.println("PASS concurrent booking: 1 committed, 11 conflicts");
        try{db.book(1,d,570,630,"Bob");throw new AssertionError("overlap accepted");}
        catch(BookingStore.Conflict expected){}
        db.book(1,d,570,600,"Bob");
        check(db.agenda(d).size()==2,"partial booking rolled back; adjacent allowed");
        db.book(2,d,600,660,"Carol"); db.book(1,d.plusDays(1),600,660,"Dan");
        check(db.agenda(d).size()==3 && db.agenda(d.plusDays(1)).size()==1,"room and day isolation");
        check(!db.cancel(winner.id,"wrong"),"wrong key");
        check(db.cancel(winner.id,winner.cancelKey),"cancel with key");
        db.book(1,d,600,660,"Eve");
        try{db.book(1,d,605,630,"Fail");throw new AssertionError("invalid slot");}
        catch(IllegalArgumentException expected){}
        System.out.println("PASS rollback, adjacency, room/day isolation, cancellation, rebooking, validation");
    }
}
