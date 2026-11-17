package rooms;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.TimeUnit;

final class Delivery implements Runnable {
    private final BookingStore db;
    Delivery(BookingStore db) { this.db=db; }
    public void run() {
        try {
            for(String[] row:db.query("SELECT n.id,a.email,n.kind,n.body,n.attempts FROM notices n JOIN accounts a ON a.username=n.recipient WHERE n.state='QUEUED' ORDER BY n.created FETCH FIRST 20 ROWS ONLY")) {
                String state;
                int attempts=Integer.parseInt(row[4])+1;
                if(row[1]==null) state="INBOX_ONLY";
                else {
                    try { send(row); state="SENT"; }
                    catch(Exception e) { state=attempts>=4?"DEAD":"QUEUED"; }
                }
                final String result=state;
                db.transaction(c -> Database.update(c,"UPDATE notices SET state=?,attempts=? WHERE id=? AND state='QUEUED' AND attempts=?",
                    result,attempts,row[0],attempts-1));
            }
        } catch(SQLException e) { System.err.println("Email worker database error "+e.getSQLState()); }
    }
    private void send(String[] row) throws Exception {
        ProcessBuilder builder=new ProcessBuilder("python","mail_delivery.py");
        Process process=builder.start();
        try {
            try(Writer writer=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8)) {
                writer.write("{\"id\":"+Server.q(row[0])+",\"to\":"+Server.q(row[1])+",\"subject\":"+Server.q(row[2])+",\"body\":"+Server.q(row[3])+"}");
            }
            if(!process.waitFor(15,TimeUnit.SECONDS) || process.exitValue()!=0) throw new IOException("Delivery failed");
        } finally { process.destroyForcibly(); }
    }
}
