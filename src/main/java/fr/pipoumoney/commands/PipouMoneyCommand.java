package fr.pipoumoney.commands;

import fr.pipoumoney.PipouMoney;
import fr.pipoumoney.config.PluginConfig;
import fr.pipoumoney.db.repositories.AuditRepository;
import fr.pipoumoney.services.AccountService;
import fr.pipoumoney.services.AntiAbuseService;
import fr.pipoumoney.services.AuditService;
import fr.pipoumoney.text.Messages;
import fr.pipoumoney.utils.MoneyUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public final class PipouMoneyCommand implements CommandExecutor {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Europe/Paris"));

    private static final String PERM_PREFIX = "pipoumoney.";
    private static final String PERM_ADMIN_ROOT = PERM_PREFIX + "admin";
    private static final String PERM_ADMIN_ALL = PERM_PREFIX + "admin.*";

    private static final String PERM_AUDIT_VIEW = PERM_PREFIX + "admin.audit.view";
    private static final String PERM_AUDIT_FLAG = PERM_PREFIX + "admin.audit.flag";
    private static final String PERM_AUDIT_UNFLAG = PERM_PREFIX + "admin.audit.unflag";
    private static final String PERM_AUDIT_ALERTS = PERM_PREFIX + "admin.audit.alerts";

    private final PipouMoney plugin;

    private final Map<String, BiFunction<Player, Ctx, Boolean>> moneyRoutes;

    private final Map<UUID, Long> payCooldownMs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPay> pendingConfirm = new ConcurrentHashMap<>();

    private record PendingPay(UUID from, UUID to, double amount, long expiresAtMs) {}

    public PipouMoneyCommand(PipouMoney plugin) {
        this.plugin = plugin;

        moneyRoutes = Map.ofEntries(
                Map.entry("help", (p, c) -> { help(p, c); return true; }),
                Map.entry("settings", (p, c) -> { settings(p, c); return true; }),
                Map.entry("pay", (p, c) -> { pay(p, c); return true; }),
                Map.entry("top", (p, c) -> { top(p, c, false); return true; }),
                Map.entry("baltop", (p, c) -> { top(p, c, true); return true; }),
                Map.entry("bal", (p, c) -> { balance(p, c); return true; }),
                Map.entry("balance", (p, c) -> { balance(p, c); return true; }),
                Map.entry("history", (p, c) -> { history(p, c); return true; }),
                Map.entry("version", (p, c) -> { version(p, c); return true; }),
                Map.entry("admin", (p, c) -> { adminRoot(p, c); return true; })
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] argsArr) {
        if (!(sender instanceof Player p)) return true;

        String usedLabel = (label == null) ? "money" : label.toLowerCase(Locale.ROOT);
        List<String> args = List.of(argsArr);

        Ctx c = new Ctx(plugin, usedLabel, args);

        if (usedLabel.equals("bal")) {
            if (!requirePerm(p, c, PERM_PREFIX + "balance")) return true;
            balanceAlias(p, c);
            return true;
        }

        if (usedLabel.equals("pay")) {
            if (!requirePerm(p, c, PERM_PREFIX + "pay")) return true;
            payAlias(p, c);
            return true;
        }

        if (usedLabel.equals("baltop")) {
            if (!requirePerm(p, c, PERM_PREFIX + "top")) return true;
            topAlias(p, c);
            return true;
        }

        if (!requirePerm(p, c, PERM_PREFIX + "use")) return true;

        if (c.args().isEmpty()) {
            double bal = c.accounts().balance(p.getUniqueId());
            p.sendMessage(c.msg().fmt("balance.show", Map.of("amount", c.msg().moneyWithCurrency(bal))));
            return true;
        }

        String sub = c.args().get(0).toLowerCase(Locale.ROOT);
        return moneyRoutes.getOrDefault(sub, (pl, ctx) -> {
            pl.sendMessage(ctx.msg().fmt("generic.unknown", Map.of("label", ctx.label())));
            return true;
        }).apply(p, c);
    }

    private record Ctx(PipouMoney plugin, String label, List<String> args) {
        Messages msg() { return plugin.messages(); }
        PluginConfig cfg() { return plugin.cfg(); }
        AccountService accounts() { return plugin.accounts(); }
    }

    private boolean hasPerm(Player p, String perm) {
        if (p.hasPermission(PERM_ADMIN_ROOT) || p.hasPermission(PERM_ADMIN_ALL)) return true;
        return perm == null || perm.isBlank() || p.hasPermission(perm);
    }

    private boolean requirePerm(Player p, Ctx c, String perm) {
        if (perm == null || perm.isBlank()) return true;
        if (hasPerm(p, perm)) return true;

        String m = c.msg().fmt("generic.no_permission_cmd", Map.of("perm", perm));
        if (m != null && !m.isBlank()) {
            p.sendMessage(m);
            return false;
        }

        p.sendMessage(c.msg().get("generic.no_permission"));
        return false;
    }

    private void help(Player p, Ctx c) {
        var msg = c.msg();

        p.sendMessage(msg.get("help.header"));
        p.sendMessage(msg.get("help.player_header"));
        p.sendMessage(msg.get("help.player_bal_self"));
        p.sendMessage(msg.get("help.player_bal_other"));
        p.sendMessage(msg.get("help.player_pay"));
        p.sendMessage(msg.get("help.player_baltop"));
        p.sendMessage(msg.fmt("help.player_settings", Map.of("label", c.label())));
        p.sendMessage(msg.get("help.player_history_me"));
        p.sendMessage(msg.get("help.player_version"));
        p.sendMessage(msg.get("help.player_help_tip"));

        if (hasPerm(p, PERM_PREFIX + "admin") || hasPerm(p, PERM_PREFIX + "admin.help")) {
            p.sendMessage(" ");
            p.sendMessage(msg.get("help.admin_header"));
            p.sendMessage(msg.fmt("help.admin_give", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_take", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_set", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_history", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_balances", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_top", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_reload", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_save", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_health", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_stats", Map.of("label", c.label())));
            p.sendMessage(msg.fmt("help.admin_purge", Map.of("label", c.label())));
            if (msg.get("help.admin_tx") != null) p.sendMessage(msg.fmt("help.admin_tx", Map.of("label", c.label())));
            if (msg.get("help.admin_flag") != null) p.sendMessage(msg.fmt("help.admin_flag", Map.of("label", c.label())));
            if (msg.get("help.admin_unflag") != null) p.sendMessage(msg.fmt("help.admin_unflag", Map.of("label", c.label())));
        }
    }

    private void balanceAlias(Player p, Ctx c) {
        if (c.args().isEmpty()) {
            double bal = c.accounts().balance(p.getUniqueId());
            p.sendMessage(c.msg().fmt("balance.show", Map.of("amount", c.msg().moneyWithCurrency(bal))));
            return;
        }

        if (!requirePerm(p, c, PERM_PREFIX + "balance.other")) return;

        OfflinePlayer t = Bukkit.getOfflinePlayer(c.args().get(0));
        if (t == null || (!t.hasPlayedBefore() && !t.isOnline())) {
            p.sendMessage(c.msg().get("generic.player_not_found"));
            return;
        }

        double bal = c.accounts().balance(t.getUniqueId());
        p.sendMessage(c.msg().fmt("balance.other", Map.of(
                "player", safeName(t, c.args().get(0)),
                "amount", c.msg().moneyWithCurrency(bal)
        )));
    }

    private void balance(Player p, Ctx c) {
        List<String> a = c.args();
        if (a.size() <= 1) {
            double bal = c.accounts().balance(p.getUniqueId());
            p.sendMessage(c.msg().fmt("balance.show", Map.of("amount", c.msg().moneyWithCurrency(bal))));
            return;
        }

        if (!requirePerm(p, c, PERM_PREFIX + "balance.other")) return;

        OfflinePlayer t = Bukkit.getOfflinePlayer(a.get(1));
        if (t == null || (!t.hasPlayedBefore() && !t.isOnline())) {
            p.sendMessage(c.msg().get("generic.player_not_found"));
            return;
        }

        double bal = c.accounts().balance(t.getUniqueId());
        p.sendMessage(c.msg().fmt("balance.other", Map.of(
                "player", safeName(t, a.get(1)),
                "amount", c.msg().moneyWithCurrency(bal)
        )));
    }

    private void settings(Player p, Ctx c) {
        if (!requirePerm(p, c, PERM_PREFIX + "settings")) return;

        var msg = c.msg();
        List<String> a = c.args();

        if (a.size() == 1) {
            boolean notify = c.accounts().notifyEnabled(p.getUniqueId());
            boolean locked = c.accounts().locked(p.getUniqueId());
            msgSendSettings(p, msg, c.label(), notify, locked);
            return;
        }

        String sub = a.get(1).toLowerCase(Locale.ROOT);

        if (sub.equals("notify")) {
            boolean now = !c.accounts().notifyEnabled(p.getUniqueId());
            c.accounts().setNotify(p.getUniqueId(), now);
            plugin.maybeAutoFlush();
            p.sendMessage(msg.get(now ? "settings.notify_enabled" : "settings.notify_disabled"));

            boolean locked = c.accounts().locked(p.getUniqueId());
            msgSendSettings(p, msg, c.label(), now, locked);
            return;
        }

        if (sub.equals("lock")) {
            boolean now = !c.accounts().locked(p.getUniqueId());
            c.accounts().setLocked(p.getUniqueId(), now);
            plugin.maybeAutoFlush();
            p.sendMessage(msg.get(now ? "settings.lock_enabled" : "settings.lock_disabled"));

            boolean notify = c.accounts().notifyEnabled(p.getUniqueId());
            msgSendSettings(p, msg, c.label(), notify, now);
            return;
        }

        p.sendMessage(msg.fmt("usage.settings", Map.of("label", c.label())));
    }

    private void msgSendSettings(Player p, Messages msg, String label, boolean notify, boolean locked) {
        p.sendMessage(msg.get("settings.header"));
        p.sendMessage(msg.fmt("settings.line_notify", Map.of(
                "label", label,
                "state", notify ? "§aON" : "§cOFF"
        )));
        p.sendMessage(msg.fmt("settings.line_lock", Map.of(
                "label", label,
                "state", locked ? "§aON" : "§cOFF"
        )));
    }

    private void payAlias(Player p, Ctx c) {
        if (!c.args().isEmpty() && c.args().get(0).equalsIgnoreCase("confirm")) {
            payConfirm(p, c);
            return;
        }

        if (c.args().size() < 2) {
            p.sendMessage(c.msg().get("usage.pay"));
            return;
        }

        List<String> newArgs = new ArrayList<>();
        newArgs.add("pay");
        newArgs.addAll(c.args());

        pay(p, new Ctx(plugin, c.label(), List.copyOf(newArgs)));
    }

    private void pay(Player p, Ctx c) {
        if (!requirePerm(p, c, PERM_PREFIX + "pay")) return;

        var cfg = c.cfg().pay();
        var msg = c.msg();
        var args = c.args();

        if (!cfg.enabled()) { p.sendMessage(msg.get("pay.disabled")); return; }

        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("confirm")) {
            payConfirm(p, c);
            return;
        }

        if (args.size() < 3) {
            p.sendMessage(msg.get("usage.pay"));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args.get(1));
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            p.sendMessage(msg.get("generic.player_not_found"));
            return;
        }

        if (!cfg.allowPaySelf() && target.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(msg.get("pay.self"));
            return;
        }

        if (c.accounts().locked(target.getUniqueId())) {
            p.sendMessage(msg.get("pay.locked_target"));
            return;
        }

        int dec = c.cfg().format().decimals();

        Double amountRaw = parseAmount(args.get(2)).orElse(null);
        Double amount = (amountRaw == null) ? null : MoneyUtil.round(amountRaw, dec);

        if (amount == null || amount <= 0) { p.sendMessage(msg.get("economy.invalid_amount")); return; }
        if (amount + 1e-9 < cfg.min()) {
            p.sendMessage(msg.fmt("pay.min", Map.of("min", msg.moneyWithCurrency(cfg.min()))));
            return;
        }

        if (cfg.cooldownSeconds() > 0) {
            long now = System.currentTimeMillis();
            long last = payCooldownMs.getOrDefault(p.getUniqueId(), 0L);
            long leftMs = (last + cfg.cooldownSeconds() * 1000L) - now;
            if (leftMs > 0) {
                long left = (leftMs + 999) / 1000;
                p.sendMessage(msg.fmt("pay.cooldown", Map.of("seconds", String.valueOf(left))));
                return;
            }
            payCooldownMs.put(p.getUniqueId(), now);
        }

        var ar = plugin.antiAbuse().checkPay(p.getUniqueId(), amount);
        if (ar.triggered()) {
            notifyAntiAbuseAdmins(p, c, amount, ar);
            if (ar.block()) {
                p.sendMessage(msg.fmt("antiabuse.blocked", Map.of(
                        "reason", ar.reason() == null ? "UNKNOWN" : ar.reason(),
                        "amount", msg.moneyWithCurrency(amount)
                )));
                return;
            }
        }

        if (cfg.confirmAbove() > 0 && amount >= cfg.confirmAbove()) {
            long exp = System.currentTimeMillis() + cfg.confirmTimeoutSeconds() * 1000L;
            pendingConfirm.put(p.getUniqueId(), new PendingPay(p.getUniqueId(), target.getUniqueId(), amount, exp));
            p.sendMessage(msg.fmt("pay.confirm_required", Map.of("label", c.label())));
            return;
        }

        doTransfer(p, c, target.getUniqueId(), amount, "PAY", "TRANSFER");
    }

    private void payConfirm(Player p, Ctx c) {
        var msg = c.msg();
        PendingPay pp = pendingConfirm.get(p.getUniqueId());
        if (pp == null) {
            p.sendMessage(msg.get("pay.confirm_missing"));
            return;
        }
        if (System.currentTimeMillis() > pp.expiresAtMs()) {
            pendingConfirm.remove(p.getUniqueId());
            p.sendMessage(msg.get("pay.confirm_expired"));
            return;
        }
        pendingConfirm.remove(p.getUniqueId());
        doTransfer(p, c, pp.to(), pp.amount(), "PAY", "TRANSFER");
        p.sendMessage(msg.get("pay.confirm_done"));
    }

    private void notifyAntiAbuseAdmins(Player sender, Ctx c, double amount, AntiAbuseService.Result ar) {
        var cfg = plugin.cfg().antiAbuse();
        if (cfg == null || !cfg.enabled() || !cfg.alertAdmins()) return;

        String reason = (ar.reason() == null) ? "UNKNOWN" : ar.reason();
        String line = c.msg().fmt("antiabuse.alert_admins", Map.of(
                "player", sender.getName(),
                "uuid", sender.getUniqueId().toString(),
                "amount", c.msg().moneyWithCurrency(amount),
                "reason", reason
        ));

        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.hasPermission(PERM_AUDIT_ALERTS)) pl.sendMessage(line);
        }
    }

    private void doTransfer(Player fromPlayer, Ctx c, UUID toUuid, double amount, String auditSource, String auditType) {
        var cfg = c.cfg().pay();
        var msg = c.msg();
        UUID fromUuid = fromPlayer.getUniqueId();

        int dec = c.cfg().format().decimals();

        double safeAmount = MoneyUtil.round(amount, dec);
        if (safeAmount <= 0) { fromPlayer.sendMessage(msg.get("economy.invalid_amount")); return; }

        AntiAbuseService.Result ar = plugin.antiAbuse().checkPay(fromUuid, safeAmount);
        if (ar.triggered()) {
            notifyAntiAbuseAdmins(fromPlayer, c, safeAmount, ar);
            if (ar.block()) {
                fromPlayer.sendMessage(msg.fmt("antiabuse.blocked", Map.of(
                        "reason", ar.reason() == null ? "UNKNOWN" : ar.reason(),
                        "amount", msg.moneyWithCurrency(safeAmount)
                )));
                return;
            }
        }

        if (!c.accounts().has(fromUuid, safeAmount)) {
            fromPlayer.sendMessage(msg.get("economy.not_enough"));
            return;
        }

        double tax = MoneyUtil.round(safeAmount * (cfg.taxPercent() / 100.0), dec);
        double received = MoneyUtil.round(Math.max(0.0, safeAmount - tax), dec);

        if (!c.accounts().remove(fromUuid, safeAmount)) {
            fromPlayer.sendMessage(msg.get("economy.not_enough"));
            return;
        }

        c.accounts().add(toUuid, received);

        if (tax > 1e-9) {
            if ("treasury".equalsIgnoreCase(cfg.taxMode())) {
                c.accounts().add(cfg.treasuryUuid(), tax);
                fromPlayer.sendMessage(msg.fmt("pay.taxed_to_treasury", Map.of("tax", msg.moneyWithCurrency(tax))));
            } else {
                fromPlayer.sendMessage(msg.fmt("pay.taxed_to_sink", Map.of("tax", msg.moneyWithCurrency(tax))));
            }
        }

        AuditService.FlagInfo flag = null;
        if (plugin.cfg().antiAbuse() != null && plugin.cfg().antiAbuse().enabled() && plugin.cfg().antiAbuse().autoFlag()) {
            if (ar.triggered()) {
                String reason = (ar.reason() == null) ? "UNKNOWN" : ar.reason();
                flag = new AuditService.FlagInfo(true, "ANTI_ABUSE:" + reason, fromUuid);
            }
        }

        plugin.audit().logAsync(auditSource.toUpperCase(Locale.ROOT), auditType.toUpperCase(Locale.ROOT), fromUuid, toUuid, safeAmount, flag);
        plugin.maybeAutoFlush();

        OfflinePlayer target = Bukkit.getOfflinePlayer(toUuid);
        fromPlayer.sendMessage(msg.fmt("pay.sent", Map.of(
                "sent", msg.moneyWithCurrency(safeAmount),
                "tax", msg.moneyWithCurrency(tax),
                "player", safeName(target, toUuid.toString())
        )));

        if (target.isOnline() && c.accounts().notifyEnabled(toUuid)) {
            target.getPlayer().sendMessage(msg.fmt("pay.received", Map.of(
                    "received", msg.moneyWithCurrency(received),
                    "player", fromPlayer.getName()
            )));
        }
    }

    private void topAlias(Player p, Ctx c) {
        List<String> newArgs = new ArrayList<>();
        newArgs.add("top");
        newArgs.addAll(c.args());
        top(p, new Ctx(plugin, c.label(), List.copyOf(newArgs)), true);
    }

    private void top(Player p, Ctx c, boolean calledFromAlias) {
        if (!requirePerm(p, c, PERM_PREFIX + "top")) return;

        var msg = c.msg();
        var tcfg = c.cfg().top();
        var bcfg = c.cfg().balances();

        int n = parseInt(c.args().size() >= 2 ? c.args().get(1) : null).orElse(tcfg.def());
        n = Math.max(1, Math.min(tcfg.max(), n));

        if (bcfg.onlyOnline()) {
            List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toList());
            var rows = c.accounts().topOnline(bcfg, online, n);

            p.sendMessage(msg.fmt("top.header", Map.of(
                    "count", String.valueOf(rows.size()),
                    "onlyOnline", String.valueOf(true),
                    "showUuid", String.valueOf(bcfg.showUuid())
            )));

            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                p.sendMessage(msg.fmt("top.line", Map.of(
                        "rank", String.valueOf(i + 1),
                        "player", r.displayName(),
                        "amount", msg.moneyWithCurrency(r.balance())
                )));
            }
            return;
        }

        try {
            var rows = c.accounts().topDb(bcfg, n);

            p.sendMessage(msg.fmt("top.header", Map.of(
                    "count", String.valueOf(rows.size()),
                    "onlyOnline", String.valueOf(false),
                    "showUuid", String.valueOf(bcfg.showUuid())
            )));

            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                p.sendMessage(msg.fmt("top.line", Map.of(
                        "rank", String.valueOf(i + 1),
                        "player", r.displayName(),
                        "amount", msg.moneyWithCurrency(r.balance())
                )));
            }
        } catch (Exception e) {
            p.sendMessage(msg.get("generic.db_error"));
        }
    }

    private void history(Player p, Ctx c) {
        var msg = c.msg();
        var cfg = c.cfg();

        if (!cfg.audit().enabled() || !plugin.audit().isEnabled()) {
            p.sendMessage(msg.get("generic.audit_disabled"));
            return;
        }

        List<String> a = c.args();
        if (a.size() < 2 || !a.get(1).equalsIgnoreCase("me")) {
            p.sendMessage(msg.fmt("usage.history", Map.of("label", c.label())));
            return;
        }

        int page = parseInt(a.size() >= 3 ? a.get(2) : null).orElse(1);
        page = Math.max(1, page);

        AuditRepository.Query q = new AuditRepository.Query(
                p.getUniqueId(),
                null,
                null,
                cfg.player().historyDaysLimit(),
                null,
                null,
                page,
                cfg.audit().perPage(),
                cfg.player().historyMaxResults()
        );

        runHistoryAsync(p, c, q, true);
    }

    private void adminRoot(Player p, Ctx c) {
        if (!requirePerm(p, c, PERM_PREFIX + "admin")) return;

        var msg = c.msg();
        List<String> a = c.args();

        if (a.size() < 2) {
            help(p, c);
            return;
        }

        String sub = a.get(1).toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.reload")) return;
                plugin.reloadAll();
                p.sendMessage(msg.get("admin.reloaded"));
                if (!plugin.cfg().autosaveEnabled()) p.sendMessage(msg.get("admin.autosave_disabled"));
            }
            case "save" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.save")) return;
                try {
                    plugin.accounts().flushDirty();
                    p.sendMessage(msg.get("admin.saved"));
                } catch (Exception e) {
                    p.sendMessage(msg.get("generic.db_error"));
                }
            }
            case "health" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.health")) return;
                health(p, c);
            }
            case "stats" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.stats")) return;
                stats(p, c);
            }
            case "give" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.give")) return;
                adminAdjust(p, c, "GIVE", +1);
            }
            case "take" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.take")) return;
                adminAdjust(p, c, "TAKE", -1);
            }
            case "set" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.set")) return;
                adminSet(p, c);
            }
            case "balances" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.balances")) return;
                balances(p, c);
            }
            case "top" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.top")) return;
                top(p, new Ctx(plugin, c.label(), normalizeArgsForAdminTop(a)), false);
            }
            case "history" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.history")) return;
                historyAdmin(p, c);
            }
            case "tx" -> {
                if (!requirePerm(p, c, PERM_AUDIT_VIEW)) return;
                adminTxShow(p, c);
            }
            case "flag" -> {
                if (!requirePerm(p, c, PERM_AUDIT_FLAG)) return;
                adminTxFlag(p, c);
            }
            case "unflag" -> {
                if (!requirePerm(p, c, PERM_AUDIT_UNFLAG)) return;
                adminTxUnflag(p, c);
            }
            case "purge" -> {
                if (!requirePerm(p, c, PERM_PREFIX + "admin.purge")) return;
                purge(p, c);
            }
            default -> p.sendMessage(msg.fmt("generic.unknown", Map.of("label", c.label())));
        }
    }

    private List<String> normalizeArgsForAdminTop(List<String> args) {
        if (args.size() >= 3) return List.of("top", args.get(2));
        return List.of("top");
    }

    private void adminAdjust(Player p, Ctx c, String type, int sign) {
        var msg = c.msg();
        var args = c.args();

        if (args.size() < 4) {
            String usageKey = type.equals("GIVE") ? "usage.give" : "usage.take";
            p.sendMessage(msg.fmt(usageKey, Map.of("label", c.label())));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args.get(2));
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            p.sendMessage(msg.get("generic.player_not_found"));
            return;
        }

        int dec = c.cfg().format().decimals();
        Double amountRaw = parseAmount(args.get(3)).orElse(null);
        Double amount = (amountRaw == null) ? null : MoneyUtil.round(amountRaw, dec);
        if (amount == null || amount <= 0) { p.sendMessage(msg.get("economy.invalid_amount")); return; }

        if (sign > 0) {
            c.accounts().add(target.getUniqueId(), amount);
        } else {
            if (!c.accounts().remove(target.getUniqueId(), amount)) {
                p.sendMessage(msg.get("economy.not_enough"));
                return;
            }
        }

        plugin.audit().logAsync("COMMAND", type, p.getUniqueId(), target.getUniqueId(), amount);
        plugin.maybeAutoFlush();

        p.sendMessage("§aDone.");
    }

    private void adminSet(Player p, Ctx c) {
        var msg = c.msg();
        var args = c.args();

        if (args.size() < 4) { p.sendMessage(msg.fmt("usage.set", Map.of("label", c.label()))); return; }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args.get(2));
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            p.sendMessage(msg.get("generic.player_not_found"));
            return;
        }

        int dec = c.cfg().format().decimals();
        Double amountRaw = parseAmount(args.get(3)).orElse(null);
        Double amount = (amountRaw == null) ? null : MoneyUtil.round(amountRaw, dec);

        if (amount == null || amount < 0) { p.sendMessage(msg.get("economy.invalid_amount")); return; }

        c.accounts().set(target.getUniqueId(), amount);
        plugin.audit().logAsync("COMMAND", "SET", p.getUniqueId(), target.getUniqueId(), amount);
        plugin.maybeAutoFlush();

        p.sendMessage("§aDone.");
    }

    private void balances(Player p, Ctx c) {
        var cfg = c.cfg().balances();
        var msg = c.msg();

        int page = parseInt(c.args().size() >= 3 ? c.args().get(2) : null).orElse(1);
        page = Math.max(1, page);

        if (cfg.onlyOnline()) {
            List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toList());

            int total = c.accounts().countOnlineBalances(online, cfg.min());
            int pages = Math.max(1, (int) Math.ceil(total / (double) cfg.perPage()));
            int current = Math.min(page, pages);

            var rows = c.accounts().listOnlineBalances(cfg, online, current);

            p.sendMessage(msg.fmt("balances.header", Map.of(
                    "page", String.valueOf(current),
                    "pages", String.valueOf(pages),
                    "min", String.valueOf(cfg.min()),
                    "sort", cfg.sort()
            )));

            rows.forEach(r -> p.sendMessage(msg.fmt("balances.line", Map.of(
                    "player", r.displayName(),
                    "amount", msg.moneyWithCurrency(r.balance())
            ))));

            p.sendMessage(msg.fmt("balances.footer", Map.of("label", c.label())));
            return;
        }

        try {
            int total = c.accounts().countBalancesDb(cfg.min());
            int pages = Math.max(1, (int) Math.ceil(total / (double) cfg.perPage()));
            int current = Math.min(page, pages);

            var rows = c.accounts().listBalancesDb(cfg, current);

            p.sendMessage(msg.fmt("balances.header", Map.of(
                    "page", String.valueOf(current),
                    "pages", String.valueOf(pages),
                    "min", String.valueOf(cfg.min()),
                    "sort", cfg.sort()
            )));

            rows.forEach(r -> p.sendMessage(msg.fmt("balances.line", Map.of(
                    "player", r.displayName(),
                    "amount", msg.moneyWithCurrency(r.balance())
            ))));

            p.sendMessage(msg.fmt("balances.footer", Map.of("label", c.label())));
        } catch (Exception e) {
            p.sendMessage(msg.get("generic.db_error"));
        }
    }

    private void historyAdmin(Player p, Ctx c) {
        var msg = c.msg();
        var cfg = c.cfg();

        if (!cfg.audit().enabled() || !plugin.audit().isEnabled()) {
            p.sendMessage(msg.get("generic.audit_disabled"));
            return;
        }

        List<String> a = c.args();

        if (a.size() < 3) {
            p.sendMessage(msg.fmt("help.admin_history", Map.of("label", c.label())));
            return;
        }

        List<String> tail = a.subList(2, a.size());

        UUID playerUuid = null;
        int page = 1;

        int idx = 0;
        if (idx < tail.size() && !tail.get(idx).startsWith("--")) {
            String maybePlayer = tail.get(idx);

            if (maybePlayer.equals("*") || maybePlayer.equalsIgnoreCase("all")) {
                playerUuid = null;
                idx++;
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(maybePlayer);
                if (op != null && (op.hasPlayedBefore() || op.isOnline())) {
                    playerUuid = op.getUniqueId();
                    idx++;
                }
            }
        }

        if (idx < tail.size() && !tail.get(idx).startsWith("--")) {
            page = parseInt(tail.get(idx)).orElse(1);
            idx++;
        }

        Map<String, String> flags = parseFlags(tail.subList(idx, tail.size()));

        String source = normalizeUpperOrNull(flags.get("source"));
        String type = normalizeUpperOrNull(flags.get("type"));

        Integer days = parseInt(flags.get("days")).orElse(null);
        Double min = parseAmount(flags.get("min")).orElse(null);

        Boolean flagged = null;
        String flaggedRaw = flags.get("flagged");
        if (flaggedRaw != null) {
            if (flaggedRaw.equalsIgnoreCase("true")) flagged = true;
            else if (flaggedRaw.equalsIgnoreCase("false")) flagged = false;
        }

        int useDays = (days == null) ? cfg.player().historyDaysLimit() : days;

        AuditRepository.Query q = new AuditRepository.Query(
                playerUuid,
                source,
                type,
                useDays,
                min,
                flagged,
                Math.max(1, page),
                cfg.audit().perPage(),
                cfg.player().historyMaxResults()
        );

        runHistoryAsync(p, c, q, playerUuid != null && playerUuid.equals(p.getUniqueId()));
    }


    private void runHistoryAsync(Player p, Ctx c, AuditRepository.Query q, boolean self) {
        plugin.runAsync(() -> {
            AuditRepository.Page res;
            try { res = plugin.auditRepo().query(q); }
            catch (Exception e) { res = new AuditRepository.Page(List.of(), q.page(), 1, 0); }

            AuditRepository.Page finalRes = res;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalRes.total() <= 0) {
                    p.sendMessage(c.msg().get("history.empty"));
                    return;
                }

                p.sendMessage(c.msg().fmt(self ? "history.self_header" : "history.header", Map.of(
                        "page", String.valueOf(finalRes.page()),
                        "pages", String.valueOf(finalRes.pages())
                )));

                for (var tx : finalRes.rows()) {
                    p.sendMessage(c.msg().fmt("history.line", Map.of(
                            "id", String.valueOf(tx.id()),
                            "date", DT.format(tx.at()),
                            "source", tx.source(),
                            "type", tx.type(),
                            "actor", playerToken(tx.actor()),
                            "target", playerToken(tx.target()),
                            "amount", c.msg().moneyWithCurrency(tx.amount()),
                            "flag", tx.adminFlagged() ? "§cFLAG" : "§aOK",
                            "reason", tx.flagReason() == null ? "" : tx.flagReason()
                    )));
                }

                p.sendMessage(c.msg().fmt("history.footer", Map.of("label", c.label())));
            });
        });
    }

    private void adminTxShow(Player p, Ctx c) {
        final Player fp = p;
        final Ctx fc = c;

        List<String> a = fc.args();
        if (a.size() < 3) {
            fp.sendMessage("§cUsage: §e/" + fc.label() + " admin tx <id>");
            return;
        }

        long id;
        try { id = Long.parseLong(a.get(2)); } catch (Exception e) { id = -1; }
        if (id <= 0) {
            fp.sendMessage("§cInvalid id.");
            return;
        }

        final long fid = id;

        plugin.runAsync(() -> {
            Optional<AuditRepository.Tx> opt;
            try { opt = plugin.auditRepo().getById(fid); }
            catch (Exception e) { opt = Optional.empty(); }

            final Optional<AuditRepository.Tx> finalOpt = opt;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalOpt.isEmpty()) {
                    fp.sendMessage("§cTransaction not found.");
                    return;
                }
                var tx = finalOpt.get();
                fp.sendMessage("§6=== TX #" + tx.id() + " ===");
                fp.sendMessage("§7At: §f" + DT.format(tx.at()));
                fp.sendMessage("§7Type: §f" + tx.source() + "/" + tx.type());
                fp.sendMessage("§7Actor: §f" + playerToken(tx.actor()));
                fp.sendMessage("§7Target: §f" + playerToken(tx.target()));
                fp.sendMessage("§7Amount: §e" + fc.msg().moneyWithCurrency(tx.amount()));
                fp.sendMessage("§7Flagged: " + (tx.adminFlagged() ? "§cYES" : "§aNO"));
                if (tx.adminFlagged()) {
                    fp.sendMessage("§7Reason: §f" + (tx.flagReason() == null ? "" : tx.flagReason()));
                    fp.sendMessage("§7FlaggedBy: §f" + playerToken(tx.flaggedBy()));
                }
            });
        });
    }


    private void adminTxFlag(Player p, Ctx c) {
        final Player fp = p;
        final Ctx fc = c;

        List<String> a = fc.args();
        if (a.size() < 4) {
            fp.sendMessage("§cUsage: §e/" + fc.label() + " admin flag <id> <reason>");
            return;
        }

        long id;
        try { id = Long.parseLong(a.get(2)); } catch (Exception e) { id = -1; }
        if (id <= 0) {
            fp.sendMessage("§cInvalid id.");
            return;
        }

        String reason = String.join(" ", a.subList(3, a.size())).trim();
        if (reason.isEmpty()) reason = "NO_REASON";

        final long fid = id;
        final String freason = reason;
        final UUID fadmin = fp.getUniqueId();

        plugin.runAsync(() -> {
            try { plugin.auditRepo().flag(fid, fadmin, freason); }
            catch (Exception ignored) {}

            Bukkit.getScheduler().runTask(plugin, () -> fp.sendMessage("§aFlagged TX #" + fid + "."));
        });
    }

    private void adminTxUnflag(Player p, Ctx c) {
        final Player fp = p;
        final Ctx fc = c;

        List<String> a = fc.args();
        if (a.size() < 3) {
            fp.sendMessage("§cUsage: §e/" + fc.label() + " admin unflag <id>");
            return;
        }

        long id;
        try { id = Long.parseLong(a.get(2)); } catch (Exception e) { id = -1; }
        if (id <= 0) {
            fp.sendMessage("§cInvalid id.");
            return;
        }

        final long fid = id;
        final UUID fadmin = fp.getUniqueId();

        plugin.runAsync(() -> {
            try { plugin.auditRepo().unflag(fid, fadmin); }
            catch (Exception ignored) {}

            Bukkit.getScheduler().runTask(plugin, () -> fp.sendMessage("§aUnflagged TX #" + fid + "."));
        });
    }


    private void purge(Player p, Ctx c) {
        final Player fp = p;
        final Ctx fc = c;

        var msg = fc.msg();

        int days = (fc.args().size() >= 3) ? parseInt(fc.args().get(2)).orElse(-1) : -1;
        if (days <= 0) { fp.sendMessage(msg.fmt("usage.purge", Map.of("label", fc.label()))); return; }

        final int fdays = days;

        plugin.runAsync(() -> {
            int deleted;
            try { deleted = plugin.auditRepo().purgeOlderThanDays(fdays); }
            catch (Exception ignored) { deleted = 0; }

            final int fdeleted = deleted;

            Bukkit.getScheduler().runTask(plugin, () -> fp.sendMessage(
                    msg.fmt("audit.purge_done", Map.of(
                            "deleted", String.valueOf(fdeleted),
                            "days", String.valueOf(fdays)
                    ))
            ));
        });
    }



    private void health(Player p, Ctx c) {
        var msg = c.msg();
        boolean dbOk = plugin.db() != null && plugin.db().isOpen();

        boolean vaultOk = Bukkit.getPluginManager().getPlugin("Vault") != null &&
                Bukkit.getServicesManager().getRegistration(Economy.class) != null;

        double[] tpsArr = Bukkit.getTPS();
        int idx = c.cfg().health().tpsSample();
        double tps = (idx >= 0 && idx < tpsArr.length) ? tpsArr[idx] : tpsArr[0];

        p.sendMessage(msg.get("health.header"));
        p.sendMessage(msg.fmt("health.db", Map.of("state", dbOk ? "§aOK" : "§cKO")));
        p.sendMessage(msg.fmt("health.vault", Map.of("state", vaultOk ? "§aOK" : "§cKO")));
        p.sendMessage(msg.fmt("health.tps", Map.of("tps", String.format(Locale.US, "%.2f", tps))));
    }

    private void stats(Player p, Ctx c) {
        var msg = c.msg();
        p.sendMessage(msg.get("stats.header"));
        p.sendMessage(msg.fmt("stats.dirty", Map.of("dirty", String.valueOf(plugin.accounts().dirtySize()))));
        p.sendMessage(msg.fmt("stats.flush_queued", Map.of("queued", String.valueOf(plugin.isFlushQueued()))));
        p.sendMessage(msg.fmt("stats.last_flush", Map.of(
                "when", plugin.formattedLastFlush(),
                "duration", String.valueOf(plugin.lastFlushDurationMs())
        )));
        p.sendMessage(msg.fmt("stats.autosave", Map.of(
                "enabled", String.valueOf(plugin.autosaveEnabled()),
                "minutes", String.valueOf(plugin.autosaveMinutes())
        )));
    }

    private void version(Player p, Ctx c) {
        boolean vault = Bukkit.getPluginManager().getPlugin("Vault") != null;

        String provider = "NONE";
        String pluginName = "NONE";
        if (vault) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null && rsp.getProvider() != null) {
                provider = rsp.getProvider().getName();
                pluginName = rsp.getPlugin().getName();
            }
        }

        p.sendMessage(c.msg().fmt("version.line", Map.of(
                "version", plugin.getDescription().getVersion(),
                "provider", provider,
                "plugin", pluginName
        )));
    }

    private static Map<String, String> parseFlags(List<String> args) {
        Map<String, String> out = new HashMap<>();
        for (String s : args) {
            if (s == null) continue;
            if (!s.startsWith("--")) continue;
            String raw = s.substring(2);
            int eq = raw.indexOf('=');
            if (eq <= 0) continue;
            String k = raw.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = raw.substring(eq + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    private static String normalizeUpperOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.toUpperCase(Locale.ROOT);
    }

    private static Optional<Double> parseAmount(String raw) {
        if (raw == null) return Optional.empty();
        try { return Optional.of(Double.parseDouble(raw.replace(',', '.'))); }
        catch (Exception e) { return Optional.empty(); }
    }

    private static Optional<Integer> parseInt(String raw) {
        if (raw == null) return Optional.empty();
        try { return Optional.of(Integer.parseInt(raw)); }
        catch (Exception e) { return Optional.empty(); }
    }

    private static String safeName(OfflinePlayer p, String fallback) {
        return p.getName() != null ? p.getName() : fallback;
    }

    private static String playerToken(UUID uuid) {
        if (uuid == null) return "§7SYSTEM";
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String name = (op != null && op.getName() != null && !op.getName().isBlank()) ? op.getName() : uuid.toString();
        return name + " §8(" + uuid + "§8)";
    }
}
