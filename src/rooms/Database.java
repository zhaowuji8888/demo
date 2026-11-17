package rooms;

import java.sql.*;
import java.util.*;

class Database {
    final String url;
    Database(String url) { this.url = url; }
    javax.sql.DataSource source;
    Connection open() throws SQLException { return source==null?DriverManager.getConnection(url):source.getConnection(); }
    static boolean mysql(Connection c) throws SQLException { return "MySQL".equals(c.getMetaData().getDatabaseProductName()); }
    static String dialect(String sql) {
        return sql.replace("(',' || r.equipment || ',')","CONCAT(',',r.equipment,',')")
            .replaceAll("OFFSET ([0-9]+) ROWS FETCH NEXT ([0-9]+) ROWS ONLY","LIMIT $2 OFFSET $1")
            .replaceAll("FETCH FIRST ([0-9]+) ROWS ONLY","LIMIT $1").replace("FETCH FIRST ROW ONLY","LIMIT 1");
    }

    static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        if(mysql(c)) sql=dialect(sql);
        java.util.logging.Logger.getLogger("rooms.sql").fine(sql);
        PreparedStatement p = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            return p;
        } catch (SQLException e) { p.close(); throw e; }
    }

    static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = prepare(c, sql, args)) { return p.executeUpdate(); }
    }

    static List<String[]> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = prepare(c, sql, args); ResultSet r = p.executeQuery()) {
            List<String[]> result = new ArrayList<>();
            int count = r.getMetaData().getColumnCount();
            while (r.next()) {
                String[] row = new String[count];
                for (int i = 0; i < count; i++) row[i] = r.getString(i + 1);
                result.add(row);
            }
            return result;
        }
    }

    List<String[]> query(String sql, Object... args) throws SQLException {
        try (Connection c = open()) { return rows(c, sql, args); }
    }

    interface Work<T> { T run(Connection c) throws SQLException; }
    <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    static boolean hasTable(Connection c, String table) throws SQLException {
        try (ResultSet r = c.getMetaData().getTables(null, "APP", table, null)) { return r.next(); }
    }

    void migrate() throws SQLException {
        transaction(c -> {
            if (!hasTable(c, "ROOMS")) {
                update(c,"CREATE TABLE rooms (id INT PRIMARY KEY, label VARCHAR(40) NOT NULL, capacity INT NOT NULL)");
                update(c,"INSERT INTO rooms VALUES (1,'Cedar',4),(2,'Maple',8),(3,'Oak',12)");
                update(c,"CREATE TABLE bookings (id VARCHAR(36) PRIMARY KEY, secret VARCHAR(36) NOT NULL, room INT NOT NULL REFERENCES rooms(id), booked_day DATE NOT NULL, first_min INT NOT NULL, last_min INT NOT NULL, holder VARCHAR(40) NOT NULL)");
                update(c,"CREATE INDEX agenda ON bookings(booked_day,room,first_min)");
                update(c,"CREATE TABLE slots (room INT NOT NULL, booked_day DATE NOT NULL, minute_of_day INT NOT NULL, booking VARCHAR(36) NOT NULL REFERENCES bookings(id) ON DELETE CASCADE, PRIMARY KEY(room,booked_day,minute_of_day))");
            }
            if (!hasTable(c, "ACCOUNTS")) {
                update(c,"CREATE TABLE accounts (username VARCHAR(40) PRIMARY KEY, salt VARCHAR(64) NOT NULL, verifier VARCHAR(128) NOT NULL, is_admin INT NOT NULL)");
                update(c,"ALTER TABLE bookings ADD COLUMN owner_name VARCHAR(40) REFERENCES accounts(username)");
                update(c,"ALTER TABLE bookings ADD COLUMN needs VARCHAR(120) DEFAULT '' NOT NULL");
                update(c,"ALTER TABLE rooms ADD COLUMN enabled INT DEFAULT 1 NOT NULL");
                update(c,"ALTER TABLE rooms ADD COLUMN equipment VARCHAR(120) DEFAULT '' NOT NULL");
                update(c,"UPDATE rooms SET equipment='whiteboard' WHERE id=1");
                update(c,"UPDATE rooms SET equipment='projector,whiteboard' WHERE id=2");
                update(c,"UPDATE rooms SET equipment='camera,projector,whiteboard' WHERE id=3");
                update(c,"CREATE TABLE maintenance (id VARCHAR(36) PRIMARY KEY, room INT REFERENCES rooms(id), booked_day DATE, first_min INT, last_min INT, reason VARCHAR(200) NOT NULL)");
                update(c,"CREATE INDEX maintenance_day ON maintenance(room,booked_day)");
                update(c,"CREATE TABLE admin_log (seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, at_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, actor VARCHAR(40), operation VARCHAR(32), target VARCHAR(40), reason VARCHAR(200))");
            }
            if (!hasTable(c,"SCHEMA_HISTORY")) {
                update(c,"CREATE TABLE schema_history (version INT PRIMARY KEY, installed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                update(c,"INSERT INTO accounts VALUES ('/legacy','-','-',0)");
                update(c,"UPDATE bookings SET owner_name='/legacy' WHERE owner_name IS NULL");
                update(c,"ALTER TABLE bookings DROP COLUMN secret RESTRICT");
                update(c,"ALTER TABLE bookings ADD COLUMN attendees INT DEFAULT 0 NOT NULL");
                update(c,"ALTER TABLE bookings ADD COLUMN status VARCHAR(20) DEFAULT 'CONFIRMED' NOT NULL");
                update(c,"ALTER TABLE bookings ADD COLUMN cancelled_at TIMESTAMP");
                update(c,"ALTER TABLE bookings ADD COLUMN cancelled_by VARCHAR(40)");
                update(c,"ALTER TABLE rooms ADD COLUMN version BIGINT DEFAULT 0 NOT NULL");
                update(c,"CREATE INDEX bookings_owner_day ON bookings(owner_name, booked_day)");
                update(c,"CREATE INDEX audit_time ON admin_log(at_time)");
                update(c,"CREATE TABLE login_guard (username VARCHAR(40) PRIMARY KEY, failures INT NOT NULL, locked_until BIGINT NOT NULL)");
                update(c,"INSERT INTO schema_history(version) VALUES (2)");
            }
            if (!hasTable(c,"ROOM_HOURS")) {
                update(c,"ALTER TABLE rooms ADD COLUMN deleted INT DEFAULT 0 NOT NULL");
                update(c,"ALTER TABLE rooms ADD COLUMN location VARCHAR(160) DEFAULT '' NOT NULL");
                update(c,"ALTER TABLE rooms ADD COLUMN description VARCHAR(500) DEFAULT '' NOT NULL");
                update(c,"ALTER TABLE rooms ADD COLUMN image_url VARCHAR(500) DEFAULT '' NOT NULL");
                update(c,"ALTER TABLE rooms ADD COLUMN approval INT DEFAULT 0 NOT NULL");
                update(c,"CREATE SEQUENCE room_ids AS INT START WITH 1000");
                update(c,"CREATE TABLE room_hours(room INT REFERENCES rooms(id), weekday INT, opens INT, closes INT, PRIMARY KEY(room,weekday))");
                update(c,"CREATE TABLE holidays(booked_day DATE PRIMARY KEY)");
                update(c,"CREATE TABLE day_override(room INT REFERENCES rooms(id),booked_day DATE,opens INT,closes INT,PRIMARY KEY(room,booked_day))");
                update(c,"ALTER TABLE bookings ADD COLUMN series_id VARCHAR(36)");
                update(c,"ALTER TABLE bookings ADD COLUMN checked_in_at TIMESTAMP");
                update(c,"ALTER TABLE accounts ADD COLUMN strikes INT DEFAULT 0 NOT NULL");
                update(c,"ALTER TABLE accounts ADD COLUMN suspended_until BIGINT DEFAULT 0 NOT NULL");
                update(c,"ALTER TABLE accounts ADD COLUMN email VARCHAR(254)");
                update(c,"CREATE INDEX booking_status ON bookings(status,booked_day)");
                update(c,"CREATE INDEX booking_series ON bookings(series_id,booked_day)");
                update(c,"CREATE TABLE notices (id VARCHAR(36) PRIMARY KEY,recipient VARCHAR(40),booking VARCHAR(36),kind VARCHAR(32),body VARCHAR(400),state VARCHAR(12),attempts INT DEFAULT 0,created TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                update(c,"ALTER TABLE bookings ADD COLUMN reminded INT DEFAULT 0 NOT NULL");
                update(c,"INSERT INTO schema_history(version) VALUES (3)");
            }
            return null;
        });
    }
}
