package com.zenith.toolkit.module;

import com.github.rfresh2.EventConsumer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.Globals;
import com.zenith.cache.data.scoreboard.Objective;
import com.zenith.cache.data.scoreboard.Score;
import com.zenith.cache.data.scoreboard.ScoreboardCache;
import com.zenith.event.chat.DeathMessageChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.deathmessages.DeathMessageParseResult;
import com.zenith.feature.deathmessages.Killer;
import com.zenith.feature.deathmessages.KillerType;
import com.zenith.module.api.Module;
import com.zenith.toolkit.BuildConstants;
import com.zenith.toolkit.ToolkitPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static com.github.rfresh2.EventConsumer.of;

public class ScoreboardDeathLogger extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final TrackedBoard deaths = new TrackedBoard(Path.of("scoreboard_deaths.log"));
    private final TrackedBoard kills = new TrackedBoard(Path.of("scoreboard_kills.log"));
    private volatile boolean started = false;
    private volatile boolean scoreboardsFullyDiscovered = false;
    private Set<String> lastKnownObjectives = null;

    @Override
    public boolean enabledSetting() {
        return ToolkitPlugin.CONFIG == null || ToolkitPlugin.CONFIG.scoreboardLogger.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(DeathMessageChatEvent.class, this::onDeathMessage)
        );
    }

    private void onTick(ClientBotTick event) {
        if (started) return;
        started = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deaths.flushNow();
            kills.flushNow();
        }, "ZenithToolkit-ScoreboardFlush"));
        info("Death/Kill Logger scheduler started");
        Globals.EXECUTOR.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    static String cleanName(String name) {
        if (name == null) return null;
        String clean = name.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                           .replaceAll("[\\r\\n\\t\\p{Cntrl}]", "")
                           .replace(" : ", " - ")
                           .trim();
        if (clean.length() > 64) clean = clean.substring(0, 64);
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Default event-driven tracking: parses in-game death messages.
     * Works on any server that displays death announcements.
     */
    private void onDeathMessage(DeathMessageChatEvent event) {
        if (ToolkitPlugin.CONFIG == null || !ToolkitPlugin.CONFIG.scoreboardLogger.enabled) return;
        String mode = ToolkitPlugin.CONFIG.scoreboardLogger.dataSource;
        if (mode != null && !mode.equalsIgnoreCase("EVENT")) {
            return; // In SCOREBOARD mode, events are ignored in favor of scoreboard polling
        }

        try {
            DeathMessageParseResult result = event.deathMessage();
            if (result == null) return;

            String victim = cleanName(result.victim());
            if (victim != null) {
                deaths.increment(victim);
            }

            if (result.killer().isPresent()) {
                Killer killer = result.killer().get();
                if (killer.type() == KillerType.PLAYER && killer.name() != null) {
                    String killerName = cleanName(killer.name());
                    if (killerName != null) {
                        kills.increment(killerName);
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void poll() {
        if (!enabledSetting()) return;
        try {
            deaths.loadIfNeeded();
            kills.loadIfNeeded();

            ScoreboardCache cache = Globals.CACHE.getScoreboardCache();
            if (cache != null) {
                if (!scoreboardsFullyDiscovered) {
                    discoverScoreboards(cache);
                }

                // If user selected SCOREBOARD mode, pull from configured objectives
                if (ToolkitPlugin.CONFIG != null
                        && "SCOREBOARD".equalsIgnoreCase(ToolkitPlugin.CONFIG.scoreboardLogger.dataSource)) {
                    String dObj = ToolkitPlugin.CONFIG.scoreboardLogger.deathsObjective;
                    String kObj = ToolkitPlugin.CONFIG.scoreboardLogger.killsObjective;
                    deaths.update(cache, dObj);
                    kills.update(cache, kObj);
                }
            }

            deaths.flushIfDue();
            kills.flushIfDue();
        } catch (Throwable t) {
            try { info("ScoreboardDeathLogger ERROR: " + t); } catch (Throwable ignored) {}
            t.printStackTrace();
        }
    }

    /**
     * Discovers active scoreboard objectives on the server and writes them
     * to the configuration file up to maxDiscoveredScoreboards.
     */
    private void discoverScoreboards(ScoreboardCache cache) {
        if (ToolkitPlugin.CONFIG == null) return;
        var cfg = ToolkitPlugin.CONFIG.scoreboardLogger;
        var cached = cache.getCachedObjectives();
        if (cached == null || cached.isEmpty()) return;

        var currentKeys = cached.keySet();
        if (lastKnownObjectives != null && lastKnownObjectives.equals(currentKeys)) {
            return;
        }
        lastKnownObjectives = new HashSet<>(currentKeys);

        boolean updated = false;
        int limit = Math.max(1, cfg.maxDiscoveredScoreboards);

        for (String name : cached.keySet()) {
            if (name == null || name.isBlank()) continue;
            if (!cfg.discoveredScoreboards.contains(name)) {
                if (cfg.discoveredScoreboards.size() < limit) {
                    cfg.discoveredScoreboards.add(name);
                    updated = true;
                }
            }
        }

        if (updated) {
            saveConfigToFile();
            info("Discovered new scoreboard objectives (" + cfg.discoveredScoreboards.size() + "/" + limit + "). Config updated.");
        }
        // Stop scanning once the limit is reached
        if (cfg.discoveredScoreboards.size() >= limit) {
            scoreboardsFullyDiscovered = true;
        }
    }

    private void saveConfigToFile() {
        try {
            Path configDir = Path.of("plugins", "config");
            Files.createDirectories(configDir);
            Path configFile = configDir.resolve(BuildConstants.PLUGIN_ID + ".json");
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");

            JsonElement rootToWrite = null;
            if (Files.exists(configFile)) {
                try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed != null && parsed.isJsonObject()) {
                        JsonObject rootObj = parsed.getAsJsonObject();
                        JsonObject sbObj = rootObj.has("scoreboardLogger") && rootObj.get("scoreboardLogger").isJsonObject()
                            ? rootObj.getAsJsonObject("scoreboardLogger")
                            : new JsonObject();
                        JsonArray arr = new JsonArray();
                        for (String s : ToolkitPlugin.CONFIG.scoreboardLogger.discoveredScoreboards) {
                            arr.add(s);
                        }
                        sbObj.add("discoveredScoreboards", arr);
                        rootObj.add("scoreboardLogger", sbObj);
                        rootToWrite = rootObj;
                    }
                } catch (Exception ignored) {
                    // Fallback to full config serialization if existing file cannot be parsed
                }
            }

            try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                if (rootToWrite != null) {
                    GSON.toJson(rootToWrite, writer);
                } else {
                    GSON.toJson(ToolkitPlugin.CONFIG, writer);
                }
            }
            try {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            try { info("ScoreboardDeathLogger: failed to save config: " + t); } catch (Throwable ignored) {}
        }
    }

    private static class TrackedBoard {
        private static final long WRITE_THROTTLE_MS = 5_000L;
        private final Path file;
        private final Map<String, Integer> knownScores = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private boolean loadedFromDisk = false;
        private boolean loadErrorShown = false;
        private boolean dirty = false;
        private long lastWriteMs = 0L;

        TrackedBoard(Path file) {
            this.file = file;
        }

        synchronized void loadIfNeeded() {
            if (loadedFromDisk) return;
            if (!Files.exists(file)) { loadedFromDisk = true; return; }
            try {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String line : content.split("\\r?\\n")) {
                    int sep = line.lastIndexOf(" : ");
                    if (sep == -1) continue;
                    String username = cleanName(line.substring(0, sep));
                    if (username == null) continue;
                    try {
                        knownScores.put(username, Integer.parseInt(line.substring(sep + 3).trim()));
                    } catch (NumberFormatException ignored) {}
                }
                loadedFromDisk = true;
            } catch (IOException e) {
                if (!loadErrorShown) {
                    loadErrorShown = true;
                    e.printStackTrace();
                }
            }
        }

        synchronized void increment(String username) {
            String clean = cleanName(username);
            if (clean == null) return;
            loadIfNeeded();
            if (!loadedFromDisk) return;
            int current = knownScores.getOrDefault(clean, 0);
            knownScores.put(clean, current + 1);
            dirty = true;
            flushIfDue();
        }

        synchronized void update(ScoreboardCache scoreboardCache, String objectiveName) {
            if (objectiveName == null || objectiveName.isBlank()) return;
            Objective objective = scoreboardCache.get(objectiveName);
            if (objective == null) return;

            for (Map.Entry<String, Score> entry : objective.getScores().entrySet()) {
                String username = cleanName(entry.getKey());
                if (username == null) continue;
                int newValue = entry.getValue().getValue();
                Integer oldValue = knownScores.get(username);
                if (oldValue == null || newValue > oldValue) {
                    knownScores.put(username, newValue);
                    dirty = true;
                }
            }
            flushIfDue();
        }

        synchronized void flushIfDue() {
            if (!dirty || !loadedFromDisk) return;
            long now = System.currentTimeMillis();
            if (lastWriteMs == 0L || now - lastWriteMs >= WRITE_THROTTLE_MS) {
                writeFile();
            }
        }

        synchronized void flushNow() {
            if (!dirty || !loadedFromDisk) return;
            writeFile();
        }

        private void writeFile() {
            if (!loadedFromDisk) return;
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : knownScores.entrySet()) {
                sb.append(entry.getKey()).append(" : ").append(entry.getValue()).append("\n");
            }
            try {
                Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                e.printStackTrace();
                return; // keep the old file untouched and keep dirty=true
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                dirty = false;
                lastWriteMs = System.currentTimeMillis();
            } catch (AtomicMoveNotSupportedException e) {
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    dirty = false;
                    lastWriteMs = System.currentTimeMillis();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
