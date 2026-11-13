package com.zenith.toolkit.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Globals;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.toolkit.ToolkitPlugin;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.github.rfresh2.EventConsumer.of;

public class ChatStatsBot extends Module {
    private static final Path FILE = Path.of("players.tsv");
    private static final Path SEED_FILE = Path.of("players_seed.tsv");
    private static final Path TMP_FILE = Path.of("players.tsv.tmp");
    private static final Path ENABLE_FILE = Path.of("chatbot_reply.enabled");
    private static final Path DEATHS = Path.of("scoreboard_deaths.log");
    private static final Path KILLS = Path.of("scoreboard_kills.log");
    private static final Pattern TARGET = Pattern.compile("^\\.?[A-Za-z0-9_]{1,16}$");
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,32}$");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final long GLOBAL_COOLDOWN_MS = 3000;
    private static final long USER_COOLDOWN_MS = 8000;
    private static final long SETTLE_SEC = 10;
    private static final long SAVE_EVERY_SEC = 30;

    private static final class P {
        String uuid = "";
        String name;
        final List<String> oldNames = new ArrayList<>();
        long first, last, msgs, joins, play, sess;
    }

    private final Object lock = new Object();
    private final List<P> all = new ArrayList<>();
    private final Map<String, P> byUuid = new HashMap<>();
    private final Map<String, P> byName = new HashMap<>();
    private final Map<UUID, String> known = new HashMap<>();
    private final Deque<String> recentReplies = new ArrayDeque<>();
    private final Map<String, Long> userLast = new HashMap<>();
    private long lastReplyMs = 0;
    private boolean loaded = false;
    private boolean wasEmpty = true;
    private long snapshotUntil = 0;
    private long lastTick = 0;
    private long lastSave = 0;
    private volatile boolean started = false;

    @Override
    public boolean enabledSetting() {
        return ToolkitPlugin.CONFIG == null || ToolkitPlugin.CONFIG.chatStatsBot.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(PublicChatEvent.class, this::onChat)
        );
    }

    private void onTick(ClientBotTick event) {
        if (started) return;
        started = true;
        info("ChatStatsBot scheduler started");
        Globals.EXECUTOR.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    private void index(P p, boolean force) {
        all.add(p);
        if (!p.uuid.isEmpty()) byUuid.put(p.uuid, p);
        String k = p.name.toLowerCase();
        P ex = byName.get(k);
        if (force || ex == null || ex.uuid.isEmpty()) byName.put(k, p);
    }

    private P newP(String uuid, String name, long now) {
        P p = new P();
        p.uuid = uuid;
        p.name = name;
        p.first = now;
        index(p, true);
        return p;
    }

    private void rename(P p, String name) {
        String oldKey = p.name.toLowerCase();
        if (byName.get(oldKey) == p) byName.remove(oldKey);
        boolean has = false;
        for (String n : p.oldNames) if (n.equalsIgnoreCase(p.name)) has = true;
        if (!has) p.oldNames.add(p.name);
        p.oldNames.removeIf(n -> n.equalsIgnoreCase(name));
        p.name = name;
        byName.put(name.toLowerCase(), p);
    }

    private P resolve(UUID id, String name, long now) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) return null;
        String key = name.toLowerCase();
        if (id == null) {
            P p = byName.get(key);
            return p != null ? p : newP("", name, now);
        }
        String u = id.toString();
        P p = byUuid.get(u);
        if (p != null) {
            if (!p.name.equalsIgnoreCase(name)) rename(p, name);
            else p.name = name;
            return p;
        }
        P legacy = byName.get(key);
        if (legacy != null && legacy.uuid.isEmpty()) {
            legacy.uuid = u;
            legacy.name = name;
            byUuid.put(u, legacy);
            return legacy;
        }
        return newP(u, name, now);
    }

    private P find(String target) {
        P p = byName.get(target.toLowerCase());
        if (p != null) return p;
        for (P q : all) {
            for (String n : q.oldNames) if (n.equalsIgnoreCase(target)) return q;
        }
        return null;
    }

    private void ensureLoaded() {
        synchronized (lock) {
            if (loaded) return;
            loaded = true;
            load();
        }
    }

    private void load() {
        Path src = Files.exists(FILE) ? FILE : SEED_FILE;
        if (!Files.exists(src)) return;
        long tick = 0;
        try {
            for (String line : Files.readAllLines(src)) {
                if (line.startsWith("#lastTick|")) {
                    try { tick = Long.parseLong(line.substring(10).trim()); } catch (NumberFormatException ignored) {}
                    continue;
                }
                if (line.startsWith("#")) continue;
                String[] f = line.split("\\|", -1);
                int o;
                P p = new P();
                if (f.length >= 9) { p.uuid = f[0]; o = 1; }
                else if (f.length >= 7) { o = 0; }
                else continue;
                try {
                    p.name = f[o];
                    p.first = Long.parseLong(f[o + 1]);
                    p.last = Long.parseLong(f[o + 2]);
                    p.msgs = Long.parseLong(f[o + 3]);
                    p.joins = Long.parseLong(f[o + 4]);
                    p.play = Long.parseLong(f[o + 5]);
                    p.sess = Long.parseLong(f[o + 6]);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (f.length >= 9 && !f[8].isEmpty()) {
                    for (String n : f[8].split(",")) if (!n.isEmpty()) p.oldNames.add(n);
                }
                if (p.name == null || p.name.isEmpty()) continue;
                index(p, false);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (P p : all) {
            if (p.sess > 0) {
                if (tick > p.sess) p.play += tick - p.sess;
                p.sess = 0;
            }
        }
        lastTick = tick;
        int withUuid = 0;
        for (P p : all) if (!p.uuid.isEmpty()) withUuid++;
        info("ChatStatsBot loaded " + all.size() + " players (" + src + "), with UUID: " + withUuid);
    }

    private void save() {
        try (java.io.BufferedWriter w = Files.newBufferedWriter(TMP_FILE)) {
            synchronized (lock) {
                w.write("#v2 uuid|name|first|last|msgs|joins|play|sess|oldnames\n");
                w.write("#lastTick|" + lastTick + "\n");
                for (P p : all) {
                    w.write(p.uuid + "|" + p.name + "|" + p.first + "|" + p.last + "|"
                        + p.msgs + "|" + p.joins + "|" + p.play + "|" + p.sess + "|"
                        + String.join(",", p.oldNames) + "\n");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        try {
            Files.move(TMP_FILE, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(TMP_FILE, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void closeAllOpen(long at) {
        for (P p : all) {
            if (p.sess > 0) {
                if (at > p.sess) p.play += at - p.sess;
                p.sess = 0;
            }
        }
    }

    private void poll() {
        if (!enabledSetting()) return;
        try {
            ensureLoaded();
            long now = System.currentTimeMillis() / 1000;

            Map<UUID, String> cur = new HashMap<>();
            for (PlayerListEntry e : Globals.CACHE.getTabListCache().getEntries()) {
                if (e.getProfileId() == null || e.getName() == null) continue;
                cur.put(e.getProfileId(), e.getName());
            }

            boolean doSave = false;
            synchronized (lock) {
                if (cur.isEmpty()) {
                    if (!wasEmpty) {
                        closeAllOpen(lastTick > 0 ? lastTick : now);
                        known.clear();
                    }
                    wasEmpty = true;
                    return;
                }
                if (wasEmpty) {
                    known.clear();
                    wasEmpty = false;
                    snapshotUntil = now + SETTLE_SEC;
                }
                boolean snapshot = now < snapshotUntil;

                for (var c : cur.entrySet()) {
                    if (known.containsKey(c.getKey())) continue;
                    P p = resolve(c.getKey(), c.getValue(), now);
                    if (p == null) continue;
                    p.last = now;
                    if (snapshot) {
                        if (p.sess == 0) p.sess = now;
                    } else {
                        p.joins++;
                        p.sess = now;
                    }
                }
                for (var k : known.entrySet()) {
                    if (cur.containsKey(k.getKey())) continue;
                    P p = byUuid.get(k.getKey().toString());
                    if (p == null) p = byName.get(k.getValue().toLowerCase());
                    if (p == null) continue;
                    if (p.sess > 0) {
                        p.play += Math.max(0, now - p.sess);
                        p.sess = 0;
                    }
                    p.last = now;
                }
                known.clear();
                known.putAll(cur);
                lastTick = now;

                if (now - lastSave >= SAVE_EVERY_SEC) {
                    lastSave = now;
                    doSave = true;
                }
            }
            if (doSave) save();
        } catch (Throwable t) {
            try { info("ChatStatsBot poll ERROR: " + t); } catch (Throwable ignored) {}
            t.printStackTrace();
        }
    }

    private void onChat(PublicChatEvent e) {
        try {
            ensureLoaded();
            PlayerListEntry s = e.sender();
            if (s == null || s.getName() == null || e.message() == null) return;
            String name = s.getName();
            String msg = e.message();
            String prefix = "<" + name + "> ";
            if (msg.startsWith(prefix)) msg = msg.substring(prefix.length());
            msg = msg.trim();
            if (msg.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000;
            synchronized (lock) {
                if (recentReplies.contains(msg)) return;
                P p = resolve(s.getProfileId(), name, now);
                if (p != null) {
                    p.msgs++;
                    p.last = now;
                }
            }
            if (msg.startsWith("!")) handleCommand(name, msg);
        } catch (Throwable t) {
            try { info("ChatStatsBot chat ERROR: " + t); } catch (Throwable ignored) {}
            t.printStackTrace();
        }
    }

    private boolean tryAcquireCooldown(String sender) {
        long nowMs = System.currentTimeMillis();
        synchronized (lock) {
            if (nowMs - lastReplyMs < GLOBAL_COOLDOWN_MS) return false;
            Long last = userLast.get(sender.toLowerCase());
            if (last != null && nowMs - last < USER_COOLDOWN_MS) return false;
            lastReplyMs = nowMs;
            if (userLast.size() > 1000) userLast.clear();
            userLast.put(sender.toLowerCase(), nowMs);
            return true;
        }
    }

    private void handleCommand(String sender, String text) {
        String[] parts = text.substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;
        if (!tryAcquireCooldown(sender)) return;
        String cmd = parts[0].toLowerCase();
        String reply = buildReply(cmd, parts, sender);
        if (reply != null) sendReply(sender, reply);
    }

    private String buildReply(String cmd, String[] parts, String sender) {
        if (cmd.equals("seed")) {
            String seed = (ToolkitPlugin.CONFIG != null) ? ToolkitPlugin.CONFIG.chatStatsBot.worldSeed : "0";
            return "World seed: " + seed;
        }
        if (cmd.equals("help") || cmd.equals("commands")) {
            return "Commands: !joindate/!jd, !lastseen/!ls, !info/!inf/!i, !deaths, !kills, "
                + "!playtime/!pt, !message/!messages/!msg, !top kills/deaths/messages/playtime, !seed";
        }
        if (cmd.equals("top")) return top(parts.length > 1 ? parts[1].toLowerCase() : "");

        String target = parts.length > 1 ? parts[1] : sender;
        if (!TARGET.matcher(target).matches()) return null;
        long now = System.currentTimeMillis() / 1000;

        switch (cmd) {
            case "joindate": case "jd":
                return withPlayer(target, p -> p.first == 0 ? "No record of " + target + "."
                    : target + " first seen: " + fmtTs(p.first));
            case "lastseen": case "ls":
                return withPlayer(target, p -> p.sess > 0 ? target + " is online now"
                    : target + " last seen: " + fmtTs(p.last));
            case "playtime": case "pt":
                return withPlayer(target, p -> target + ": playtime " + fmtDur(play(p, now)));
            case "message": case "messages": case "msg":
                return withPlayer(target, p -> target + ": " + p.msgs + " messages");
            case "deaths": {
                Integer d = lookup(DEATHS, target);
                return d == null ? "No death record for " + target + "." : target + ": " + d + " deaths";
            }
            case "kills": {
                Integer k = lookup(KILLS, target);
                return k == null ? "No kill record for " + target + "." : target + ": " + k + " kills";
            }
            case "info": case "inf": case "i": case "stats": {
                Integer d = lookup(DEATHS, target);
                Integer k = lookup(KILLS, target);
                String dp = d != null ? ", deaths " + d : "";
                String kp = k != null ? ", kills " + k : "";
                return withPlayer(target, p -> target + ": " + p.msgs + " messages, joined " + p.joins
                    + "x, playtime " + fmtDur(play(p, now)) + dp + kp + ", first seen " + fmtTs(p.first)
                    + ", last seen " + (p.sess > 0 ? "online now" : fmtTs(p.last)));
            }
            default:
                return null;
        }
    }

    private String withPlayer(String target, Function<P, String> f) {
        synchronized (lock) {
            P p = find(target);
            if (p == null) return "No record of " + target + ".";
            return f.apply(p);
        }
    }

    private String top(String sub) {
        if (sub.equals("kills")) return topFile("Top kills: ", KILLS, "No kill data available.");
        if (sub.equals("deaths")) return topFile("Top deaths: ", DEATHS, "No death data available.");
        long now = System.currentTimeMillis() / 1000;
        if (sub.equals("messages") || sub.equals("msg")) {
            List<Map.Entry<String, Long>> list = new ArrayList<>();
            synchronized (lock) {
                for (P p : all) {
                    if (p.msgs > 0) list.add(Map.entry(p.name, p.msgs));
                }
            }
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder("Top messages: ");
            int n = 0;
            for (var e : list) {
                if (n >= 3) break;
                if (n++ > 0) sb.append(", ");
                sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
            }
            return n == 0 ? "No message data available." : sb.toString();
        }
        if (sub.equals("playtime") || sub.equals("pt")) {
            List<Map.Entry<String, Long>> list = new ArrayList<>();
            synchronized (lock) {
                for (P p : all) {
                    long pt = play(p, now);
                    if (pt > 0) list.add(Map.entry(p.name, pt));
                }
            }
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder("Top playtime: ");
            int n = 0;
            for (var e : list) {
                if (n >= 3) break;
                if (n++ > 0) sb.append(", ");
                sb.append(e.getKey()).append(" (").append(fmtDur(e.getValue())).append(")");
            }
            return n == 0 ? "No playtime data available." : sb.toString();
        }
        return "Usage: !top kills / deaths / messages / playtime";
    }

    private void sendReply(String sender, String reply) {
        String out = reply.replaceAll("\\p{Cntrl}", "");
        if (out.length() > 200) out = out.substring(0, 200);
        if (out.isEmpty() || out.startsWith("/") || out.startsWith("!")) return;

        synchronized (lock) {
            recentReplies.addLast(out);
            while (recentReplies.size() > 20) recentReplies.removeFirst();
        }

        if (!Files.exists(ENABLE_FILE)) {
            info("[DRY-RUN] Reply for " + sender + ": " + out);
            return;
        }
        sendClientPacketAsync(new ServerboundChatPacket(out));
    }

    private static long play(P p, long now) {
        return p.play + (p.sess > 0 ? Math.max(0, now - p.sess) : 0);
    }

    private static String fmtTs(long epoch) {
        if (epoch <= 0) return "unknown";
        if (ToolkitPlugin.CONFIG == null || ToolkitPlugin.CONFIG.chatStatsBot.useSystemTimezone) {
            return Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault()).format(TS_FMT);
        }
        int offset = Math.max(-18, Math.min(18, ToolkitPlugin.CONFIG.chatStatsBot.utcOffsetHours));
        return Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.ofHours(offset)).format(TS_FMT);
    }

    private static String fmtDur(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        return h > 0 ? h + "h" + m + "m" : m + "m";
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Integer lookup(Path path, String target) {
        List<String> lines = readLines(path);
        if (lines == null) return null;
        for (String line : lines) {
            int sep = line.lastIndexOf(" : ");
            if (sep == -1) continue;
            if (line.substring(0, sep).trim().equalsIgnoreCase(target)) {
                try { return Integer.parseInt(line.substring(sep + 3).trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static String topFile(String label, Path path, String empty) {
        List<String> lines = readLines(path);
        if (lines == null) return empty;
        Map<String, Integer> best = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : lines) {
            int sep = line.lastIndexOf(" : ");
            if (sep == -1) continue;
            String name = line.substring(0, sep).trim();
            if (name.isEmpty()) continue;
            try {
                int v = Integer.parseInt(line.substring(sep + 3).trim());
                best.merge(name, v, Math::max);
            } catch (NumberFormatException ignored) {}
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(best.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder(label);
        int n = 0;
        for (var e : list) {
            if (e.getValue() <= 0 || n >= 3) break;
            if (n++ > 0) sb.append(", ");
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
        }
        return n == 0 ? empty : sb.toString();
    }
}
