package rooms;

import java.sql.*;
import java.util.*;

class Database {
    final String url;
    Database(String url) { this.url = url; }
    Connection open() throws SQLException { return DriverManager.getConnection(url); }

    static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
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
            return null;
        });
    }
}
