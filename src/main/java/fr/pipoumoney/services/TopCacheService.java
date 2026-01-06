package fr.pipoumoney.services;

import fr.pipoumoney.db.repositories.AccountsRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class TopCacheService {

    public record Entry(int rank, String name, double balance, String uuid) {}

    private final AccountsRepository accountsRepo;
    private final double minBalance;
    private final int size;

    private volatile List<Entry> snapshot = List.of();
    private final AtomicLong lastRefreshAtMs = new AtomicLong(0L);

    public TopCacheService(AccountsRepository accountsRepo, double minBalance, int size) {
        this.accountsRepo = accountsRepo;
        this.minBalance = minBalance;
        this.size = size;
    }

    public List<Entry> snapshot() {
        return snapshot;
    }

    public long lastRefreshAtMs() {
        return lastRefreshAtMs.get();
    }

    public void refresh() throws Exception {
        var rows = accountsRepo.top(minBalance, size);
        var out = new ArrayList<Entry>(rows.size());
        int rank = 1;
        for (var r : rows) {
            out.add(new Entry(rank++, r.name(), r.balance(), r.uuid().toString()));
        }
        snapshot = Collections.unmodifiableList(out);
        lastRefreshAtMs.set(System.currentTimeMillis());
    }
}
