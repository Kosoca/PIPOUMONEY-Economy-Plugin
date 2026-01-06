package fr.pipoumoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.UUID;

public record PluginConfig(
        Storage storage,
        Sqlite sqlite,
        Mysql mysql,
        Format format,
        Currency currency,
        TopCache topCache,
        boolean autosaveEnabled,
        int autosaveMinutes,
        int flushDirtyThreshold,
        boolean listenerUpdateNameOnJoin,
        boolean listenerFlushOnQuit,
        Pay pay,
        Player player,
        Balances balances,
        Top top,
        Audit audit,
        Health health,
        AntiAbuse antiAbuse
) {
    public record Storage(String type) {}
    public record Sqlite(String file) {}

    public record Mysql(
            String host,
            int port,
            String database,
            String username,
            String password,
            String params,
            Pool pool
    ) {
        public record Pool(
                int maximumPoolSize,
                int minimumIdle,
                long connectionTimeoutMs,
                long idleTimeoutMs,
                long maxLifetimeMs
        ) {}
    }

    public record Format(int decimals, Locale locale) {}
    public record Currency(String symbol, String singular, String plural, String format) {}
    public record TopCache(boolean enabled, int refreshMinutes, int size) {}

    public record Pay(
            boolean enabled,
            double min,
            double taxPercent,
            int cooldownSeconds,
            boolean allowPaySelf,
            String taxMode,
            UUID treasuryUuid,
            double confirmAbove,
            int confirmTimeoutSeconds,
            int requestTimeoutSeconds
    ) {}

    public record Player(int historyDaysLimit, int historyMaxResults) {}
    public record Balances(boolean showUuid, boolean onlyOnline, int perPage, String sort, double min) {}
    public record Top(int def, int max) {}
    public record Audit(boolean enabled, int perPage, boolean purgeOnStart, int purgeOlderThanDays) {}
    public record Health(int tpsSample) {}

    public record AntiAbuse(
            boolean enabled,
            boolean alertAdmins,
            boolean blockOnTrigger,
            boolean autoFlag,
            int maxTransactionsPerMinute,
            int windowSeconds,
            double windowMaxAmount,
            double dailyMaxAmount,
            double singleTxMaxAmount
    ) {}

    public static PluginConfig load(FileConfiguration c) {
        String storageType = c.getString("storage.type", "sqlite").trim().toLowerCase(Locale.ROOT);

        var sqlite = new Sqlite(c.getString("storage.sqlite.file", "pipoumoney.db"));

        var mysqlPool = new Mysql.Pool(
                Math.max(1, c.getInt("storage.mysql.pool.maximum-pool-size", 10)),
                Math.max(0, c.getInt("storage.mysql.pool.minimum-idle", 2)),
                Math.max(1000L, c.getLong("storage.mysql.pool.connection-timeout-ms", 10000L)),
                Math.max(1000L, c.getLong("storage.mysql.pool.idle-timeout-ms", 600000L)),
                Math.max(1000L, c.getLong("storage.mysql.pool.max-lifetime-ms", 1800000L))
        );

        var mysql = new Mysql(
                c.getString("storage.mysql.host", "127.0.0.1"),
                Math.max(1, c.getInt("storage.mysql.port", 3306)),
                c.getString("storage.mysql.database", "pipoumoney"),
                c.getString("storage.mysql.username", "root"),
                c.getString("storage.mysql.password", "password"),
                c.getString("storage.mysql.params", "useUnicode=true&characterEncoding=utf8&useSSL=false"),
                mysqlPool
        );

        int decimals = clamp(c.getInt("format.decimals", 2), 0, 8);
        Locale locale = parseLocale(c.getString("format.locale", "en_US"));
        var format = new Format(decimals, locale);

        var currency = new Currency(
                c.getString("currency.symbol", "⛃"),
                c.getString("currency.singular", "pipou"),
                c.getString("currency.plural", "pipous"),
                c.getString("currency.format", "{amount} {plural}")
        );

        var topCache = new TopCache(
                c.getBoolean("top-cache.enabled", true),
                Math.max(1, c.getInt("top-cache.refresh-minutes", 3)),
                Math.max(1, c.getInt("top-cache.size", 50))
        );

        boolean autosaveEnabled = c.getBoolean("autosave.enabled", true);
        int autosaveMinutes = Math.max(1, c.getInt("autosave.interval-minutes", 5));
        int flushDirtyThreshold = Math.max(1, c.getInt("flush.dirty-threshold", 50));

        boolean updNameJoin = c.getBoolean("listeners.update-name-on-join", true);
        boolean flushQuit = c.getBoolean("listeners.flush-on-quit", true);

        var pay = new Pay(
                c.getBoolean("pay.enabled", true),
                c.getDouble("pay.min", 0.01),
                Math.max(0.0, c.getDouble("pay.tax-percent", 0.0)),
                Math.max(0, c.getInt("pay.cooldown-seconds", 30)),
                c.getBoolean("pay.allow-pay-self", false),
                c.getString("pay.tax-mode", "sink"),
                parseUuid(c.getString("pay.treasury-uuid", "00000000-0000-0000-0000-000000000000")),
                Math.max(0.0, c.getDouble("pay.confirm-above", 1000)),
                Math.max(1, c.getInt("pay.confirm-timeout-seconds", 15)),
                Math.max(1, c.getInt("pay.request-timeout-seconds", 30))
        );

        var player = new Player(
                Math.max(1, c.getInt("player.history-days-limit", 30)),
                Math.max(1, c.getInt("player.history-max-results", 20))
        );

        var balances = new Balances(
                c.getBoolean("balances.show-uuid", false),
                c.getBoolean("balances.only-online", false),
                Math.max(1, c.getInt("balances.per-page", 10)),
                c.getString("balances.sort", "BAL_DESC"),
                c.getDouble("balances.min", 0.01)
        );

        var top = new Top(
                Math.max(1, c.getInt("top.default", 10)),
                Math.max(1, c.getInt("top.max", 50))
        );

        var audit = new Audit(
                c.getBoolean("audit.enabled", true),
                Math.max(1, c.getInt("audit.max-results-per-page", 10)),
                c.getBoolean("audit.purge-on-start.enabled", false),
                Math.max(1, c.getInt("audit.purge-on-start.older-than-days", 90))
        );

        var health = new Health(clamp(c.getInt("health.tps-sample", 0), 0, 2));

        var antiAbuse = new AntiAbuse(
                c.getBoolean("anti-abuse.enabled", true),
                c.getBoolean("anti-abuse.alert-admins", true),
                c.getBoolean("anti-abuse.block-on-trigger", false),
                c.getBoolean("anti-abuse.auto-flag", true),
                Math.max(0, c.getInt("anti-abuse.max-transactions-per-minute", 10)),
                Math.max(0, c.getInt("anti-abuse.window-seconds", 60)),
                Math.max(0.0, c.getDouble("anti-abuse.window-max-amount", 5000.0)),
                Math.max(0.0, c.getDouble("anti-abuse.daily-max-amount", 25000.0)),
                Math.max(0.0, c.getDouble("anti-abuse.single-tx-max-amount", 10000.0))
        );

        return new PluginConfig(
                new Storage(storageType),
                sqlite,
                mysql,
                format,
                currency,
                topCache,
                autosaveEnabled,
                autosaveMinutes,
                flushDirtyThreshold,
                updNameJoin,
                flushQuit,
                pay,
                player,
                balances,
                top,
                audit,
                health,
                antiAbuse
        );
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static UUID parseUuid(String raw) {
        try { return UUID.fromString(raw); }
        catch (Exception e) { return UUID.fromString("00000000-0000-0000-0000-000000000000"); }
    }

    private static Locale parseLocale(String raw) {
        try {
            String[] parts = raw.split("[_-]");
            if (parts.length == 1) return Locale.forLanguageTag(parts[0]);
            if (parts.length >= 2) return new Locale(parts[0], parts[1]);
        } catch (Exception ignored) {}
        return Locale.US;
    }
}
