package rooms;

import java.sql.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

final class Reports {
    static String usage(BookingStore db,LocalDate from,LocalDate to,String granularity) throws SQLException {
        if(to.isBefore(from) || to.isAfter(from.plusDays(365)) || !(granularity.equals("week") || granularity.equals("month")))
            throw new Problem(400,"Use a range of at most 366 days and granularity week or month");
        return db.transaction(c -> {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            StringJoiner buckets=new StringJoiner(",","[","]");
            LocalDate start=from;
            while(!start.isAfter(to)) {
                LocalDate boundary=granularity.equals("month")?start.with(TemporalAdjusters.lastDayOfMonth()):
                    start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                LocalDate end=boundary.isAfter(to)?to:boundary;
                buckets.add(bucket(c,start,end));
                start=end.plusDays(1);
            }
            return buckets.toString();
        });
    }
    private static String bucket(Connection c,LocalDate start,LocalDate end) throws SQLException {
        Map<Integer,int[]> metrics=new TreeMap<>();
        Map<String,int[]> users=new TreeMap<>();
        Map<String,Integer> peaks=new TreeMap<>();
        java.sql.Date first=java.sql.Date.valueOf(start),last=java.sql.Date.valueOf(end);
        for(String[] row:Database.rows(c,"SELECT id FROM rooms WHERE deleted=0 ORDER BY id")) metrics.put(Integer.parseInt(row[0]),new int[2]);
        for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1)) {
            java.sql.Date date=java.sql.Date.valueOf(day);
            List<String[]> windows=Database.rows(c,"SELECT r.id,COALESCE(d.opens,h.opens,540),COALESCE(d.closes,h.closes,1080) FROM rooms r"
                +RoomCatalog.CALENDAR+" WHERE r.deleted=0 AND r.enabled=1 AND (d.room IS NOT NULL OR g.booked_day IS NULL)",
                day.getDayOfWeek().getValue(),date,date);
            Map<Integer,BitSet> capacity=new HashMap<>();
            for(String[] w:windows) {
                BitSet slots=new BitSet(48);
                slots.set(Integer.parseInt(w[1])/30,Integer.parseInt(w[2])/30);
                capacity.put(Integer.parseInt(w[0]),slots);
            }
            for(String[] m:Database.rows(c,"SELECT room,first_min,last_min FROM maintenance WHERE booked_day=?",date)) {
                BitSet slots=capacity.get(Integer.parseInt(m[0]));
                if(slots!=null) slots.clear(Integer.parseInt(m[1])/30,Integer.parseInt(m[2])/30);
            }
            Map<Integer,BitSet> occupied=new HashMap<>();
            for(String[] b:Database.rows(c,"SELECT room,first_min,last_min FROM bookings WHERE booked_day=? AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')",date)) {
                int room=Integer.parseInt(b[0]);
                occupied.computeIfAbsent(room,id -> new BitSet(48)).set(Integer.parseInt(b[1])/30,Integer.parseInt(b[2])/30);
            }
            for(Map.Entry<Integer,BitSet> entry:capacity.entrySet()) {
                int[] count=metrics.get(entry.getKey());
                count[1]+=entry.getValue().cardinality()*30;
                BitSet booked=occupied.getOrDefault(entry.getKey(),new BitSet());
                booked.and(entry.getValue());
                count[0]+=booked.cardinality()*30;
                for(int slot=booked.nextSetBit(0);slot>=0;slot=booked.nextSetBit(slot+1))
                    peaks.merge(day.getDayOfWeek().getValue()+"@"+Server.time(""+(slot*30)),1,Integer::sum);
            }
        }
        for(String[] row:Database.rows(c,"SELECT owner_name,status,checkin_required,COUNT(*) FROM bookings WHERE booked_day BETWEEN ? AND ? GROUP BY owner_name,status,checkin_required",first,last)) {
            int[] count=users.computeIfAbsent(row[0],k -> new int[3]);
            int n=Integer.parseInt(row[3]);count[0]+=n;
            if("1".equals(row[2])) {
                count[2]+=n;
                if("NO_SHOW".equals(row[1])) count[1]+=n;
            }
        }
        StringJoiner rooms=new StringJoiner(",","[","]"),people=new StringJoiner(",","[","]"),heat=new StringJoiner(",","{","}");
        for(Map.Entry<Integer,int[]> row:metrics.entrySet()) {
            int[] n=row.getValue();
            rooms.add("{\"room\":"+row.getKey()+",\"bookedMinutes\":"+n[0]+",\"availableMinutes\":"+n[1]+",\"utilization\":"+ratio(n[0],n[1])+"}");
        }
        for(Map.Entry<String,int[]> row:users.entrySet()) {
            int[] n=row.getValue();
            people.add("{\"username\":"+Server.q(row.getKey())+",\"bookings\":"+n[0]+",\"noShows\":"+n[1]+",\"noShowRate\":"+ratio(n[1],n[2])+"}");
        }
        peaks.forEach((k,v) -> heat.add(Server.q(k)+":"+v));
        List<Integer> idle=new ArrayList<>(metrics.keySet());
        idle.removeIf(id -> metrics.get(id)[1]==0);
        idle.sort(Comparator.comparingDouble(id -> (double)metrics.get(id)[0]/metrics.get(id)[1]));
        return "{\"from\":"+Server.q(start.toString())+",\"to\":"+Server.q(end.toString())+",\"rooms\":"+rooms+
            ",\"users\":"+people+",\"heatmap\":"+heat+",\"idleTop10\":"+idle.subList(0,Math.min(10,idle.size()))+"}";
    }
    private static String ratio(int n,int d) { return d==0?"null":Double.toString((double)n/d); }
}
