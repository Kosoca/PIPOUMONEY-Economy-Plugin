package fr.pipoumoney.listeners;

import fr.pipoumoney.PipouMoney;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class QuitListener implements Listener {
    private final PipouMoney plugin;

    public QuitListener(PipouMoney plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!plugin.cfg().listenerFlushOnQuit()) return;
        plugin.maybeAutoFlush();
    }
}
