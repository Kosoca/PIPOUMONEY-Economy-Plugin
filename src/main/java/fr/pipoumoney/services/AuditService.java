package fr.pipoumoney.services;

import fr.pipoumoney.config.PluginConfig;
import fr.pipoumoney.db.repositories.AuditRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

public final class AuditService {

    public record FlagInfo(boolean flag, String reason, UUID flaggedBy) {}

    private final AuditRepository repo;
    private final Consumer<Runnable> async;
    private volatile boolean enabled;

    public AuditService(AuditRepository repo, Consumer<Runnable> async, boolean enabled) {
        this.repo = repo;
        this.async = async;
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    public void logAsync(String source, String type, UUID actor, UUID target, double amount) {
        logAsync(source, type, actor, target, amount, null);
    }

    public void logAsync(String source, String type, UUID actor, UUID target, double amount, FlagInfo flag) {
        if (!enabled) return;
        async.accept(() -> {
            try {
                long id = repo.insert(Instant.now(), source, type, actor, target, amount);
                if (flag != null && flag.flag() && id > 0) {
                    repo.flag(id, flag.flaggedBy(), flag.reason());
                }
            } catch (Exception ignored) {}
        });
    }

    public void purgeOnStartAsync(PluginConfig.Audit cfg) {
        if (!enabled || !cfg.purgeOnStart()) return;
        async.accept(() -> {
            try { repo.purgeOlderThanDays(cfg.purgeOlderThanDays()); }
            catch (Exception ignored) {}
        });
    }
}
