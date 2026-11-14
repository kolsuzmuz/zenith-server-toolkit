package com.zenith.toolkit.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Globals;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.toolkit.ToolkitPlugin;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.rfresh2.EventConsumer.of;

public class SessionLogger extends Module {
    private static final Path FILE = Path.of("sessions.log");
    private static final long SETTLE_MS = 10_000;
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'+00:00'");
    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_.]{1,32}$");

    private final Map<UUID, String> known = new HashMap<>();
    private volatile boolean started = false;
    private long snapshotUntil = 0;
    private long lastBeat = 0;
    private boolean wasEmpty = true;

    @Override
    public boolean enabledSetting() {
        return ToolkitPlugin.CONFIG == null || ToolkitPlugin.CONFIG.sessionLogger.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientBotTick.class, this::onTick));
    }

    private void onTick(ClientBotTick event) {
        if (started) return;
        started = true;
        info("SessionLogger scheduler started");
        Globals.EXECUTOR.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    private static String ts(long millis) {
        return ISO_FMT.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC));
    }

    private static void appendLine(StringBuilder sb, long millis, String uuid, String name, String kind) {
                // Empty name = control events; otherwise it must look like a username (or a HEARTBEAT count).
        // This keeps '|' and line breaks out of the line-oriented log.
        if (!name.isEmpty() && !SAFE_NAME.matcher(name).matches()) return;
        sb.append(ts(millis)).append("|").append(uuid).append("|").append(name).append("|").append(kind).append("\n");
    }

    private void flush(StringBuilder sb) {
        if (sb.length() == 0) return;
        try {
            Files.writeString(FILE, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void poll() {
        if (!enabledSetting()) return;
        try {
            long now = System.currentTimeMillis();

            Map<UUID, String> current = new HashMap<>();
            for (PlayerListEntry e : Globals.CACHE.getTabListCache().getEntries()) {
                if (e.getProfileId() == null || e.getName() == null) continue;
                current.put(e.getProfileId(), e.getName());
            }

            StringBuilder sb = new StringBuilder();

            // If the list is completely empty, the bot got disconnected; do not mark everyone as LEAVE
            if (current.isEmpty()) {
                if (!wasEmpty) {
                    appendLine(sb, now, "", "", "BOT_DISCONNECT");
                    known.clear();
                    flush(sb);
                }
                wasEmpty = true;
                return;
            }
            if (wasEmpty) {
                appendLine(sb, now, "", "", "SNAPSHOT_START");
                known.clear();
                snapshotUntil = now + SETTLE_MS;
                wasEmpty = false;
            }

            if (now - lastBeat > 60_000) {
                lastBeat = now;
                appendLine(sb, now, "", String.valueOf(current.size()), "HEARTBEAT");
            }

            boolean snapshot = now < snapshotUntil;
            for (var c : current.entrySet()) {
                if (!known.containsKey(c.getKey())) {
                    appendLine(sb, now, c.getKey().toString(), c.getValue(), snapshot ? "SNAPSHOT" : "JOIN");
                }
            }
            for (var k : known.entrySet()) {
                if (!current.containsKey(k.getKey())) {
                    appendLine(sb, now, k.getKey().toString(), k.getValue(), "LEAVE");
                }
            }
            known.clear();
            known.putAll(current);

            flush(sb);
        } catch (Throwable t) {
            try { info("SessionLogger ERROR: " + t); } catch (Throwable ignored) {}
            t.printStackTrace();
        }
    }
}
