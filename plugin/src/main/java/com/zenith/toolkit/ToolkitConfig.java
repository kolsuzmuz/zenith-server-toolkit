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
    }
}
