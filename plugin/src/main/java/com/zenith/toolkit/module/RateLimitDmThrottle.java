package com.zenith.toolkit.module;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure (framework-independent) per-player and global rolling throttle plus routing decision
 * logic for rate-limited replies sent via private DM.
 */
public final class RateLimitDmThrottle {

    /** Public cooldown reason that caused a command reply to be diverted to DM. */
    public enum BlockReason {
        GLOBAL,
        PER_USER
    }

    private static final long WINDOW_MS = 60_000L;
    private static final int IDLE_CLEANUP_THRESHOLD = 64;

    private final Deque<Long> total = new ArrayDeque<>();
    private final Map<String, Deque<Long>> perPlayer = new HashMap<>();
    private final Map<String, Long> lastDm = new HashMap<>();

    /** Determines whether a blocked public reply should be routed to DM based on config flags. */
    public static boolean shouldRouteToDm(boolean enabled, boolean onGlobalLimit, boolean onPerUserLimit, BlockReason reason) {
        if (!enabled || reason == null) return false;
        return reason == BlockReason.GLOBAL ? onGlobalLimit : onPerUserLimit;
    }

    /**
     * Checks per-player DM cooldown, rolling global per-minute cap, and rolling per-player
     * per-minute cap. When allowed, updates counters and prunes expired idle entries.
     */
    public synchronized boolean tryAcquire(String player, long now,
                                           int perPlayerCooldownMs, int maxPerMinute, int maxPerMinutePerPlayer) {
        if (player == null || player.isBlank()) return false;
        String key = player.toLowerCase(Locale.ROOT);
        prune(total, now);

        Deque<Long> pp = perPlayer.get(key);
        if (pp != null) {
            prune(pp, now);
            if (pp.isEmpty()) {
                perPlayer.remove(key);
                pp = null;
            }
        }

        Long last = lastDm.get(key);
        if (last != null && perPlayerCooldownMs > 0 && now - last < perPlayerCooldownMs) {
            cleanupIdleIfLarge(now, perPlayerCooldownMs);
            return false;
        }
        if (total.size() >= Math.max(1, maxPerMinute)) {
            cleanupIdleIfLarge(now, perPlayerCooldownMs);
            return false;
        }
        if (pp != null && pp.size() >= Math.max(1, maxPerMinutePerPlayer)) {
            cleanupIdleIfLarge(now, perPlayerCooldownMs);
            return false;
        }

        if (pp == null) {
            pp = new ArrayDeque<>();
            perPlayer.put(key, pp);
        }
        total.addLast(now);
        pp.addLast(now);
        lastDm.put(key, now);
        cleanupIdle(now, perPlayerCooldownMs);
        return true;
    }

    synchronized int trackedPlayerCount() {
        return perPlayer.size();
    }

    synchronized int trackedLastDmCount() {
        return lastDm.size();
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
    }

    private void cleanupIdleIfLarge(long now, int perPlayerCooldownMs) {
        if (perPlayer.size() > IDLE_CLEANUP_THRESHOLD || lastDm.size() > IDLE_CLEANUP_THRESHOLD) {
            cleanupIdle(now, perPlayerCooldownMs);
        }
    }

    private void cleanupIdle(long now, int perPlayerCooldownMs) {
        perPlayer.entrySet().removeIf(e -> {
            prune(e.getValue(), now);
            return e.getValue().isEmpty();
        });
        long retentionMs = Math.max(WINDOW_MS, perPlayerCooldownMs);
        lastDm.entrySet().removeIf(e -> now - e.getValue() > retentionMs);
    }
}
