package fr.pipoumoney.placeholders;

import fr.pipoumoney.PipouMoney;
import fr.pipoumoney.services.TopCacheService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class PipouMoneyExpansion extends PlaceholderExpansion {

    private final PipouMoney plugin;

    public PipouMoneyExpansion(PipouMoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "pipoumoney";
    }

    @Override
    public @NotNull String getAuthor() {
        return "PipouMoney";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("symbol")) {
            return plugin.cfg().currency().symbol();
        }
        if (params.equalsIgnoreCase("currency_singular")) {
            return plugin.cfg().currency().singular();
        }
        if (params.equalsIgnoreCase("currency_plural")) {
            return plugin.cfg().currency().plural();
        }

        if (player != null) {
            if (params.equalsIgnoreCase("balance")) {
                plugin.accounts().ensure(player.getUniqueId());
                return String.valueOf(plugin.accounts().balance(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("balance_formatted")) {
                plugin.accounts().ensure(player.getUniqueId());
                return plugin.messages().moneyWithCurrency(plugin.accounts().balance(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("rank")) {
                try {
                    return String.valueOf(plugin.accounts().rankOf(player.getUniqueId()));
                } catch (Exception e) {
                    return "0";
                }
            }
        }

        if (params.startsWith("top_")) {
            String rest = params.substring("top_".length());
            int idx = parseIntPrefix(rest);
            if (idx <= 0) return null;

            String suffix = rest.substring(String.valueOf(idx).length());
            if (suffix.startsWith("_")) suffix = suffix.substring(1);

            TopCacheService cache = plugin.topCache();
            List<TopCacheService.Entry> snap = cache == null ? List.of() : cache.snapshot();
            if (idx > snap.size()) return "";

            TopCacheService.Entry e = snap.get(idx - 1);

            if (suffix.isEmpty() || suffix.equalsIgnoreCase("name")) {
                return e.name();
            }
            if (suffix.equalsIgnoreCase("balance")) {
                return String.valueOf(e.balance());
            }
            if (suffix.equalsIgnoreCase("balance_formatted")) {
                return plugin.messages().moneyWithCurrency(e.balance());
            }
            if (suffix.equalsIgnoreCase("uuid")) {
                return e.uuid();
            }
        }

        return null;
    }

    private static int parseIntPrefix(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return -1;
        try { return Integer.parseInt(s.substring(0, i)); }
        catch (Exception e) { return -1; }
    }
}
