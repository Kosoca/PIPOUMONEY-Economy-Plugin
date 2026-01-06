package fr.pipoumoney.text;

import fr.pipoumoney.config.PluginConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.NumberFormat;
import java.util.Map;

public final class Messages {

    private final FileConfiguration cfg;
    private final NumberFormat nf;
    private final PluginConfig pluginCfg;

    public Messages(FileConfiguration cfg, PluginConfig pluginCfg) {
        this.cfg = cfg;
        this.pluginCfg = pluginCfg;
        this.nf = NumberFormat.getNumberInstance(pluginCfg.format().locale());
        this.nf.setMinimumFractionDigits(pluginCfg.format().decimals());
        this.nf.setMaximumFractionDigits(pluginCfg.format().decimals());
    }

    public String get(String path) {
        return fmt(path, Map.of());
    }

    public String fmt(String path, Map<String, String> vars) {
        String s = cfg.getString(path, "§c[MISSING] " + path);

        String warn = cfg.getString("prefix.warn", "§6§lPipouMoney §c");
        String info = cfg.getString("prefix.info", "§6§lPipouMoney §f");
        String main = cfg.getString("prefix.main", "§6§lPipouMoney §e");

        var cur = pluginCfg.currency();

        s = s.replace("{warn}", warn).replace("{info}", info).replace("{main}", main);
        s = s.replace("{symbol}", cur.symbol())
                .replace("{singular}", cur.singular())
                .replace("{plural}", cur.plural());

        for (var e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    public String money(double v) {
        return nf.format(v);
    }

    public String moneyWithCurrency(double v) {
        var cur = pluginCfg.currency();
        String amount = money(v);
        return cur.format()
                .replace("{amount}", amount)
                .replace("{symbol}", cur.symbol())
                .replace("{singular}", cur.singular())
                .replace("{plural}", cur.plural());
    }
}
