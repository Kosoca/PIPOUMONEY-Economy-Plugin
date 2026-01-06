package fr.pipoumoney.commands;

import fr.pipoumoney.PipouMoney;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class PipouMoneyTabCompleter implements TabCompleter {

    private static final String PERM_PREFIX = "pipoumoney.";
    private static final String PERM_ADMIN = PERM_PREFIX + "admin";

    private static final String PERM_AUDIT_VIEW = PERM_PREFIX + "admin.audit.view";
    private static final String PERM_AUDIT_FLAG = PERM_PREFIX + "admin.audit.flag";
    private static final String PERM_AUDIT_UNFLAG = PERM_PREFIX + "admin.audit.unflag";

    private static final List<String> FLAG_REASONS = List.of(
            "SCAM",
            "DUPLICATION",
            "EXPLOIT",
            "ADMIN_ERROR",
            "ANTI_ABUSE",
            "SUSPICIOUS",
            "REFUND",
            "OTHER"
    );

    private final PipouMoney plugin;

    public PipouMoneyTabCompleter(PipouMoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p)) return List.of();

        String label = (alias == null) ? "" : alias.toLowerCase(Locale.ROOT);

        if (label.equals("bal")) {
            if (args.length == 1 && p.hasPermission(PERM_PREFIX + "balance.other")) return onlineNames(args[0]);
            return List.of();
        }

        if (label.equals("pay")) {
            if (args.length == 1) {
                return filterPrefix(merge(onlineNames(args[0]), List.of("confirm")), args[0]);
            }
            if (args.length == 2) return List.of("<amount>");
            return List.of();
        }

        if (label.equals("baltop")) {
            if (args.length == 1) return filterPrefix(List.of("10", "20", "50", "100"), args[0]);
            return List.of();
        }

        if (!p.hasPermission(PERM_PREFIX + "use")) return List.of();

        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("help", "settings", "bal", "pay", "top", "version"));
            if (p.hasPermission(PERM_ADMIN)) base.add("admin");
            return filterPrefix(base, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("settings")) {
            if (args.length == 2) return filterPrefix(List.of("notify", "lock"), args[1]);
            return List.of();
        }

        if (sub.equals("bal") || sub.equals("balance")) {
            if (args.length == 2 && p.hasPermission(PERM_PREFIX + "balance.other")) return onlineNames(args[1]);
            return List.of();
        }

        if (sub.equals("pay")) {
            if (args.length == 2) return filterPrefix(merge(onlineNames(args[1]), List.of("confirm")), args[1]);
            if (args.length == 3) return List.of("<amount>");
            return List.of();
        }

        if (sub.equals("top")) {
            if (args.length == 2) return filterPrefix(List.of("10", "20", "50", "100"), args[1]);
            return List.of();
        }

        if (sub.equals("admin")) {
            if (!p.hasPermission(PERM_ADMIN)) return List.of();

            if (args.length == 2) {
                List<String> subs = new ArrayList<>(List.of(
                        "give", "take", "set",
                        "history", "balances", "top",
                        "reload", "save", "health", "stats",
                        "purge"
                ));

                if (p.hasPermission(PERM_AUDIT_VIEW)) subs.add("tx");
                if (p.hasPermission(PERM_AUDIT_FLAG)) subs.add("flag");
                if (p.hasPermission(PERM_AUDIT_UNFLAG)) subs.add("unflag");

                return filterPrefix(subs, args[1]);
            }

            String a2 = args[1].toLowerCase(Locale.ROOT);

            if (Set.of("give", "take", "set").contains(a2)) {
                if (args.length == 3) return onlineNames(args[2]);
                if (args.length == 4) return List.of("<amount>");
                return List.of();
            }

            if (a2.equals("history")) {
                if (args.length == 3) return filterPrefix(merge(onlineNames(args[2]), List.of("*")), args[2]);
                if (args.length == 4) return List.of("1", "2", "3", "4", "5");
                if (args.length >= 5) {
                    List<String> flags = List.of(
                            "--days=",
                            "--min=",
                            "--source=",
                            "--type=",
                            "--flagged=true",
                            "--flagged=false"
                    );
                    return filterPrefix(flags, args[args.length - 1]);
                }
                return List.of();
            }

            if (a2.equals("balances")) {
                if (args.length == 3) return List.of("1", "2", "3", "4", "5");
                return List.of();
            }

            if (a2.equals("top")) {
                if (args.length == 3) return filterPrefix(List.of("10", "20", "50", "100"), args[2]);
                return List.of();
            }

            if (a2.equals("purge")) {
                if (args.length == 3) return filterPrefix(List.of("7", "14", "30", "90", "180"), args[2]);
                return List.of();
            }

            if (a2.equals("tx")) {
                if (!p.hasPermission(PERM_AUDIT_VIEW)) return List.of();
                if (args.length == 3) return filterPrefix(recentTxIds(), args[2]);
                return List.of();
            }

            if (a2.equals("flag")) {
                if (!p.hasPermission(PERM_AUDIT_FLAG)) return List.of();
                if (args.length == 3) return filterPrefix(recentTxIds(), args[2]);
                if (args.length >= 4) return filterPrefix(FLAG_REASONS, args[args.length - 1]);
                return List.of();
            }

            if (a2.equals("unflag")) {
                if (!p.hasPermission(PERM_AUDIT_UNFLAG)) return List.of();
                if (args.length == 3) return filterPrefix(recentTxIds(), args[2]);
                return List.of();
            }
        }

        return List.of();
    }

    private List<String> recentTxIds() {
        try {
            return plugin.auditRepo()
                    .recentIds(50)
                    .stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> onlineNames(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p))
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> filterPrefix(List<String> items, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return items.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p))
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
