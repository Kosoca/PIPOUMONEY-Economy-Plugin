package fr.pipoumoney.listeners;

import fr.pipoumoney.PipouMoney;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinListener implements Listener {
    private final PipouMoney plugin;

    public JoinListener(PipouMoney plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.cfg().listenerUpdateNameOnJoin()) return;
        plugin.accounts().updateName(e.getPlayer().getUniqueId(), e.getPlayer().getName());
        plugin.maybeAutoFlush();
    }
}
