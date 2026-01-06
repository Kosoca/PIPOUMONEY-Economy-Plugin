package fr.pipoumoney.economy;

import fr.pipoumoney.services.AccountService;
import fr.pipoumoney.services.AuditService;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.function.Function;

public final class VaultPipouMoneyEconomy extends AbstractEconomy {

    private final AccountService accounts;
    private final AuditService audit;
    private final Runnable maybeFlush;
    private final Function<Double, String> formatter;
    private final int decimals;

    public VaultPipouMoneyEconomy(AccountService accounts,
                                  AuditService audit,
                                  Runnable maybeFlush,
                                  Function<Double, String> formatter,
                                  int decimals) {
        this.accounts = accounts;
        this.audit = audit;
        this.maybeFlush = maybeFlush;
        this.formatter = formatter;
        this.decimals = Math.max(0, Math.min(8, decimals));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "PipouMoney";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return decimals;
    }

    @Override
    public String format(double amount) {
        return formatter.apply(amount);
    }

    @Override
    public String currencyNameSingular() {
        return "pipou";
    }

    @Override
    public String currencyNamePlural() {
        return "pipous";
    }

    @Override
    public boolean hasAccount(String playerName) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        return hasAccount(p);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        if (player == null) return false;
        return player.isOnline() || player.hasPlayedBefore();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        return getBalance(p);
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        if (!hasAccount(player)) return 0.0;
        accounts.ensure(player.getUniqueId());
        return accounts.balance(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        return has(p, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (player == null) return false;
        if (!hasAccount(player)) return false;
        if (amount < 0) return true;
        accounts.ensure(player.getUniqueId());
        return accounts.has(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        return withdrawPlayer(p, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player_not_found");
        if (!hasAccount(player)) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player_not_found");
        if (amount < 0) return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "negative");

        accounts.ensure(player.getUniqueId());
        boolean ok = accounts.remove(player.getUniqueId(), amount);
        if (!ok) return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "not_enough");

        audit.logAsync("VAULT", "WITHDRAW", null, player.getUniqueId(), amount);
        maybeFlush.run();
        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        return depositPlayer(p, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player_not_found");
        if (!hasAccount(player)) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "player_not_found");
        if (amount < 0) return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "negative");

        accounts.ensure(player.getUniqueId());
        accounts.add(player.getUniqueId(), amount);
        audit.logAsync("VAULT", "DEPOSIT", null, player.getUniqueId(), amount);
        maybeFlush.run();
        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "no_bank_support");
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        return createPlayerAccount(p);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null) return false;
        if (!hasAccount(player)) return false;
        accounts.ensure(player.getUniqueId());
        maybeFlush.run();
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }
}
