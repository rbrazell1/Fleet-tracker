package com.brazell.fleet.agent;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class LocalBuffer {

    private final Connection conn;

    public LocalBuffer(String dbPath) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                        CREATE TABLE IF NOT EXISTS events (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        device_uptime_ms INTEGER NOT NULL,
                        received_at_epoch_ms INTEGER NOT NULL,
                        payload_json TEXT NOT NULL,
                        sent INTEGER NOT NULL DEFAULT 0
                        )
                    """);
        }
    }

    public synchronized long enqueue(String type, long deviceUptimeMs, long receivedAtEpochMs, String payloadJson)
            throws SQLException {
        String sql = "INSERT INTO events (type, device_uptime_ms, received_at_epoch_ms, payload_json, sent) VALUES (?, ?, ?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setLong(2, deviceUptimeMs);
            ps.setLong(3, receivedAtEpochMs);
            ps.setString(4, payloadJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public synchronized List<TelemetryEvent> unsent(int limit) throws SQLException {
        List<TelemetryEvent> out = new ArrayList<>();
        String sql = "SELECT seq, type, device_uptime_ms, received_at_epoch_ms, payload_json FROM events WHERE sent = 0 ORDER BY seq ASC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TelemetryEvent(
                        rs.getLong("seq"), 
                        rs.getString("type"),
                        rs.getLong("device_uptime_ms"),
                        rs.getLong("received_at_epoch_ms"),
                        rs.getString("payload_json")));
                }
            }
        }
        return out;
    }

    public synchronized void markSent(long seq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE events SET sent = 1 WHERE seq = ?")) {
            ps.setLong(1, seq);
            ps.executeUpdate();
        }
    }

    public synchronized int evictOldestIfOverCapacity(int maxRows) throws SQLException {
        int unsentCount;
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events WHERE sent = 0")) {
            rs.next();
            unsentCount = rs.getInt(1);
        }
        if (unsentCount <= maxRows)
            return 0;

        int toDrop = unsentCount - maxRows;
        String sql = "DELETE FROM events WHERE seq IN (SELECT seq FROM events WHERE sent = 0 ORDER BY seq ASC LIMIT ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, toDrop);
            ps.executeUpdate();
        }
        return toDrop;
    }
}
