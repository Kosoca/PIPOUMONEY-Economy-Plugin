package fr.pipoumoney.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.pipoumoney.config.PluginConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public final class Database {

    public enum Dialect { SQLITE, MYSQL }

    private final Dialect dialect;
    private final DataSource dataSource;

    private Database(Dialect dialect, DataSource dataSource) {
        this.dialect = dialect;
        this.dataSource = dataSource;
    }

    public static Database open(File dataFolder, PluginConfig cfg) throws Exception {
        String type = cfg.storage().type().trim().toLowerCase(Locale.ROOT);

        Database db = "mysql".equals(type)
                ? openMysql(cfg)
                : openSqlite(dataFolder, cfg);

        db.initSchema();
        return db;
    }

    private static Database openSqlite(File dataFolder, PluginConfig cfg) {
        File file = new File(dataFolder, cfg.sqlite().file());
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.getAbsolutePath());
        return new Database(Dialect.SQLITE, ds);
    }

    private static Database openMysql(PluginConfig cfg) {
        String params = cfg.mysql().params();
        if (params == null) params = "";
        params = params.trim();
        String q = params.isEmpty() ? "" : (params.startsWith("?") ? params : "?" + params);

        String jdbcUrl = "jdbc:mysql://" + cfg.mysql().host() + ":" + cfg.mysql().port() + "/" + cfg.mysql().database() + q;

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(cfg.mysql().username());
        hc.setPassword(cfg.mysql().password());

        hc.setMaximumPoolSize(cfg.mysql().pool().maximumPoolSize());
        hc.setMinimumIdle(cfg.mysql().pool().minimumIdle());
        hc.setConnectionTimeout(cfg.mysql().pool().connectionTimeoutMs());
        hc.setIdleTimeout(cfg.mysql().pool().idleTimeoutMs());
        hc.setMaxLifetime(cfg.mysql().pool().maxLifetimeMs());

        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        HikariDataSource ds = new HikariDataSource(hc);
        return new Database(Dialect.MYSQL, ds);
    }

    public DataSource dataSource() { return dataSource; }
    public boolean isMysql() { return dialect == Dialect.MYSQL; }
    public Dialect dialect() { return dialect; }

    public boolean isOpen() {
        try (Connection c = dataSource.getConnection()) {
            return c != null && !c.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    private void initSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            if (dialect == Dialect.SQLITE) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      uuid TEXT PRIMARY KEY,
                      name TEXT,
                      balance REAL NOT NULL DEFAULT 0,
                      updated_ms INTEGER NOT NULL DEFAULT 0,
                      notify INTEGER NOT NULL DEFAULT 1,
                      locked INTEGER NOT NULL DEFAULT 0,
                      last_activity_ms INTEGER NOT NULL DEFAULT 0
                    );
                """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS transactions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      at_epoch_ms INTEGER NOT NULL,
                      source TEXT NOT NULL,
                      type TEXT NOT NULL,
                      actor_uuid TEXT,
                      target_uuid TEXT,
                      amount REAL NOT NULL,
                      admin_flagged INTEGER NOT NULL DEFAULT 0,
                      flag_reason TEXT,
                      flagged_by_uuid TEXT,
                      flagged_at_ms INTEGER NOT NULL DEFAULT 0
                    );
                """);

                execIgnore(st, "ALTER TABLE transactions ADD COLUMN admin_flagged INTEGER NOT NULL DEFAULT 0;");
                execIgnore(st, "ALTER TABLE transactions ADD COLUMN flag_reason TEXT;");
                execIgnore(st, "ALTER TABLE transactions ADD COLUMN flagged_by_uuid TEXT;");
                execIgnore(st, "ALTER TABLE transactions ADD COLUMN flagged_at_ms INTEGER NOT NULL DEFAULT 0;");

                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_balance ON accounts(balance);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_activity ON accounts(last_activity_ms);");

                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_at ON transactions(at_epoch_ms);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_actor ON transactions(actor_uuid);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_target ON transactions(target_uuid);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_source_type ON transactions(source, type);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tx_flagged ON transactions(admin_flagged);");
            } else {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      uuid VARCHAR(36) PRIMARY KEY,
                      name VARCHAR(16),
                      balance DOUBLE NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      notify TINYINT NOT NULL DEFAULT 1,
                      locked TINYINT NOT NULL DEFAULT 0,
                      last_activity_ms BIGINT NOT NULL DEFAULT 0
                    );
                """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS transactions (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      at_epoch_ms BIGINT NOT NULL,
                      source VARCHAR(32) NOT NULL,
                      type VARCHAR(64) NOT NULL,
                      actor_uuid VARCHAR(36),
                      target_uuid VARCHAR(36),
                      amount DOUBLE NOT NULL,
                      admin_flagged TINYINT NOT NULL DEFAULT 0,
                      flag_reason VARCHAR(255),
                      flagged_by_uuid VARCHAR(36),
                      flagged_at_ms BIGINT NOT NULL DEFAULT 0
                    );
                """);

                execIgnore(st, "ALTER TABLE transactions ADD COLUMN admin_flagged TINYINT NOT NULL DEFAULT 0;");
                execIgnore(st, "ALTER TABLE transactions ADD COLUMN flag_reason VARCHAR(255);");
                execIgnore(st, "ALTER TABLE transactions ADD COLUMN flagged_by_uuid VARCHAR(36);");
                execIgnore(st, "ALTER TABLE transactions ADD COLUMN flagged_at_ms BIGINT NOT NULL DEFAULT 0;");

                execIgnore(st, "CREATE INDEX idx_accounts_balance ON accounts(balance);");
                execIgnore(st, "CREATE INDEX idx_accounts_name ON accounts(name);");
                execIgnore(st, "CREATE INDEX idx_accounts_activity ON accounts(last_activity_ms);");

                execIgnore(st, "CREATE INDEX idx_tx_at ON transactions(at_epoch_ms);");
                execIgnore(st, "CREATE INDEX idx_tx_actor ON transactions(actor_uuid);");
                execIgnore(st, "CREATE INDEX idx_tx_target ON transactions(target_uuid);");
                execIgnore(st, "CREATE INDEX idx_tx_source_type ON transactions(source, type);");
                execIgnore(st, "CREATE INDEX idx_tx_flagged ON transactions(admin_flagged);");
            }
        }
    }

    private static void execIgnore(Statement st, String sql) throws SQLException {
        try { st.executeUpdate(sql); }
        catch (SQLException ignored) {}
    }

    public void closeQuietly() {
        if (dataSource instanceof HikariDataSource h) {
            try { h.close(); } catch (Exception ignored) {}
        }
    }
}
