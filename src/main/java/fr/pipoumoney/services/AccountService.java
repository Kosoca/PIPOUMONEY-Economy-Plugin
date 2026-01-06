package fr.pipoumoney.services;

import fr.pipoumoney.db.repositories.AccountsRepository;
import fr.pipoumoney.utils.MoneyUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class AccountService {

    public record DisplayRow(UUID uuid, String displayName, double balance) {}

    private final AccountsRepository repo;
    private final int decimals;

    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> notify = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> locked = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicInteger dirtyCount = new AtomicInteger(0);

    public AccountService(AccountsRepository repo, int decimals) {
        this.repo = repo;
        this.decimals = Math.max(0, Math.min(8, decimals));
    }

    public void warmup() throws Exception {
        balances.clear(); names.clear(); notify.clear(); locked.clear(); lastActivity.clear();
        dirty.clear();
        dirtyCount.set(0);

        var all = repo.loadAll();
        for (var e : all.entrySet()) {
            UUID u = e.getKey();
            var r = e.getValue();
            balances.put(u, MoneyUtil.round(r.balance(), decimals));
            if (r.name() != null) names.put(u, r.name());
            notify.put(u, r.notificationsEnabled());
            locked.put(u, r.locked());
            lastActivity.put(u, r.lastActivityMs());
        }
    }

    public void ensure(UUID uuid) {
        boolean changed = false;

        if (balances.putIfAbsent(uuid, MoneyUtil.round(0.0, decimals)) == null) changed = true;
        if (notify.putIfAbsent(uuid, true) == null) changed = true;
        if (locked.putIfAbsent(uuid, false) == null) changed = true;
        if (lastActivity.putIfAbsent(uuid, 0L) == null) changed = true;

        if (changed) markDirty(uuid);
    }

    public void updateName(UUID uuid, String name) {
        if (uuid == null) return;
        ensure(uuid);
        if (name != null && !name.isBlank()) {
            String prev = names.put(uuid, name);
            if (!Objects.equals(prev, name)) markDirty(uuid);
        }
    }

    public double balance(UUID uuid) {
        return balances.getOrDefault(uuid, MoneyUtil.round(0.0, decimals));
    }

    public boolean has(UUID uuid, double amount) {
        return balance(uuid) + 1e-9 >= amount;
    }

    public void touch(UUID uuid) {
        lastActivity.put(uuid, System.currentTimeMillis());
        markDirty(uuid);
    }

    public long lastActivity(UUID uuid) {
        return lastActivity.getOrDefault(uuid, 0L);
    }

    public boolean notifyEnabled(UUID uuid) {
        return notify.getOrDefault(uuid, true);
    }

    public void setNotify(UUID uuid, boolean enabled) {
        ensure(uuid);
        notify.put(uuid, enabled);
        markDirty(uuid);
    }

    public boolean locked(UUID uuid) {
        return locked.getOrDefault(uuid, false);
    }

    public void setLocked(UUID uuid, boolean isLocked) {
        ensure(uuid);
        locked.put(uuid, isLocked);
        markDirty(uuid);
    }

    public void set(UUID uuid, double amount) {
        ensure(uuid);
        balances.put(uuid, MoneyUtil.round(Math.max(0.0, amount), decimals));
        touch(uuid);
    }

    public void add(UUID uuid, double amount) {
        if (amount <= 0) return;
        ensure(uuid);
        balances.compute(uuid, (k, v) -> MoneyUtil.round((v == null ? 0.0 : v) + amount, decimals));
        touch(uuid);
    }

    public boolean remove(UUID uuid, double amount) {
        if (amount <= 0) return true;
        ensure(uuid);

        double cur = balance(uuid);
        if (cur + 1e-9 < amount) return false;

        double next = MoneyUtil.round(cur - amount, decimals);
        if (next < 0) next = 0.0;

        balances.put(uuid, next);
        touch(uuid);
        return true;
    }

    public int dirtySize() {
        return dirtyCount.get();
    }

    public void flushDirty() throws Exception {
        if (dirty.isEmpty()) return;

        List<UUID> toFlush = new ArrayList<>(dirty);
        if (toFlush.isEmpty()) return;

        repo.upsertBatch(
                toFlush,
                uuid -> {
                    String cached = names.get(uuid);
                    return (cached != null && !cached.isBlank()) ? cached : uuid.toString();
                },
                uuid -> balances.getOrDefault(uuid, MoneyUtil.round(0.0, decimals)),
                uuid -> notify.getOrDefault(uuid, true),
                uuid -> locked.getOrDefault(uuid, false),
                uuid -> lastActivity.getOrDefault(uuid, 0L)
        );

        for (UUID u : toFlush) dirty.remove(u);

        dirtyCount.set(dirty.size());
    }

    public int countBalancesDb(double min) throws Exception {
        return repo.countByMin(min);
    }

    public List<DisplayRow> listBalancesDb(fr.pipoumoney.config.PluginConfig.Balances cfg, int page) throws Exception {
        int perPage = cfg.perPage();
        int offset = (Math.max(1, page) - 1) * perPage;

        var base = repo.list(cfg.min(), cfg.sort(), perPage, offset);

        return base.stream()
                .map(r -> new DisplayRow(
                        r.uuid(),
                        formatName(r.uuid(), pickName(r.uuid(), r.name()), cfg.showUuid()),
                        MoneyUtil.round(r.balance(), decimals)
                ))
                .collect(Collectors.toList());
    }

    public List<DisplayRow> topDb(fr.pipoumoney.config.PluginConfig.Balances cfg, int n) throws Exception {
        var base = repo.top(cfg.min(), n);

        return base.stream()
                .map(r -> new DisplayRow(
                        r.uuid(),
                        formatName(r.uuid(), pickName(r.uuid(), r.name()), cfg.showUuid()),
                        MoneyUtil.round(r.balance(), decimals)
                ))
                .collect(Collectors.toList());
    }

    public int countOnlineBalances(Collection<UUID> online, double min) {
        if (online == null || online.isEmpty()) return 0;

        int c = 0;
        for (UUID u : online) {
            if (balances.getOrDefault(u, 0.0) + 1e-9 >= min) c++;
        }
        return c;
    }

    public List<DisplayRow> listOnlineBalances(fr.pipoumoney.config.PluginConfig.Balances cfg,
                                               Collection<UUID> online,
                                               int page) {
        if (online == null || online.isEmpty()) return List.of();

        int perPage = cfg.perPage();
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * perPage;

        List<DisplayRow> all = new ArrayList<>();
        double min = cfg.min();

        for (UUID u : online) {
            double b = balances.getOrDefault(u, 0.0);
            if (b + 1e-9 < min) continue;

            String name = pickName(u, null);
            all.add(new DisplayRow(u, formatName(u, name, cfg.showUuid()), MoneyUtil.round(b, decimals)));
        }

        sortRows(all, cfg.sort());

        if (offset >= all.size()) return List.of();
        int to = Math.min(all.size(), offset + perPage);
        return all.subList(offset, to);
    }

    public List<DisplayRow> topOnline(fr.pipoumoney.config.PluginConfig.Balances cfg,
                                      Collection<UUID> online,
                                      int n) {
        if (online == null || online.isEmpty()) return List.of();

        int limit = Math.max(1, n);
        double min = cfg.min();

        List<DisplayRow> all = new ArrayList<>();
        for (UUID u : online) {
            double b = balances.getOrDefault(u, 0.0);
            if (b + 1e-9 < min) continue;

            String name = pickName(u, null);
            all.add(new DisplayRow(u, formatName(u, name, cfg.showUuid()), MoneyUtil.round(b, decimals)));
        }

        sortRows(all, cfg.sort());
        if (all.size() <= limit) return all;
        return all.subList(0, limit);
    }

    public int rankOf(UUID uuid) throws Exception {
        return repo.rankOf(uuid);
    }

    private void sortRows(List<DisplayRow> rows, String sort) {
        String s = (sort == null) ? "BAL_DESC" : sort.trim().toUpperCase(Locale.ROOT);

        Comparator<DisplayRow> byBalAsc = Comparator.comparingDouble(DisplayRow::balance);
        Comparator<DisplayRow> byBalDesc = byBalAsc.reversed();
        Comparator<DisplayRow> byNameAsc = Comparator.comparing(r -> stripColors(r.displayName()), String.CASE_INSENSITIVE_ORDER);
        Comparator<DisplayRow> byNameDesc = byNameAsc.reversed();

        Comparator<DisplayRow> cmp = switch (s) {
            case "BAL_ASC" -> byBalAsc.thenComparing(byNameAsc);
            case "NAME_ASC" -> byNameAsc.thenComparing(byBalDesc);
            case "NAME_DESC" -> byNameDesc.thenComparing(byBalDesc);
            case "BAL_DESC" -> byBalDesc.thenComparing(byNameAsc);
            default -> byBalDesc.thenComparing(byNameAsc);
        };

        rows.sort(cmp);
    }

    private static String stripColors(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
    }

    private void markDirty(UUID uuid) {
        if (dirty.add(uuid)) dirtyCount.incrementAndGet();
    }

    private String pickName(UUID uuid, String dbName) {
        return Optional.ofNullable(names.get(uuid))
                .or(() -> Optional.ofNullable(dbName))
                .orElse(uuid.toString());
    }

    private String formatName(UUID uuid, String name, boolean showUuid) {
        return showUuid ? (name + " §8(" + uuid + "§8)") : name;
    }
}
