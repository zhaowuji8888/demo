package rooms;

import java.sql.*;
import java.time.*;
import java.util.*;

public final class BookingStore extends RoomCatalog {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    BookingStore(String url) throws SQLException { this(url,Clock.system(ZONE)); }
    BookingStore(String url,Clock clock) throws SQLException { super(url,clock.withZone(ZONE)); migrate(); }

    void validateBooking(Window w) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate day = w.date.toLocalDate();
        if (w.end-w.start>240 || day.isAfter(now.toLocalDate().plusDays(30)) ||
                day.atStartOfDay().plusMinutes(w.start).isBefore(now.minusMinutes(5)))
            throw new Problem(400,"TIME_OUT_OF_RANGE","Book up to 30 days ahead for 30 minutes to 4 hours");
    }

    BookingStore(javax.sql.DataSource source,Clock clock) { super("",clock.withZone(ZONE)); this.source=source; }
    static final class Conflict extends Problem {
        Conflict(String message) { super(409,message); }
    }
    static final class Window {
        final java.sql.Date date;
        final int start, end;
        Window(LocalDate day, int start, int end) {
            if (day == null || start < 0 || end > 1440 || start >= end || start % 30 != 0 || end % 30 != 0)
                throw new Problem(400,"TIME_OUT_OF_RANGE","Use aligned half-hour slots within one day");
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
    static boolean supports(String inventory, String needs) {
        return needs.isEmpty() || Arrays.asList(inventory.split(",")).containsAll(Arrays.asList(needs.split(",")));
    }
    static String reason(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 200)
            throw new Problem(400,"Provide a reason of 1-200 characters");
        return value.trim();
    }

    private static String[] room(Connection c, int id) throws SQLException {
        List<String[]> result = rows(c,"SELECT enabled,equipment,capacity FROM rooms WHERE id=?",id);
        if (result.isEmpty()) throw new Problem(400,"Unknown room");
        return result.get(0);
    }
    private static void checkRoom(Connection c, int id, Window w, String needs,int attendees) throws SQLException {
        String[] room = room(c,id);
        if ("0".equals(room[0])) throw new Problem(409,"ROOM_DISABLED","Room is disabled");
        if (attendees>Integer.parseInt(room[2])) throw new Problem(409,"CAPACITY_EXCEEDED","Maximum capacity is "+room[2]);
        if (!supports(room[1],needs)) throw new Conflict("Room lacks required equipment");
        if (!rows(c,"SELECT id FROM maintenance WHERE room=? AND booked_day=? AND first_min<? AND last_min>?",
                id,w.date,w.end,w.start).isEmpty()) throw new Problem(409,"ROOM_MAINTENANCE","Room under maintenance");
        if(search(c,w,0,"",id,false).isEmpty()) throw new Problem(409,"ROOM_CLOSED","Room closed for this interval");
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
                    if ("23505".equals(e.getSQLState()) || e.getErrorCode()==1062) throw new Problem(409,"SLOT_CONFLICT","Time slot already reserved");
                    throw e;
                }
            }
        }
    }

    private static void quota(Connection c,String owner,Window w,String exclude) throws SQLException {
        update(c,"UPDATE accounts SET is_admin=is_admin WHERE username=?",owner);
        int count=Integer.parseInt(rows(c,"SELECT COUNT(*) FROM bookings WHERE owner_name=? AND booked_day=? AND status IN ('CONFIRMED','PENDING_APPROVAL','CHECKED_IN') AND id<>?",owner,w.date,exclude).get(0)[0]);
        if (count>=5) throw new Problem(409,"DAILY_QUOTA","Daily limit is five active bookings");
    }
    String book(Accounts.User user,int room,Window w,String rawNeeds,int attendees) throws SQLException {
        return recurring(user,room,w,rawNeeds,attendees,1).get(0)[0];
    }
    private void requireApprovalOpen(LocalDate day,int firstMinute) {
        if (!LocalDateTime.now(clock).isBefore(day.atStartOfDay().plusMinutes(firstMinute)))
            throw new Problem(409,"APPROVAL_CLOSED","Approval closes when the meeting starts");
    }
    private String initialStatus(Connection c,int room,Window w) throws SQLException {
        if (!"1".equals(rows(c,"SELECT approval FROM rooms WHERE id=?",room).get(0)[0])) return "CONFIRMED";
        requireApprovalOpen(w.date.toLocalDate(),w.start);
        return "PENDING_APPROVAL";
    }
    List<String[]> recurring(Accounts.User user,int room,Window w,String rawNeeds,int attendees,int weeks) throws SQLException {
        String needs=equipment(rawNeeds);
        if(attendees<1) throw new Problem(400,"INVALID_ATTENDEES","attendees must be positive");
        if(weeks<1 || weeks>5) throw new Problem(400,"Invalid weekly occurrence count");
        String series=weeks==1?null:UUID.randomUUID().toString();
        return transaction(c -> {
            List<String[]> ids=new ArrayList<>();
            for(int i=0;i<weeks;i++) {
                Window day=new Window(w.date.toLocalDate().plusWeeks(i),w.start,w.end);
                validateBooking(day);
                quota(c,user.name,day,"");
                long suspended=Long.parseLong(rows(c,"SELECT suspended_until FROM accounts WHERE username=?",user.name).get(0)[0]);
                if(suspended>clock.millis()) throw new Problem(403,"BOOKING_SUSPENDED","Bookings suspended for repeated no-shows");
                if(i==0) lockRoom(c,room);
                checkRoom(c,room,day,needs,attendees);
                String id=UUID.randomUUID().toString();
                String status=initialStatus(c,room,day);
                update(c,"INSERT INTO bookings (id,room,booked_day,first_min,last_min,holder,owner_name,needs,attendees,status,series_id,checkin_required) VALUES (?,?,?,?,?,?,?,?,?,?,?,1)",
                    id,room,day.date,day.start,day.end,user.name,user.name,needs,attendees,status,series);
                try { reserveSlots(c,id,room,day); }
                catch(Problem e) { throw new Problem(e.status,e.code,"Room "+room+" is occupied on "+day.date+" at "+Server.time(Integer.toString(day.start))); }
                audit(c,user,"create-booking",id,status);
                notice(c,user.name,id,"BOOKED","Booking "+status+" on "+day.date);
                ids.add(new String[]{id,day.date.toString()});
            }
            return ids;
        });
    }

    static void notice(Connection c,String owner,String booking,String type,String message) throws SQLException {
        update(c,"INSERT INTO notices(id,recipient,booking,kind,body,state) VALUES (?,?,?,?,?,'QUEUED')",
            UUID.randomUUID().toString(),owner,booking,type,message);
    }

    private static String[] authorize(Connection c,Accounts.User user,String id,String why) throws SQLException {
        List<String[]> found=rows(c,"SELECT owner_name,needs,room,attendees,status FROM bookings WHERE id=?",id);
        if(found.isEmpty()) throw new Problem(404,"Booking not found");
        String[] booking=found.get(0);
        if (!user.name.equals(booking[0])) { user.requireAdmin(); reason(why); }
        if (!"CONFIRMED".equals(booking[4]) && !"PENDING_APPROVAL".equals(booking[4]))
            throw new Problem(409,"INVALID_STATE","Booking cannot be modified in its current state");
        return booking;
    }
    void cancel(Accounts.User user,String id,String why) throws SQLException {
        transaction(c -> {
            String[] booking=authorize(c,user,id,why);
            lockRoom(c,Integer.parseInt(booking[2]));
            if(update(c,"UPDATE bookings SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,cancelled_by=? WHERE id=? AND room=? AND status IN ('CONFIRMED','PENDING_APPROVAL')",
                user.name,id,Integer.parseInt(booking[2]))!=1) throw new Problem(409,"BOOKING_CHANGED","Booking changed");
            update(c,"DELETE FROM slots WHERE booking=?",id);
            audit(c,user,"cancel",id,why==null?"Cancelled":why);
            notice(c,booking[0],id,"CANCELLED","Booking cancelled");
            return null;
        });
    }
    void reschedule(Accounts.User user,String id,String why,int room,Window w,Integer attendees) throws SQLException {
        moveSeries(user,id,why,room,w,attendees,false);
    }

    void moveSeries(Accounts.User user,String id,String why,int room,Window w,Integer attendees,boolean future) throws SQLException {
        transaction(c -> {
            authorize(c,user,id,why);
            String[] current=rows(c,"SELECT series_id,booked_day FROM bookings WHERE id=?",id).get(0);
            List<String[]> members=Collections.singletonList(new String[]{id,current[1]});
            if(future && current[0]!=null) members=rows(c,"SELECT id,booked_day FROM bookings WHERE series_id=? AND booked_day>=? AND status IN ('CONFIRMED','PENDING_APPROVAL') ORDER BY booked_day",
                current[0],java.sql.Date.valueOf(current[1]));
            // Free the affected instances within this transaction before moving them onto each other's dates.
            for(String[] member:members) update(c,"DELETE FROM slots WHERE booking=?",member[0]);
            for(String[] member:members) {
                long offset=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(current[1]),LocalDate.parse(member[1]));
                Window target=new Window(w.date.toLocalDate().plusDays(offset),w.start,w.end);
                validateBooking(target);
                moveOne(c,user,member[0],why,room,target,attendees);
            }
            return null;
        });
    }

    private void moveOne(Connection c,Accounts.User user,String id,String why,int room,Window w,Integer attendees) throws SQLException {
            String[] booking=authorize(c,user,id,why);
            if(attendees==null && !"0".equals(booking[3]) || attendees!=null && attendees<1)
                throw new Problem(400,"INVALID_ATTENDEES","Supply a positive attendee count");
            int count=attendees==null?0:attendees;
            quota(c,booking[0],w,id);
            int old=Integer.parseInt(booking[2]);
            lockRoom(c,Math.min(old,room));
            if (old!=room) lockRoom(c,Math.max(old,room));
            checkRoom(c,room,w,booking[1],count);
            String next=initialStatus(c,room,w);
            if(update(c,"UPDATE bookings SET room=?,booked_day=?,first_min=?,last_min=?,attendees=? WHERE id=? AND room=? AND status IN ('CONFIRMED','PENDING_APPROVAL')",
                room,w.date,w.start,w.end,count,id,old)!=1) throw new Problem(409,"BOOKING_CHANGED","Booking changed");
            update(c,"DELETE FROM slots WHERE booking=?",id);
            reserveSlots(c,id,room,w);
            update(c,"UPDATE bookings SET status=?,reminded=0 WHERE id=?",next,id);
            audit(c,user,"reschedule",id,why==null?"Rescheduled":why);
            notice(c,booking[0],id,"RESCHEDULED","Moved to room "+room+" on "+w.date+" at "+Server.time(Integer.toString(w.start)));
    }

    List<String[]> availableRooms(Window w, int people, String rawNeeds) throws SQLException {
        if (people < 1) throw new Problem(400,"people must be positive");
        String needs = equipment(rawNeeds);
        try(Connection c=open()) { return search(c,w,people,needs,null,true); }
    }
    List<String[]> rooms() throws SQLException {
        return query("SELECT id,label,capacity,equipment,enabled,location,description,image_url,approval FROM rooms WHERE deleted=0 ORDER BY id");
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
    List<String[]> agenda(LocalDate day, String owner,boolean includeCancelled) throws SQLException {
        String sql = "SELECT id,room,first_min,last_min,holder,attendees,status FROM bookings WHERE booked_day=?";
        if (!includeCancelled) sql+=" AND status<>\u0027CANCELLED\u0027";
        java.sql.Date date = java.sql.Date.valueOf(day);
        String order = " ORDER BY room,first_min";
        return times(owner == null ? query(sql+order,date) : query(sql+" AND owner_name=?"+order,date,owner));
    }

    List<String[]> auditPage(Accounts.User user,Map<String,String> p) throws SQLException {
        user.requireAdmin();
        int page=Integer.parseInt(p.getOrDefault("page","1")),size=Integer.parseInt(p.getOrDefault("size","50"));
        if(page<1 || page>1000000 || size<1 || size>200) throw new Problem(400,"Invalid page or size");
        List<Object> args=new ArrayList<>();
        String sql="SELECT at_time,actor,operation,target,reason FROM admin_log WHERE 1=1";
        if(p.containsKey("from")) { sql+=" AND at_time>=?"; args.add(Timestamp.valueOf(LocalDate.parse(p.get("from")).atStartOfDay())); }
        if(p.containsKey("to")) { sql+=" AND at_time<?"; args.add(Timestamp.valueOf(LocalDate.parse(p.get("to")).plusDays(1).atStartOfDay())); }
        return query(sql+" ORDER BY seq DESC OFFSET "+((page-1)*size)+" ROWS FETCH NEXT "+size+" ROWS ONLY",args.toArray());
    }
    List<String[]> auditLog(Accounts.User user) throws SQLException {
        user.requireAdmin();
        return query("SELECT at_time,actor,operation,target,reason FROM admin_log ORDER BY seq DESC");
    }

    void setEnabled(Accounts.User user, int id, boolean enabled, String why) throws SQLException {
        user.requireAdmin();
        transaction(c -> {
            lockRoom(c,id);
            if (!enabled) {
                LocalDateTime now = LocalDateTime.now(clock);
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
        return transaction(c -> {
            lockRoom(c,id);
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
        transaction(c -> {
            List<String[]> blocks=rows(c,"SELECT room FROM maintenance WHERE id=?",id);
            if(blocks.isEmpty()) throw new Problem(404,"Maintenance not found");
            lockRoom(c,Integer.parseInt(blocks.get(0)[0]));
            if (update(c,"DELETE FROM maintenance WHERE id=?",id)==0) throw new Problem(404,"Maintenance not found");
            audit(c,user,"remove-maintenance",id,why);
            return null;
        });
    }
    List<String[]> maintenance(LocalDate day) throws SQLException {
        return times(query("SELECT id,room,first_min,last_min,reason FROM maintenance WHERE booked_day=? ORDER BY room,first_min",
                java.sql.Date.valueOf(day)));
    }

    void decide(Accounts.User user,String id,boolean approve,String why) throws SQLException {
        user.requireAdmin();
        transaction(c -> {
            if (approve) {
                // Lock the row before checking the deadline against the current booking window.
                update(c,"UPDATE bookings SET status=status WHERE id=?",id);
            }
            String[] b=lifecycleRow(c,id);
            if (approve && "PENDING_APPROVAL".equals(b[1])) requireApprovalOpen(LocalDate.parse(b[2]),Integer.parseInt(b[3]));
            transition(c,id,"PENDING_APPROVAL",approve?"CONFIRMED":"REJECTED");
            if(!approve) update(c,"DELETE FROM slots WHERE booking=?",id);
            audit(c,user,approve?"approve":"deny",id,why);
            notice(c,b[0],id,"DECISION",approve?"Booking approved":"Booking declined");
            return null;
        });
    }
    private static String[] lifecycleRow(Connection c,String id) throws SQLException {
        List<String[]> b=rows(c,"SELECT owner_name,status,booked_day,first_min,last_min,checkin_required FROM bookings WHERE id=?",id);
        if(b.isEmpty()) throw new Problem(404,"Booking not found");
        return b.get(0);
    }
    private static void transition(Connection c,String id,String from,String to) throws SQLException {
        if(update(c,"UPDATE bookings SET status=? WHERE id=? AND status=?",to,id,from)!=1)
            throw new Problem(409,"INVALID_STATE","Booking state changed");
    }
    void checkin(Accounts.User user,String id) throws SQLException {
        transaction(c -> {
            String[] b=lifecycleRow(c,id);
            if(!user.name.equals(b[0])) throw new Problem(403,"Owner required");
            if("CHECKED_IN".equals(b[1])) return null;
            LocalDateTime now=LocalDateTime.now(clock);
            LocalDateTime start=LocalDate.parse(b[2]).atStartOfDay().plusMinutes(Integer.parseInt(b[3]));
            if(!"CONFIRMED".equals(b[1]) || now.isBefore(start) || !now.isBefore(start.plusMinutes(15)))
                throw new Problem(409,"INVALID_STATE","Check-in is available in the first 15 minutes");
            transition(c,id,"CONFIRMED","CHECKED_IN");
            update(c,"UPDATE bookings SET checked_in_at=CURRENT_TIMESTAMP WHERE id=?",id);
            audit(c,user,"checkin",id,"Checked in");
            return null;
        });
    }
    static final class ScanEntry {
        final String id, owner, status;
        final LocalDate day;
        final int firstMinute, lastMinute;
        final boolean requiresCheckin;

        ScanEntry(String[] row) {
            id = row[0];
            owner = row[1];
            status = row[2];
            day = LocalDate.parse(row[3]);
            firstMinute = Integer.parseInt(row[4]);
            lastMinute = Integer.parseInt(row[5]);
            requiresCheckin = "1".equals(row[6]);
        }

        String dueStatus(LocalDateTime now) {
            LocalDateTime start = day.atStartOfDay().plusMinutes(firstMinute);
            LocalDateTime end = day.atStartOfDay().plusMinutes(lastMinute);
            if ("PENDING_APPROVAL".equals(status) && !now.isBefore(start)) return "EXPIRED";
            if ("CONFIRMED".equals(status) && requiresCheckin && !now.isBefore(start.plusMinutes(15))) return "NO_SHOW";
            if (("CHECKED_IN".equals(status) || "CONFIRMED".equals(status) && !requiresCheckin) && !now.isBefore(end)) return "COMPLETED";
            return null;
        }
    }

    List<String[]> scanCandidates(LocalDate day) throws SQLException {
        return query("SELECT id,owner_name,status,booked_day,first_min,last_min,checkin_required FROM bookings "+
                "WHERE booked_day<=? AND status IN ('PENDING_APPROVAL','CONFIRMED','CHECKED_IN')",java.sql.Date.valueOf(day));
    }

    void tick() throws SQLException {
        LocalDateTime now = LocalDateTime.now(clock);
        for (String[] row : scanCandidates(now.toLocalDate())) applyScan(new ScanEntry(row),now);
    }

    // A reschedule can preserve CONFIRMED. Match the entire time window before applying its consequences.
    private static final String SCANNED_SLOT = " WHERE id=? AND status=? AND booked_day=? AND first_min=? AND last_min=?";

    void applyScan(ScanEntry booking,LocalDateTime now) throws SQLException {
        String next = booking.dueStatus(now);
        LocalDateTime start = booking.day.atStartOfDay().plusMinutes(booking.firstMinute);
        boolean reminderDue = "CONFIRMED".equals(booking.status) && !now.isBefore(start.minusMinutes(15)) && now.isBefore(start);
        if (next == null && !reminderDue) return;
        java.sql.Date date = java.sql.Date.valueOf(booking.day);
        transaction(c -> {
            if (next == null) {
                if (update(c,"UPDATE bookings SET reminded=1"+SCANNED_SLOT+" AND reminded=0",
                        booking.id,booking.status,date,booking.firstMinute,booking.lastMinute)==1)
                    notice(c,booking.owner,booking.id,"REMINDER","Booking starts at "+start);
                return null;
            }
            int changed = update(c,"UPDATE bookings SET status=?"+SCANNED_SLOT,next,booking.id,booking.status,
                    date,booking.firstMinute,booking.lastMinute);
            if (changed == 0) return null;
            update(c,"DELETE FROM slots WHERE booking=?",booking.id);
            if ("NO_SHOW".equals(next)) {
                update(c,"UPDATE accounts SET strikes=strikes+1 WHERE username=?",booking.owner);
                Instant until = Instant.ofEpochMilli(clock.millis()).plus(Duration.ofDays(7));
                int suspended = update(c,"UPDATE accounts SET strikes=0,suspended_until=? WHERE username=? AND strikes>=3",
                        until.toEpochMilli(),booking.owner);
                String message = "Missed check-in for the booking starting at "+start+".";
                if (suspended == 1) message += " After three no-shows, new bookings are suspended until "+until.atZone(ZONE)+".";
                notice(c,booking.owner,booking.id,"NO_SHOW",message);
            }
            audit(c,new Accounts.User("/system",true),"state",booking.id,next);
            return null;
        });
    }
    List<String[]> suggestions(Window w,int people,String needs,int room,boolean times) throws SQLException {
        List<String[]> found=new ArrayList<>();
        if(!times) {
            for(String[] r:availableRooms(w,people,needs)) if(Integer.parseInt(r[0])!=room && found.size()<3) found.add(r);
        } else {
            for(int delta=30;delta<=120;delta+=30) for(int sign:new int[]{1,-1}) {
                int start=w.start+sign*delta,end=w.end+sign*delta;
                if(start<0 || end>1440 || found.size()>=3) continue;
                Window slot=new Window(w.date.toLocalDate(),start,end);
                try { validateBooking(slot); } catch(Problem outside) { continue; }
                for(String[] r:availableRooms(slot,people,needs)) if(Integer.parseInt(r[0])==room)
                    found.add(new String[]{Server.time(""+start),Server.time(""+end)});
            }
        }
        return found;
    }
}
