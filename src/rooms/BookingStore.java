package rooms;

import java.sql.*;
import java.time.*;
import java.util.*;

public final class BookingStore extends Database {
    BookingStore(String url) throws SQLException { super(url); migrate(); }

    static final class Conflict extends Problem {
        Conflict(String message) { super(409,message); }
    }
    static final class Window {
        final java.sql.Date date;
        final int start, end;
        Window(LocalDate day, int start, int end) {
            if (day == null || start < 540 || end > 1080 || start >= end || start % 30 != 0 || end % 30 != 0)
                throw new Problem(400,"Use half-hour slots between 09:00 and 18:00");
            this.date = java.sql.Date.valueOf(day);
            this.start = start;
            this.end = end;
        }
    }
    static String equipment(String raw) {
        if (raw.trim().isEmpty()) return "";
        Set<String> names = new TreeSet<>();
        for (String input : raw.split(",",-1)) {
            String name = input.trim().toLowerCase(Locale.ROOT);
            if (!Arrays.asList("projector","camera","whiteboard").contains(name)) throw new Problem(400,"Unknown equipment");
            names.add(name);
        }
        return String.join(",",names);
    }
    private static boolean supports(String inventory, String needs) {
        return needs.isEmpty() || Arrays.asList(inventory.split(",")).containsAll(Arrays.asList(needs.split(",")));
    }
    static String reason(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 200)
            throw new Problem(400,"Provide a reason of 1-200 characters");
        return value.trim();
    }

    // This single-site service serializes scheduling writes. Checks and changes share one transaction.
    private <T> T schedule(Work<T> action) throws SQLException {
        return transaction(c -> {
            try (Statement s = c.createStatement()) { s.execute("LOCK TABLE rooms IN EXCLUSIVE MODE"); }
            return action.run(c);
        });
    }
    private static String[] room(Connection c, int id) throws SQLException {
        List<String[]> result = rows(c,"SELECT enabled,equipment FROM rooms WHERE id=?",id);
        if (result.isEmpty()) throw new Problem(400,"Unknown room");
        return result.get(0);
    }
    private static void checkRoom(Connection c, int id, Window w, String needs) throws SQLException {
        String[] room = room(c,id);
        if ("0".equals(room[0])) throw new Conflict("Room is disabled");
        if (!supports(room[1],needs)) throw new Conflict("Room lacks required equipment");
        if (!rows(c,"SELECT id FROM maintenance WHERE room=? AND booked_day=? AND first_min<? AND last_min>?",
                id,w.date,w.end,w.start).isEmpty()) throw new Conflict("Room under maintenance");
    }
    private static void reserveSlots(Connection c, String id, int room, Window w) throws SQLException {
        try (PreparedStatement slot = c.prepareStatement("INSERT INTO slots VALUES (?,?,?,?)")) {
            slot.setInt(1,room);
            slot.setDate(2,w.date);
            slot.setString(4,id);
            for (int minute = w.start; minute < w.end; minute += 30) {
                slot.setInt(3,minute);
                try { slot.executeUpdate(); }
                catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) throw new Conflict("Time slot already reserved");
                    throw e;
                }
            }
        }
    }

    String book(Accounts.User user, int room, Window w, String rawNeeds) throws SQLException {
        String needs = equipment(rawNeeds);
        return schedule(c -> {
            checkRoom(c,room,w,needs);
            String id = UUID.randomUUID().toString();
            update(c,"INSERT INTO bookings (id,secret,room,booked_day,first_min,last_min,holder,owner_name,needs) VALUES (?,?,?,?,?,?,?,?,?)",
                    id,UUID.randomUUID().toString(),room,w.date,w.start,w.end,user.name,user.name,needs);
            reserveSlots(c,id,room,w);
            return id;
        });
    }

    private static String authorize(Connection c, Accounts.User user, String id, String legacyKey,
                                    String action, String why) throws SQLException {
        List<String[]> found = rows(c,"SELECT owner_name,secret,needs FROM bookings WHERE id=?",id);
        if (found.isEmpty()) throw new Problem(404,"Booking not found");
        String[] booking = found.get(0);
        if (user.name.equals(booking[0])) return booking[2];
        if (!user.admin && booking[0] == null && booking[1].equals(legacyKey)) return booking[2];
        user.requireAdmin();
        audit(c,user,action,id,why);
        return booking[2];
    }

    void cancel(Accounts.User user, String id, String legacyKey, String why) throws SQLException {
        schedule(c -> {
            authorize(c,user,id,legacyKey,"cancel",why);
            update(c,"DELETE FROM bookings WHERE id=?",id);
            return null;
        });
    }

    void reschedule(Accounts.User user, String id, String legacyKey, String why, int room, Window w) throws SQLException {
        schedule(c -> {
            String needs = authorize(c,user,id,legacyKey,"reschedule",why);
            checkRoom(c,room,w,needs);
            update(c,"UPDATE bookings SET room=?,booked_day=?,first_min=?,last_min=? WHERE id=?",room,w.date,w.start,w.end,id);
            update(c,"DELETE FROM slots WHERE booking=?",id);
            reserveSlots(c,id,room,w);
            return null;
        });
    }

    List<String[]> availableRooms(Window w, int people, String rawNeeds) throws SQLException {
        if (people < 1) throw new Problem(400,"people must be positive");
        String needs = equipment(rawNeeds);
        List<String[]> result = query("SELECT r.id,r.label,r.capacity,r.equipment FROM rooms r WHERE r.enabled=1 AND r.capacity>=? "
                + "AND NOT EXISTS (SELECT 1 FROM slots s WHERE s.room=r.id AND s.booked_day=? AND s.minute_of_day>=? AND s.minute_of_day<?) "
                + "AND NOT EXISTS (SELECT 1 FROM maintenance m WHERE m.room=r.id AND m.booked_day=? AND m.first_min<? AND m.last_min>?) "
                + "ORDER BY r.capacity,r.id",people,w.date,w.start,w.end,w.date,w.end,w.start);
        result.removeIf(row -> !supports(row[3],needs));
        return result;
    }
    List<String[]> rooms() throws SQLException {
        return query("SELECT id,label,capacity,equipment,enabled FROM rooms ORDER BY id");
    }
    private static List<String[]> times(List<String[]> records) {
        for (String[] row : records) {
            for (int i = 2; i <= 3; i++) {
                int minutes = Integer.parseInt(row[i]);
                row[i] = String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);
            }
        }
        return records;
    }
    List<String[]> agenda(LocalDate day, String owner) throws SQLException {
        String sql = "SELECT id,room,first_min,last_min,holder FROM bookings WHERE booked_day=?";
        java.sql.Date date = java.sql.Date.valueOf(day);
        String order = " ORDER BY room,first_min";
        return times(owner == null ? query(sql+order,date) : query(sql+" AND owner_name=?"+order,date,owner));
    }

    private static void audit(Connection c, Accounts.User user, String action, String target, String why) throws SQLException {
        update(c,"INSERT INTO admin_log (actor,operation,target,reason) VALUES (?,?,?,?)",user.name,action,target,reason(why));
    }
    List<String[]> auditLog(Accounts.User user) throws SQLException {
        user.requireAdmin();
        return query("SELECT at_time,actor,operation,target,reason FROM admin_log ORDER BY seq DESC");
    }

    private static void rejectAffected(List<String[]> affected) {
        if (!affected.isEmpty()) throw new Problem(409,"Reschedule affected bookings first",affected);
    }
    private static final String AFFECTED = "SELECT id,holder,booked_day,first_min,last_min FROM bookings WHERE room=? ";
    void setEnabled(Accounts.User user, int id, boolean enabled, String why) throws SQLException {
        user.requireAdmin();
        schedule(c -> {
            room(c,id);
            if (!enabled) {
                LocalDateTime now = LocalDateTime.now();
                java.sql.Date today = java.sql.Date.valueOf(now.toLocalDate());
                rejectAffected(rows(c,AFFECTED+"AND (booked_day>? OR (booked_day=? AND last_min>?)) ORDER BY booked_day,first_min",
                        id,today,today,now.getHour()*60+now.getMinute()));
            }
            update(c,"UPDATE rooms SET enabled=? WHERE id=?",enabled ? 1 : 0,id);
            audit(c,user,enabled ? "enable" : "disable",Integer.toString(id),why);
            return null;
        });
    }
    String addMaintenance(Accounts.User user, int id, Window w, String why) throws SQLException {
        user.requireAdmin();
        return schedule(c -> {
            room(c,id);
            rejectAffected(rows(c,AFFECTED+"AND booked_day=? AND first_min<? AND last_min>? ORDER BY first_min",id,w.date,w.end,w.start));
            if (!rows(c,"SELECT id FROM maintenance WHERE room=? AND booked_day=? AND first_min<? AND last_min>?",
                    id,w.date,w.end,w.start).isEmpty()) throw new Conflict("Overlapping maintenance");
            String block = UUID.randomUUID().toString();
            update(c,"INSERT INTO maintenance VALUES (?,?,?,?,?,?)",block,id,w.date,w.start,w.end,reason(why));
            audit(c,user,"add-maintenance",block,why);
            return block;
        });
    }
    void removeMaintenance(Accounts.User user, String id, String why) throws SQLException {
        user.requireAdmin();
        schedule(c -> {
            if (update(c,"DELETE FROM maintenance WHERE id=?",id)==0) throw new Problem(404,"Maintenance not found");
            audit(c,user,"remove-maintenance",id,why);
            return null;
        });
    }
    List<String[]> maintenance(LocalDate day) throws SQLException {
        return times(query("SELECT id,room,first_min,last_min,reason FROM maintenance WHERE booked_day=? ORDER BY room,first_min",
                java.sql.Date.valueOf(day)));
    }
}
