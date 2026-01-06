package fr.pipoumoney.db.repositories;

import fr.pipoumoney.db.Database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

public final class AccountsRepository {

    public record Row(UUID uuid, String name, double balance, boolean notificationsEnabled, boolean locked, long lastActivityMs) {}

    private final DataSource ds;
    private final boolean mysql;

    public AccountsRepository(Database db) {
        this.ds = db.dataSource();
        this.mysql = db.isMysql();
    }

    public Map<UUID, Row> loadAll() throws Exception {
        var out = new HashMap<UUID, Row>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, name, balance, notify, locked, last_activity_ms FROM accounts"
             )) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    double bal = rs.getDouble("balance");
                    boolean notificationsEnabled = rs.getInt("notify") != 0;
                    boolean locked = rs.getInt("locked") != 0;
                    long last = rs.getLong("last_activity_ms");
                    out.put(uuid, new Row(uuid, name, bal, notificationsEnabled, locked, last));
                }
            }
        }
        return out;
    }

    public void upsertBatch(
            List<UUID> uuids,
            java.util.function.Function<UUID, String> nameResolver,
            java.util.function.Function<UUID, Double> balanceResolver,
            java.util.function.Function<UUID, Boolean> notificationsEnabledResolver,
            java.util.function.Function<UUID, Boolean> lockedResolver,
            java.util.function.Function<UUID, Long> lastActivityResolver
    ) throws Exception {

        if (uuids == null || uuids.isEmpty()) return;

        long now = Instant.now().toEpochMilli();

        String sqlMysql = """
            INSERT INTO accounts(uuid, name, balance, updated_ms, notify, locked, last_activity_ms)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              name=VALUES(name),
              balance=VALUES(balance),
              updated_ms=VALUES(updated_ms),
              notify=VALUES(notify),
              locked=VALUES(locked),
              last_activity_ms=VALUES(last_activity_ms)
        """;

        String sqlSqlite = """
            INSERT INTO accounts(uuid, name, balance, updated_ms, notify, locked, last_activity_ms)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
              name=excluded.name,
              balance=excluded.balance,
              updated_ms=excluded.updated_ms,
              notify=excluded.notify,
              locked=excluded.locked,
              last_activity_ms=excluded.last_activity_ms
        """;

        String sql = mysql ? sqlMysql : sqlSqlite;

        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (UUID uuid : uuids) {
                ps.setString(1, uuid.toString());
                ps.setString(2, nameResolver.apply(uuid));
                ps.setDouble(3, balanceResolver.apply(uuid));
                ps.setLong(4, now);
                ps.setInt(5, notificationsEnabledResolver.apply(uuid) ? 1 : 0);
                ps.setInt(6, lockedResolver.apply(uuid) ? 1 : 0);
                ps.setLong(7, lastActivityResolver.apply(uuid));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public int countByMin(double min) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM accounts WHERE balance >= ?")) {
            ps.setDouble(1, min);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Row> list(double min, String sort, int limit, int offset) throws Exception {
        String orderBy = "balance DESC, name ASC";
        if ("name".equalsIgnoreCase(sort)) orderBy = "name ASC, balance DESC";

        String sql = "SELECT uuid, name, balance, notify, locked, last_activity_ms FROM accounts WHERE balance >= ? " +
                "ORDER BY " + orderBy + " LIMIT ? OFFSET ?";

        var rows = new ArrayList<Row>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, min);
            ps.setInt(2, limit);
            ps.setInt(3, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    double bal = rs.getDouble("balance");
                    boolean notificationsEnabled = rs.getInt("notify") != 0;
                    boolean locked = rs.getInt("locked") != 0;
                    long last = rs.getLong("last_activity_ms");
                    rows.add(new Row(uuid, name, bal, notificationsEnabled, locked, last));
                }
            }
        }
        return rows;
    }

    public List<Row> top(double min, int limit) throws Exception {
        String sql = "SELECT uuid, name, balance, notify, locked, last_activity_ms FROM accounts WHERE balance >= ? " +
                "ORDER BY balance DESC, name ASC LIMIT ?";

        var rows = new ArrayList<Row>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, min);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String name = rs.getString("name");
                    double bal = rs.getDouble("balance");
                    boolean notificationsEnabled = rs.getInt("notify") != 0;
                    boolean locked = rs.getInt("locked") != 0;
                    long last = rs.getLong("last_activity_ms");
                    rows.add(new Row(uuid, name, bal, notificationsEnabled, locked, last));
                }
            }
        }
        return rows;
    }

    public int rankOf(UUID uuid) throws Exception {
        double bal;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                bal = rs.next() ? rs.getDouble(1) : 0.0;
            }
        }

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM accounts WHERE balance > ?")) {
            ps.setDouble(1, bal);
            try (ResultSet rs = ps.executeQuery()) {
                int higher = rs.next() ? rs.getInt(1) : 0;
                return higher + 1;
            }
        }
    }
}
