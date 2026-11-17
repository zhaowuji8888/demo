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
            RangeData data = new RangeData(c, from, to);
            StringJoiner buckets=new StringJoiner(",","[","]");
            LocalDate start=from;
            while(!start.isAfter(to)) {
                LocalDate boundary=granularity.equals("month")?start.with(TemporalAdjusters.lastDayOfMonth()):
                    start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                LocalDate end=boundary.isAfter(to)?to:boundary;
                buckets.add(bucket(data,start,end));
                start=end.plusDays(1);
            }
            return buckets.toString();
        });
    }

    private static final class RoomCalendar {
        final boolean enabled;
        final Map<Integer, BitSet> weekly = new HashMap<>();
        final Map<LocalDate, BitSet> overrides = new HashMap<>();
        static final BitSet DEFAULT_HOURS = slots(540, 1080);

        RoomCalendar(boolean enabled) {
            this.enabled = enabled;
        }

        BitSet hours(LocalDate date, boolean holiday) {
            if (!enabled) return null;
            BitSet override = overrides.get(date);
            if (override != null || holiday) return override;
            return weekly.getOrDefault(date.getDayOfWeek().getValue(), DEFAULT_HOURS);
        }
    }

    private static BitSet slots(int first, int last) {
        BitSet result = new BitSet(48);
        result.set(first / 30, last / 30);
        return result;
    }

    private static final class DayData {
        final Map<Integer, BitSet> maintenance = new HashMap<>();
        final Map<Integer, BitSet> occupied = new HashMap<>();
        final Map<String, int[]> users = new HashMap<>();
    }

    private static final class RangeData {
        final Map<Integer, RoomCalendar> rooms = new TreeMap<>();
        final Set<LocalDate> holidays = new HashSet<>();
        final Map<LocalDate, DayData> days = new HashMap<>();

        RangeData(Connection c, LocalDate from, LocalDate to) throws SQLException {
            java.sql.Date first = java.sql.Date.valueOf(from), last = java.sql.Date.valueOf(to);
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
                days.put(date, new DayData());
            for (String[] r : Database.rows(c, "SELECT id,enabled FROM rooms WHERE deleted=0"))
                rooms.put(Integer.parseInt(r[0]), new RoomCalendar("1".equals(r[1])));
            for (String[] r : Database.rows(c, "SELECT room,weekday,opens,closes FROM room_hours")) {
                RoomCalendar room = rooms.get(Integer.parseInt(r[0]));
                if (room != null) room.weekly.put(Integer.parseInt(r[1]), slots(Integer.parseInt(r[2]), Integer.parseInt(r[3])));
            }
            for (String[] r : Database.rows(c, "SELECT booked_day FROM holidays WHERE booked_day BETWEEN ? AND ?", first, last))
                holidays.add(LocalDate.parse(r[0]));
            for (String[] r : Database.rows(c, "SELECT room,booked_day,opens,closes FROM day_override WHERE booked_day BETWEEN ? AND ?", first, last)) {
                RoomCalendar room = rooms.get(Integer.parseInt(r[0]));
                if (room != null) room.overrides.put(LocalDate.parse(r[1]), slots(Integer.parseInt(r[2]), Integer.parseInt(r[3])));
            }
            for (String[] r : Database.rows(c, "SELECT room,booked_day,first_min,last_min FROM maintenance WHERE booked_day BETWEEN ? AND ?", first, last)) {
                DayData day = days.get(LocalDate.parse(r[1]));
                day.maintenance.computeIfAbsent(Integer.parseInt(r[0]), id -> new BitSet(48))
                    .set(Integer.parseInt(r[2]) / 30, Integer.parseInt(r[3]) / 30);
            }
            String sql = "SELECT booked_day,room,first_min,last_min,owner_name,status,checkin_required FROM bookings WHERE booked_day BETWEEN ? AND ?";
            try (PreparedStatement p = Database.prepare(c, sql, first, last); ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    DayData day = days.get(r.getDate(1).toLocalDate());
                    String status = r.getString(6);
                    if ("CONFIRMED".equals(status) || "CHECKED_IN".equals(status) || "COMPLETED".equals(status)) {
                        day.occupied.computeIfAbsent(r.getInt(2), id -> new BitSet(48))
                            .set(r.getInt(3) / 30, r.getInt(4) / 30);
                    }
                    int[] counts = day.users.computeIfAbsent(r.getString(5), owner -> new int[3]);
                    counts[0]++;
                    if (r.getInt(7) == 1) {
                        counts[2]++;
                        if ("NO_SHOW".equals(status)) counts[1]++;
                    }
                }
            }
        }
    }

    private static String bucket(RangeData data, LocalDate start, LocalDate end) {
        Map<Integer,int[]> metrics=new TreeMap<>();
        Map<String,int[]> users=new TreeMap<>();
        Map<String,Integer> peaks=new TreeMap<>();
        for (int room : data.rooms.keySet()) metrics.put(room, new int[2]);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            DayData day = data.days.get(date);
            boolean holiday = data.holidays.contains(date);
            for (Map.Entry<Integer, RoomCalendar> entry : data.rooms.entrySet()) {
                int room = entry.getKey();
                BitSet hours = entry.getValue().hours(date, holiday);
                if (hours == null) continue;
                BitSet available = (BitSet) hours.clone();
                BitSet maintenance = day.maintenance.get(room);
                if (maintenance != null) available.andNot(maintenance);
                int[] count = metrics.get(room);
                count[1] += available.cardinality() * 30;
                BitSet occupied = day.occupied.get(room);
                if (occupied == null) continue;
                available.and(occupied);
                count[0] += available.cardinality() * 30;
                for (int slot = available.nextSetBit(0); slot >= 0; slot = available.nextSetBit(slot + 1))
                    peaks.merge(date.getDayOfWeek().getValue() + "@" + Server.time("" + (slot * 30)), 1, Integer::sum);
            }
            day.users.forEach((owner, daily) -> {
                int[] counts = users.computeIfAbsent(owner, key -> new int[3]);
                for (int i = 0; i < counts.length; i++) counts[i] += daily[i];
            });
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
