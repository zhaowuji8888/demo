package rooms;

import java.sql.*;
import java.time.*;
import java.util.*;

class RoomCatalog extends Database {
    RoomCatalog(String url) { super(url); }

    static void lockRoom(Connection c,int id) throws SQLException {
        List<String[]> current=rows(c,"SELECT version FROM rooms WHERE id=? AND deleted=0",id);
        if(current.isEmpty()) throw new Problem(404,"Room not found");
        if(update(c,"UPDATE rooms SET version=version+1 WHERE id=? AND version=?",id,Long.parseLong(current.get(0)[0]))!=1)
            throw new Problem(409,"ROOM_CHANGED","Room changed concurrently, retry the request");
    }
    static void audit(Connection c, Accounts.User user,String action,String target,String why) throws SQLException {
        update(c,"INSERT INTO admin_log(actor,operation,target,reason) VALUES (?,?,?,?)",user.name,action,target,BookingStore.reason(why));
    }
    static String text(String value,int max) {
        if(value==null || value.length()>max) throw new Problem(400,"Invalid text length");
        return value.trim();
    }
    int saveRoom(Accounts.User user,Integer existing,String name,int capacity,String equipment,String location,
                 String description,String image,boolean approval,String why) throws SQLException {
        user.requireAdmin();
        final String label=text(name,40), needs=BookingStore.equipment(equipment);
        if(label.isEmpty() || capacity<1) throw new Problem(400,"Name and positive capacity required");
        return transaction(c -> {
            int id;
            if(existing==null) {
                if(mysql(c)) {
                    update(c,"INSERT INTO room_ids VALUES (NULL)");
                    id=Integer.parseInt(rows(c,"SELECT LAST_INSERT_ID()").get(0)[0]);
                } else id=Integer.parseInt(rows(c,"VALUES NEXT VALUE FOR room_ids").get(0)[0]);
                update(c,"INSERT INTO rooms(id,label,capacity) VALUES (?,?,?)",id,label,capacity);
            } else {
                id=existing;
                lockRoom(c,id);
                for(String[] booking:rows(c,"SELECT attendees,needs FROM bookings WHERE room=? AND status IN ('CONFIRMED','PENDING_APPROVAL','CHECKED_IN')",id))
                    if(Integer.parseInt(booking[0])>capacity || !BookingStore.supports(needs,booking[1]))
                        throw new Problem(409,"ROOM_IN_USE","Change conflicts with existing bookings");
            }
            update(c,"UPDATE rooms SET label=?,capacity=?,equipment=?,location=?,description=?,image_url=?,approval=? WHERE id=?",
                label,capacity,needs,text(location,160),text(description,500),text(image,500),approval?1:0,id);
            audit(c,user,existing==null?"room-create":"room-edit",Integer.toString(id),why);
            return id;
        });
    }
    void deleteRoom(Accounts.User user,int id,String why) throws SQLException {
        user.requireAdmin();
        transaction(c -> {
            lockRoom(c,id);
            if(!rows(c,"SELECT id FROM bookings WHERE room=? FETCH FIRST ROW ONLY",id).isEmpty())
                throw new Problem(409,"ROOM_IN_USE","Room has booking history; disable it instead");
            update(c,"UPDATE rooms SET deleted=1,enabled=0 WHERE id=?",id);
            audit(c,user,"room-delete",Integer.toString(id),why);
            return null;
        });
    }
    void calendar(Accounts.User user,int id,String kind,LocalDate date,int weekday,int opens,int closes,String why) throws SQLException {
        user.requireAdmin();
        if(opens<0 || closes>1440 || opens>closes || opens%30!=0 || closes%30!=0) throw new Problem(400,"Invalid hours");
        transaction(c -> {
            if(kind.equals("holiday") || kind.equals("workday")) {
                if(date==null) throw new Problem(400,"Date required");
                for(String[] room:rows(c,"SELECT id FROM rooms WHERE deleted=0 ORDER BY id")) lockRoom(c,Integer.parseInt(room[0]));
                update(c,"DELETE FROM holidays WHERE booked_day=?",java.sql.Date.valueOf(date));
                if(kind.equals("holiday")) update(c,"INSERT INTO holidays VALUES (?)",java.sql.Date.valueOf(date));
            } else {
                lockRoom(c,id);
                if(kind.equals("weekly") && weekday>=1 && weekday<=7) {
                    update(c,"DELETE FROM room_hours WHERE room=? AND weekday=?",id,weekday);
                    update(c,"INSERT INTO room_hours VALUES (?,?,?,?)",id,weekday,opens,closes);
                } else if(kind.equals("override") && date!=null) {
                    update(c,"DELETE FROM day_override WHERE room=? AND booked_day=?",id,java.sql.Date.valueOf(date));
                    update(c,"INSERT INTO day_override VALUES (?,?,?,?)",id,java.sql.Date.valueOf(date),opens,closes);
                } else throw new Problem(400,"Invalid calendar rule");
            }
            audit(c,user,"calendar-"+kind,Integer.toString(id),why);
            return null;
        });
    }

