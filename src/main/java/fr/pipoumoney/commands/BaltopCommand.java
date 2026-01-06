package fr.pipoumoney.commands;

import fr.pipoumoney.PipouMoney;
import fr.pipoumoney.services.TopCacheService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BaltopCommand implements CommandExecutor {

    private final PipouMoney plugin;

    public BaltopCommand(PipouMoney plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        int count = plugin.cfg().top().def();
        if (args.length >= 1) {
            try {
                count = Integer.parseInt(args[0]);
            } catch (Exception ignored) {
            }
        }
        count = Math.max(1, Math.min(plugin.cfg().top().max(), count));

        TopCacheService cache = plugin.topCache();
        List<TopCacheService.Entry> list = cache == null ? List.of() : cache.snapshot();

        sender.sendMessage(plugin.messages().fmt("top.header", java.util.Map.of(
                "count", String.valueOf(count),
                "onlyOnline", String.valueOf(plugin.cfg().balances().onlyOnline()),
                "showUuid", String.valueOf(plugin.cfg().balances().showUuid())
        )));

        int shown = 0;
        for (var e : list) {
            if (shown >= count) break;
            sender.sendMessage(plugin.messages().fmt("top.line", java.util.Map.of(
                    "rank", String.valueOf(e.rank()),
                    "player", e.name(),
                    "amount", plugin.messages().moneyWithCurrency(e.balance()),
                    "uuid", e.uuid()
            )));
            shown++;
        }

        if (shown == 0) {
            sender.sendMessage(plugin.messages().get("history.empty"));
        }
        return true;
    }
}
