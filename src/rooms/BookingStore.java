package rooms;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Local office hours; minutes are measured from midnight. */
public final class BookingStore {
    private final String url;
    public BookingStore(String url) throws SQLException {
        this.url = url;
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (ResultSet r = c.getMetaData().getTables(null, "APP", "ROOMS", null)) {
                if (r.next()) { c.commit(); return; }
            }
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE rooms (id INT PRIMARY KEY, label VARCHAR(40) NOT NULL, capacity INT NOT NULL)");
                s.executeUpdate("INSERT INTO rooms VALUES (1,'Cedar',4),(2,'Maple',8),(3,'Oak',12)");
                s.executeUpdate("CREATE TABLE bookings (id VARCHAR(36) PRIMARY KEY, secret VARCHAR(36) NOT NULL, room INT NOT NULL REFERENCES rooms(id), booked_day DATE NOT NULL, first_min INT NOT NULL, last_min INT NOT NULL, holder VARCHAR(40) NOT NULL)");
                s.executeUpdate("CREATE INDEX agenda ON bookings(booked_day,room,first_min)");
                s.executeUpdate("CREATE TABLE slots (room INT NOT NULL, booked_day DATE NOT NULL, minute_of_day INT NOT NULL, booking VARCHAR(36) NOT NULL REFERENCES bookings(id) ON DELETE CASCADE, PRIMARY KEY(room,booked_day,minute_of_day))");
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
    private Connection open() throws SQLException { return DriverManager.getConnection(url); }
    public static final class Conflict extends Exception {}
    public static final class Ticket {
        public final String id, cancelKey;
        Ticket(String id, String key) { this.id=id; this.cancelKey=key; }
    }
    public Ticket book(int room, LocalDate day, int from, int to, String holder) throws SQLException, Conflict {
        if (day == null || from < 540 || to > 1080 || from >= to || from%30 != 0 || to%30 != 0)
            throw new IllegalArgumentException("Use half-hour slots between 09:00 and 18:00.");
        if (holder == null || holder.trim().isEmpty() || holder.length()>40 || holder.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("holder must have 1 to 40 printable characters.");
        Ticket ticket = new Ticket(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement b = c.prepareStatement("INSERT INTO bookings VALUES (?,?,?,?,?,?,?)");
                 PreparedStatement s = c.prepareStatement("INSERT INTO slots VALUES (?,?,?,?)")) {
                b.setString(1,ticket.id); b.setString(2,ticket.cancelKey); b.setInt(3,room);
                b.setDate(4,java.sql.Date.valueOf(day)); b.setInt(5,from); b.setInt(6,to); b.setString(7,holder.trim());
                try { b.executeUpdate(); } catch (SQLException e) {
                    if ("23503".equals(e.getSQLState())) throw new IllegalArgumentException("Unknown room.");
                    throw e;
                }
                for (int m=from; m<to; m+=30) {
                    s.setInt(1,room); s.setDate(2,java.sql.Date.valueOf(day)); s.setInt(3,m); s.setString(4,ticket.id);
                    try { s.executeUpdate(); } catch (SQLException e) {
                        if ("23505".equals(e.getSQLState())) throw new Conflict();
                        throw e;
                    }
                }
                c.commit(); return ticket;
            } catch (SQLException | RuntimeException | Conflict e) { c.rollback(); throw e; }
        }
    }
    public boolean cancel(String id, String key) throws SQLException {
        try (Connection c=open(); PreparedStatement p=c.prepareStatement("DELETE FROM bookings WHERE id=? AND secret=?")) {
            p.setString(1,id); p.setString(2,key); return p.executeUpdate()==1;
        }
    }
    public List<String[]> rooms() throws SQLException {
        try (Connection c=open(); PreparedStatement p=c.prepareStatement("SELECT id,label,capacity FROM rooms ORDER BY id")) {
            return rows(p,3);
        }
    }
    public List<String[]> agenda(LocalDate day) throws SQLException {
        try (Connection c=open(); PreparedStatement p=c.prepareStatement("SELECT id,room,first_min,last_min,holder FROM bookings WHERE booked_day=? ORDER BY room,first_min")) {
            p.setDate(1,java.sql.Date.valueOf(day)); List<String[]> out=rows(p,5);
            for (String[] row:out) for (int i=2;i<=3;i++) {
                int m=Integer.parseInt(row[i]); row[i]=String.format(Locale.ROOT,"%02d:%02d",m/60,m%60);
            }
            return out;
        }
    }
    private static List<String[]> rows(PreparedStatement p,int n) throws SQLException {
        List<String[]> out=new ArrayList<>();
        try(ResultSet r=p.executeQuery()) {
            while(r.next()) {String[] row=new String[n]; for(int i=0;i<n;i++) row[i]=r.getString(i+1); out.add(row);}
        }
        return out;
    }
}