    static final String CALENDAR = " LEFT JOIN room_hours h ON h.room=r.id AND h.weekday=? "
        +"LEFT JOIN day_override d ON d.room=r.id AND d.booked_day=? LEFT JOIN holidays g ON g.booked_day=? ";
    static final String OPEN = " (d.room IS NOT NULL OR g.booked_day IS NULL) AND COALESCE(d.opens,h.opens,540)<=? AND COALESCE(d.closes,h.closes,1080)>=? ";
    static List<String[]> search(Connection c, BookingStore.Window w,int people,String needs,Integer room,boolean checkSlots) throws SQLException {
        StringBuilder sql=new StringBuilder("SELECT r.id,r.label,r.capacity,r.equipment FROM rooms r WHERE r.deleted=0 AND r.enabled=1 AND r.capacity>=? ")
                .append("AND (EXISTS (SELECT 1 FROM day_override d WHERE d.room=r.id AND d.booked_day=? AND d.opens<=? AND d.closes>=?) OR (")
                .append("NOT EXISTS (SELECT 1 FROM day_override d WHERE d.room=r.id AND d.booked_day=?) AND NOT EXISTS (SELECT 1 FROM holidays g WHERE g.booked_day=?) ")
                .append("AND (EXISTS (SELECT 1 FROM room_hours h WHERE h.room=r.id AND h.weekday=? AND h.opens<=? AND h.closes>=?) ");
        int weekday=w.date.toLocalDate().getDayOfWeek().getValue();
        List<Object> args=new ArrayList<>(Arrays.asList(people,w.date,w.start,w.end,w.date,w.date,weekday,w.start,w.end));
        if(w.start>=540 && w.end<=1080) {
            sql.append("OR NOT EXISTS (SELECT 1 FROM room_hours h WHERE h.room=r.id AND h.weekday=?) ");
            args.add(weekday);
        }
        sql.append("))) ");
        if(room!=null) { sql.append(" AND r.id=?"); args.add(room); }
        for(String item:needs.split(",")) if(!item.isEmpty()) {
            sql.append(" AND (',' || r.equipment || ',') LIKE ?"); args.add("%,"+item+",%");
        }
        sql.append(" AND NOT EXISTS (SELECT 1 FROM maintenance m WHERE m.room=r.id AND m.booked_day=? AND m.first_min<? AND m.last_min>?)");
        Collections.addAll(args,w.date,w.end,w.start);
        if(checkSlots) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM slots s WHERE s.room=r.id AND s.booked_day=? AND s.minute_of_day>=? AND s.minute_of_day<?)");
            Collections.addAll(args,w.date,w.start,w.end);
        }
        return rows(c,sql.append(" ORDER BY r.capacity,r.id").toString(),args.toArray());
    }
}
