package fr.pipoumoney.db.repositories;

import fr.pipoumoney.db.Database;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class AuditRepository {

    public record Tx(
            long id,
            Instant at,
            String source,
            String type,
            UUID actor,
            UUID target,
            double amount,
            boolean adminFlagged,
            String flagReason,
            UUID flaggedBy,
            long flaggedAtMs
    ) {}

    public record Query(
            UUID player,
            String source,
            String type,
            Integer days,
            Double minAmount,
            Boolean flagged,
            int page,
            int perPage,
            int limitCap
    ) {}

    public record Page(List<Tx> rows, int page, int pages, int total) {}

    private final DataSource ds;
    private final boolean mysql;

    public AuditRepository(Database db) {
        this.ds = db.dataSource();
        this.mysql = db.isMysql();
    }

    public long insert(Instant at, String source, String type, UUID actor, UUID target, double amount) throws Exception {
        String sql = """
            INSERT INTO transactions(at_epoch_ms, source, type, actor_uuid, target_uuid, amount, admin_flagged, flag_reason, flagged_by_uuid, flagged_at_ms)
            VALUES(?, ?, ?, ?, ?, ?, 0, NULL, NULL, 0)
        """;

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, at.toEpochMilli());
            ps.setString(2, source);
            ps.setString(3, type);
            ps.setString(4, actor != null ? actor.toString() : null);
            ps.setString(5, target != null ? target.toString() : null);
            ps.setDouble(6, amount);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys != null && keys.next()) return keys.getLong(1);
            }

            if (!mysql) {
                try (PreparedStatement lastId = c.prepareStatement("SELECT last_insert_rowid()");
                     ResultSet rs = lastId.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }

            return -1L;
        }
    }

    public void flag(long txId, UUID flaggedBy, String reason) throws Exception {
        long now = System.currentTimeMillis();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE transactions SET admin_flagged = 1, flag_reason = ?, flagged_by_uuid = ?, flagged_at_ms = ? WHERE id = ?"
             )) {
            ps.setString(1, reason);
            ps.setString(2, flaggedBy != null ? flaggedBy.toString() : null);
            ps.setLong(3, now);
            ps.setLong(4, txId);
            ps.executeUpdate();
        }
    }

    public void unflag(long txId, UUID flaggedBy) throws Exception {
        long now = System.currentTimeMillis();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE transactions SET admin_flagged = 0, flag_reason = NULL, flagged_by_uuid = ?, flagged_at_ms = ? WHERE id = ?"
             )) {
            ps.setString(1, flaggedBy != null ? flaggedBy.toString() : null);
            ps.setLong(2, now);
            ps.setLong(3, txId);
            ps.executeUpdate();
        }
    }

    public Optional<Tx> getById(long id) throws Exception {
        String sql = """
            SELECT id, at_epoch_ms, source, type, actor_uuid, target_uuid, amount,
                   admin_flagged, flag_reason, flagged_by_uuid, flagged_at_ms
            FROM transactions WHERE id = ?
        """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readTx(rs));
            }
        }
    }

    public List<Long> recentIds(int limit) throws Exception {
        int lim = Math.max(1, Math.min(200, limit));
        String sql = "SELECT id FROM transactions ORDER BY at_epoch_ms DESC LIMIT ?";
        var out = new ArrayList<Long>(lim);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        }

        return out;
    }

    public int purgeOlderThanDays(int days) throws Exception {
        long cutoff = Instant.now().minusSeconds(days * 86400L).toEpochMilli();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM transactions WHERE at_epoch_ms < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    public Page query(Query q) throws Exception {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (q.player != null) {
            where.append(" AND (actor_uuid = ? OR target_uuid = ?) ");
            params.add(q.player.toString());
            params.add(q.player.toString());
        }
        if (q.source != null) {
            where.append(" AND source = ? ");
            params.add(q.source.toUpperCase(Locale.ROOT));
        }
        if (q.type != null) {
            where.append(" AND type = ? ");
            params.add(q.type.toUpperCase(Locale.ROOT));
        }
        if (q.days != null) {
            long cutoff = Instant.now().minusSeconds(q.days * 86400L).toEpochMilli();
            where.append(" AND at_epoch_ms >= ? ");
            params.add(cutoff);
        }
        if (q.minAmount != null) {
            where.append(" AND amount >= ? ");
            params.add(q.minAmount);
        }
        if (q.flagged != null) {
            where.append(" AND admin_flagged = ? ");
            params.add(q.flagged ? 1 : 0);
        }

        int total;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = prepare(c, "SELECT COUNT(*) FROM transactions" + where, params);
             ResultSet rs = ps.executeQuery()) {
            total = rs.next() ? rs.getInt(1) : 0;
        }

        int perPage = Math.max(1, q.perPage);
        int pages = Math.max(1, (int) Math.ceil(total / (double) perPage));
        int page = Math.max(1, Math.min(pages, q.page));
        int offset = (page - 1) * perPage;

        int cap = Math.max(1, q.limitCap);
        int safePerPage = Math.min(perPage, cap);

        String sql = """
            SELECT id, at_epoch_ms, source, type, actor_uuid, target_uuid, amount,
                   admin_flagged, flag_reason, flagged_by_uuid, flagged_at_ms
            FROM transactions
        """ + where + " ORDER BY at_epoch_ms DESC LIMIT ? OFFSET ?";

        List<Object> listParams = new ArrayList<>(params);
        listParams.add(safePerPage);
        listParams.add(offset);

        var rows = new ArrayList<Tx>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = prepare(c, sql, listParams);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(readTx(rs));
        }

        return new Page(rows, page, pages, total);
    }

    private Tx readTx(ResultSet rs) throws Exception {
        long id = rs.getLong("id");
        Instant at = Instant.ofEpochMilli(rs.getLong("at_epoch_ms"));
        String source = rs.getString("source");
        String type = rs.getString("type");
        UUID actor = parseUuid(rs.getString("actor_uuid"));
        UUID target = parseUuid(rs.getString("target_uuid"));
        double amount = rs.getDouble("amount");
        boolean flagged = rs.getInt("admin_flagged") != 0;
        String reason = rs.getString("flag_reason");
        UUID flaggedBy = parseUuid(rs.getString("flagged_by_uuid"));
        long flaggedAt = rs.getLong("flagged_at_ms");
        return new Tx(id, at, source, type, actor, target, amount, flagged, reason, flaggedBy, flaggedAt);
    }

    private PreparedStatement prepare(Connection c, String sql, List<Object> params) throws Exception {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            int idx = i + 1;
            if (v instanceof String s) ps.setString(idx, s);
            else if (v instanceof Integer n) ps.setInt(idx, n);
            else if (v instanceof Long n) ps.setLong(idx, n);
            else if (v instanceof Double d) ps.setDouble(idx, d);
            else ps.setObject(idx, v);
        }
        return ps;
    }

    private UUID parseUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
