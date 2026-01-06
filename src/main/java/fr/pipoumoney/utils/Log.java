package fr.pipoumoney.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class Log {
    private Log() {}

    // Préfixe unique
    private static final String PFX = "§6[§ePipouMoney§6]§r ";

    public static void info(Plugin plugin, String msg) {
        if (plugin != null) plugin.getLogger().info("§f" + PFX + msg);
        else Bukkit.getConsoleSender().sendMessage("§f" + PFX + msg);
    }

    public static void warn(Plugin plugin, String msg) {
        if (plugin != null) plugin.getLogger().warning("§e" + PFX + msg);
        else Bukkit.getConsoleSender().sendMessage("§e" + PFX + msg);
    }

    public static void error(Plugin plugin, String msg) {
        if (plugin != null) plugin.getLogger().severe("§c" + PFX + msg);
        else Bukkit.getConsoleSender().sendMessage("§c" + PFX + msg);
    }

    private static String color(String s) {
        return s == null ? "" : s;
    }
}
