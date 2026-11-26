package com.zenith.toolkit.module;

import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * Pure (framework-independent) logic to detect server anti-spam warnings
 * and convert the latest pending public chat reply into a private DM command.
 */
public final class AntiSpamDmHandler {

    /** Tracks a public reply eligible for DM fallback within the detection window. */
    public static final class PendingReply {
        public final String playerName;
        public final String message;
        public final long sentAtMs;
        public volatile boolean resolved;

        public PendingReply(String playerName, String message, long sentAtMs) {
            this.playerName = playerName;
            this.message = message;
            this.sentAtMs = sentAtMs;
        }
    }

    private static final Pattern COLOR_CODES = Pattern.compile("§[0-9a-fk-orxA-FK-ORX]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");
    private static final int MAX_PENDING = 16;
    private static final long DM_RATE_WINDOW_MS = 60_000L;

    private final Deque<PendingReply> pending = new ConcurrentLinkedDeque<>();
    private final Deque<Long> dmTimestamps = new ConcurrentLinkedDeque<>();
    private final Object pendingLock = new Object();
    private final Object dmLock = new Object();

    /** Registers a live public chat reply. Never call this for DM replies. */
    public void registerSent(String playerName, String message, long nowMs) {
        if (playerName == null || message == null || message.isEmpty()) return;
        synchronized (pendingLock) {
            pending.addLast(new PendingReply(playerName, message, nowMs));
            while (pending.size() > MAX_PENDING) {
                pending.pollFirst();
            }
        }
    }

    /** Strips Minecraft section formatting codes, collapses whitespace, and lowercases with Locale.ROOT. */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = COLOR_CODES.matcher(s).replaceAll("");
        t = WHITESPACE.matcher(t).replaceAll(" ").trim();
        return t.toLowerCase(Locale.ROOT);
    }

    /** Checks whether a plain system message matches warningText or (when blank) fallbackKeyword. */
    public static boolean isWarning(String systemMessage, String warningText, String fallbackKeyword) {
        String msg = normalize(systemMessage);
        if (msg.isEmpty()) return false;
        String w = normalize(warningText);
        if (!w.isEmpty()) return msg.contains(w);
        String k = normalize(fallbackKeyword);
        if (k.isEmpty()) k = "spam";
        return msg.contains(k);
    }

    /**
     * Evicts expired replies and marks and returns the newest unresolved reply inside windowMs,
     * or null if none match.
     */
    public PendingReply claimLatest(long nowMs, int windowMs) {
        if (windowMs < 0) return null;
        synchronized (pendingLock) {
            pending.removeIf(p -> nowMs - p.sentAtMs > windowMs);
            var it = pending.descendingIterator();
            while (it.hasNext()) {
                PendingReply p = it.next();
                if (!p.resolved && nowMs - p.sentAtMs <= windowMs) {
                    p.resolved = true;
                    return p;
                }
            }
            return null;
        }
    }

    /** Validates Minecraft usernames strictly against [A-Za-z0-9_]{1,16} to prevent command injection. */
    public static boolean isValidPlayerName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** Enforces the rolling per-minute DM cap. */
    public boolean allowDm(long nowMs, int maxPerMinute) {
        int limit = Math.max(1, maxPerMinute);
        synchronized (dmLock) {
            while (true) {
                Long head = dmTimestamps.peekFirst();
                if (head != null && nowMs - head > DM_RATE_WINDOW_MS) {
                    dmTimestamps.pollFirst();
                } else {
                    break;
                }
            }
            if (dmTimestamps.size() >= limit) return false;
            dmTimestamps.addLast(nowMs);
            return true;
        }
    }

    /**
     * Formats the DM command from template with a single leading slash, cleans control
     * characters, and truncates message so total command length stays within maxLen.
     */
    public static String buildCommand(String template, String player, String message, int maxLen) {
        if (player == null || player.isBlank() || message == null || maxLen <= 0) {
            return "";
        }
        String clean = CONTROL_CHARS.matcher(message).replaceAll("").trim();
        if (clean.isEmpty()) {
            return "";
        }

        String tpl = (template == null) ? "" : CONTROL_CHARS.matcher(template).replaceAll("").trim();
        while (tpl.startsWith("/")) {
            tpl = tpl.substring(1).trim();
        }
        if (tpl.isEmpty()) {
            tpl = "msg {player} {message}";
        }
        tpl = "/" + tpl;

        String withoutMsg = tpl.replace("{player}", player).replace("{message}", "");
        if (withoutMsg.length() >= maxLen) {
            return "";
        }
        int room = maxLen - withoutMsg.length();
        if (clean.length() > room) {
            clean = clean.substring(0, room);
        }
        if (clean.isEmpty()) {
            return "";
        }

        String cmd = tpl.replace("{player}", player).replace("{message}", clean);
        return cmd.length() <= maxLen ? cmd : cmd.substring(0, maxLen);
    }
}
