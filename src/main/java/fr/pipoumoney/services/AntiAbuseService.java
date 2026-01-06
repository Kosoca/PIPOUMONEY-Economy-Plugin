package fr.pipoumoney.services;

import fr.pipoumoney.config.PluginConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiAbuseService {

    public record Result(boolean triggered, boolean block, String reason) {}

    private final PluginConfig.AntiAbuse cfg;

    private final Map<UUID, Deque<Long>> senderTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<AmountAt>> senderWindowAmounts = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<AmountAt>> senderDailyAmounts = new ConcurrentHashMap<>();

    private record AmountAt(long atMs, double amount) {}

    public AntiAbuseService(PluginConfig.AntiAbuse cfg) {
        this.cfg = cfg;
    }

    public PluginConfig.AntiAbuse cfg() {
        return cfg;
    }

    public Result checkPay(UUID from, double amount) {
        if (cfg == null || !cfg.enabled()) return new Result(false, false, null);
        if (from == null) return new Result(false, false, null);
        if (amount <= 0) return new Result(false, false, null);

        long now = System.currentTimeMillis();

        if (cfg.maxTransactionsPerMinute() > 0) {
            Deque<Long> q = senderTimes.computeIfAbsent(from, k -> new ArrayDeque<>());
            pruneTimes(q, now - 60_000L);
            q.addLast(now);
            if (q.size() > cfg.maxTransactionsPerMinute()) {
                return new Result(true, cfg.blockOnTrigger(), "RATE_LIMIT_PER_MINUTE");
            }
        }

        if (cfg.windowMaxAmount() > 0 && cfg.windowSeconds() > 0) {
            long cutoff = now - (cfg.windowSeconds() * 1000L);
            Deque<AmountAt> q = senderWindowAmounts.computeIfAbsent(from, k -> new ArrayDeque<>());
            pruneAmounts(q, cutoff);
            q.addLast(new AmountAt(now, amount));
            double sum = sum(q);
            if (sum - 1e-9 > cfg.windowMaxAmount()) {
                return new Result(true, cfg.blockOnTrigger(), "WINDOW_MAX_AMOUNT");
            }
        }

        if (cfg.dailyMaxAmount() > 0) {
            long cutoff = now - 86_400_000L;
            Deque<AmountAt> q = senderDailyAmounts.computeIfAbsent(from, k -> new ArrayDeque<>());
            pruneAmounts(q, cutoff);
            q.addLast(new AmountAt(now, amount));
            double sum = sum(q);
            if (sum - 1e-9 > cfg.dailyMaxAmount()) {
                return new Result(true, cfg.blockOnTrigger(), "DAILY_MAX_AMOUNT");
            }
        }

        if (cfg.singleTxMaxAmount() > 0 && amount - 1e-9 > cfg.singleTxMaxAmount()) {
            return new Result(true, cfg.blockOnTrigger(), "SINGLE_TX_MAX_AMOUNT");
        }

        return new Result(false, false, null);
    }

    private static void pruneTimes(Deque<Long> q, long cutoff) {
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
    }

    private static void pruneAmounts(Deque<AmountAt> q, long cutoff) {
        while (!q.isEmpty() && q.peekFirst().atMs() < cutoff) q.pollFirst();
    }

    private static double sum(Deque<AmountAt> q) {
        double s = 0.0;
        for (AmountAt a : q) s += a.amount();
        return s;
    }
}
