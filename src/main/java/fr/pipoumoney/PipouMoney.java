package fr.pipoumoney;

import fr.pipoumoney.commands.PipouMoneyCommand;
import fr.pipoumoney.commands.PipouMoneyTabCompleter;
import fr.pipoumoney.config.PluginConfig;
import fr.pipoumoney.db.Database;
import fr.pipoumoney.db.repositories.AccountsRepository;
import fr.pipoumoney.db.repositories.AuditRepository;
import fr.pipoumoney.economy.VaultPipouMoneyEconomy;
import fr.pipoumoney.listeners.JoinListener;
import fr.pipoumoney.listeners.QuitListener;
import fr.pipoumoney.placeholders.PipouMoneyExpansion;
import fr.pipoumoney.services.AccountService;
import fr.pipoumoney.services.AntiAbuseService;
import fr.pipoumoney.services.AuditService;
import fr.pipoumoney.services.TopCacheService;
import fr.pipoumoney.text.Messages;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PipouMoney extends JavaPlugin {

    private static final DateTimeFormatter FLUSH_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Europe/Paris"));

    private PluginConfig cfg;
    private Messages messages;

    private Database db;
    private AccountsRepository accountsRepo;
    private AuditRepository auditRepo;

    private AccountService accounts;
    private AuditService audit;
    private AntiAbuseService antiAbuse;

    private Economy vaultProvider;

    private Integer autosaveTaskId;
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    private volatile long lastFlushAtMs = 0L;
    private volatile long lastFlushDurationMs = 0L;

    private TopCacheService topCache;
    private Integer topCacheTaskId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureMessagesFile();

        reloadAll();
        if (!openDbAndWarmup()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        registerListeners();
        registerCommands();

        hookVault();
        hookPlaceholderApi();

        scheduleAutosave();
        scheduleTopCache();

        audit.purgeOnStartAsync(cfg.audit());

        logInfo("Enabled.");
    }

    @Override
    public void onDisable() {
        cancelAutosave();
        cancelTopCache();
        try { if (accounts != null) accounts.flushDirty(); } catch (Exception ignored) {}
        if (vaultProvider != null) getServer().getServicesManager().unregister(Economy.class, vaultProvider);
        if (db != null) db.closeQuietly();
    }

    private void ensureMessagesFile() {
        File msg = new File(getDataFolder(), "messages.yml");
        if (!msg.exists()) {
            getDataFolder().mkdirs();
            saveResource("messages.yml", false);
        }
    }

    public void reloadAll() {
        reloadConfig();
        this.cfg = PluginConfig.load(getConfig());

        File msg = new File(getDataFolder(), "messages.yml");
        this.messages = new Messages(YamlConfiguration.loadConfiguration(msg), cfg);
        if (audit != null) audit.setEnabled(cfg.audit().enabled());
        this.antiAbuse = new AntiAbuseService(cfg.antiAbuse());

        scheduleAutosave();
        scheduleTopCache();
    }

    private boolean openDbAndWarmup() {
        try {
            db = Database.open(getDataFolder(), cfg);

            accountsRepo = new AccountsRepository(db);
            auditRepo = new AuditRepository(db);

            accounts = new AccountService(accountsRepo, cfg.format().decimals());
            accounts.warmup();

            audit = new AuditService(auditRepo, this::runAsync, cfg.audit().enabled());

            topCache = new TopCacheService(accountsRepo, cfg.balances().min(), cfg.topCache().size());
            runAsync(() -> {
                try { topCache.refresh(); }
                catch (Exception e) { logWarn("TopCache refresh failed: " + e.getMessage()); }
            });

            return true;
        } catch (Exception e) {
            logSevere("DB init failed: " + e.getMessage());
            return false;
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        getServer().getPluginManager().registerEvents(new QuitListener(this), this);
    }

    private void registerCommands() {
        PipouMoneyCommand executor = new PipouMoneyCommand(this);
        PipouMoneyTabCompleter tab = new PipouMoneyTabCompleter(this);

        Objects.requireNonNull(getCommand("money"), "Command money missing in plugin.yml").setExecutor(executor);
        Objects.requireNonNull(getCommand("money"), "Command money missing in plugin.yml").setTabCompleter(tab);

        Objects.requireNonNull(getCommand("bal"), "Command bal missing in plugin.yml").setExecutor(executor);
        Objects.requireNonNull(getCommand("bal"), "Command bal missing in plugin.yml").setTabCompleter(tab);

        Objects.requireNonNull(getCommand("pay"), "Command pay missing in plugin.yml").setExecutor(executor);
        Objects.requireNonNull(getCommand("pay"), "Command pay missing in plugin.yml").setTabCompleter(tab);

        Objects.requireNonNull(getCommand("baltop"), "Command baltop missing in plugin.yml").setExecutor(executor);
        Objects.requireNonNull(getCommand("baltop"), "Command baltop missing in plugin.yml").setTabCompleter(tab);
    }

    private void hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            logWarn("Vault absent: economy not exposed.");
            return;
        }

        VaultPipouMoneyEconomy econ = new VaultPipouMoneyEconomy(
                accounts,
                audit,
                this::maybeAutoFlush,
                v -> messages.moneyWithCurrency(v),
                cfg.format().decimals()
        );

        getServer().getServicesManager().register(Economy.class, econ, this, ServicePriority.Highest);
        vaultProvider = econ;

        logInfo("Vault Economy registered: " + econ.getName());
    }

    private void hookPlaceholderApi() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            new PipouMoneyExpansion(this).register();
            logInfo("PlaceholderAPI expansion registered.");
        } catch (Exception e) {
            logWarn("PlaceholderAPI hook failed: " + e.getMessage());
        }
    }

    public void maybeAutoFlush() {
        if (accounts.dirtySize() >= cfg.flushDirtyThreshold()) requestAsyncFlush();
    }

    public void requestAsyncFlush() {
        if (!flushQueued.compareAndSet(false, true)) return;

        runAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                accounts.flushDirty();
                lastFlushAtMs = System.currentTimeMillis();
                lastFlushDurationMs = Math.max(0, lastFlushAtMs - start);
            } catch (Exception e) {
                logWarn("Flush failed: " + e.getMessage());
            } finally {
                flushQueued.set(false);
            }
        });
    }

    public void resetStats() {
        lastFlushAtMs = 0L;
        lastFlushDurationMs = 0L;
    }

    public String formattedLastFlush() {
        if (lastFlushAtMs <= 0) return "never";
        return FLUSH_FMT.format(Instant.ofEpochMilli(lastFlushAtMs));
    }

    private void scheduleAutosave() {
        cancelAutosave();
        if (cfg == null) return;
        if (!cfg.autosaveEnabled()) return;

        long periodTicks = cfg.autosaveMinutes() * 60L * 20L;
        autosaveTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this,
                this::requestAsyncFlush,
                periodTicks,
                periodTicks
        );
    }

    private void cancelAutosave() {
        if (autosaveTaskId != null) {
            Bukkit.getScheduler().cancelTask(autosaveTaskId);
            autosaveTaskId = null;
        }
    }

    private void scheduleTopCache() {
        cancelTopCache();
        if (cfg == null) return;
        if (topCache == null) return;
        if (!cfg.topCache().enabled()) return;

        long periodTicks = cfg.topCache().refreshMinutes() * 60L * 20L;
        topCacheTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this,
                () -> runAsync(() -> {
                    try { topCache.refresh(); }
                    catch (Exception e) { logWarn("TopCache refresh failed: " + e.getMessage()); }
                }),
                periodTicks,
                periodTicks
        );
    }

    private void cancelTopCache() {
        if (topCacheTaskId != null) {
            Bukkit.getScheduler().cancelTask(topCacheTaskId);
            topCacheTaskId = null;
        }
    }

    public void runAsync(Runnable r) {
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> r.run());
        } catch (NoSuchMethodError ignored) {
            Bukkit.getScheduler().runTaskAsynchronously(this, r);
        }
    }

    private void sendConsole(String msg) {
        CommandSender cs = Bukkit.getConsoleSender();
        cs.sendMessage(msg);
    }

    public void logInfo(String msg) { sendConsole(ChatColor.GOLD + "" + ChatColor.BOLD + "[PipouMoney] " + ChatColor.YELLOW + msg); }
    public void logWarn(String msg) { sendConsole(ChatColor.GOLD + "" + ChatColor.BOLD + "[PipouMoney] " + ChatColor.RED + msg); }
    public void logSevere(String msg) { sendConsole(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[PipouMoney] " + ChatColor.RED + msg); }

    public PluginConfig cfg() { return cfg; }
    public Messages messages() { return messages; }
    public AccountService accounts() { return accounts; }
    public AuditService audit() { return audit; }
    public AuditRepository auditRepo() { return auditRepo; }
    public Database db() { return db; }
    public TopCacheService topCache() { return topCache; }
    public AntiAbuseService antiAbuse() { return antiAbuse; }

    public boolean isFlushQueued() { return flushQueued.get(); }
    public long lastFlushDurationMs() { return lastFlushDurationMs; }
    public boolean autosaveEnabled() { return cfg.autosaveEnabled(); }
    public int autosaveMinutes() { return cfg.autosaveMinutes(); }
}
