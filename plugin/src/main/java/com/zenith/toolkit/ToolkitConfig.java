package com.zenith.toolkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin configuration POJO — auto-saved and loaded as JSON by ZenithProxy.
 */
public class ToolkitConfig {
    public final SessionLoggerConfig sessionLogger = new SessionLoggerConfig();
    public static class SessionLoggerConfig {
        public boolean enabled = true;
    }

    public final ScoreboardLoggerConfig scoreboardLogger = new ScoreboardLoggerConfig();
    public static class ScoreboardLoggerConfig {
        public boolean enabled = true;

        // Data source: "EVENT" (default, reads in-game death messages) or "SCOREBOARD"
        public String dataSource = "EVENT";

        // Scoreboard objective names used when dataSource is set to "SCOREBOARD"
        public String deathsObjective = "DeathCount";
        public String killsObjective = "KillCount";

        // Maximum number of discovered scoreboard objectives saved below
        public int maxDiscoveredScoreboards = 20;

        // Auto-discovered scoreboard objectives on the current server
        public List<String> discoveredScoreboards = new ArrayList<>();
    }

    public final ChatStatsBotConfig chatStatsBot = new ChatStatsBotConfig();
    public static class ChatStatsBotConfig {
        public boolean enabled = true;
        public String worldSeed = "0";
        public boolean useSystemTimezone = true;
        public int utcOffsetHours = 0;
        public AntiSpamDm antiSpamDm = new AntiSpamDm();
        public RateLimitDm rateLimitDm = new RateLimitDm();

        /** Fallback configuration for sending replies via DM when server anti-spam blocks public chat. */
        public static class AntiSpamDm {
            public boolean enabled = false;
            public String warningText = "";
            public String fallbackKeyword = "spam";
            public String dmCommandTemplate = "/msg {player} {message}";
            public int detectionWindowMs = 4000;
            public int maxDmPerMinute = 6;
        }

        /** Configuration for routing public rate-limited replies to private /msg DMs. */
        public static class RateLimitDm {
            public boolean enabled = true;
            public boolean onGlobalLimit = true;
            public boolean onPerUserLimit = true;
            public String dmCommandTemplate = "";
            public int perPlayerDmCooldownMs = 3000;
            public int maxDmPerMinute = 10;
            public int maxDmPerMinutePerPlayer = 6;
        }
    }
}
