package com.zenith.toolkit.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolkitModulesTest {

    @Test
    void testCleanNameStripsFormattingAndControlChars() {
        assertNull(ScoreboardDeathLogger.cleanName(null));
        assertNull(ScoreboardDeathLogger.cleanName("   "));
        assertNull(ScoreboardDeathLogger.cleanName("§a§l\r\n\t"));
        assertEquals("Steve", ScoreboardDeathLogger.cleanName("§a§lSteve§r"));
        assertEquals("Clan - Player", ScoreboardDeathLogger.cleanName("Clan : Player\n"));
    }

    @Test
    void testFmtDurFormatsHoursAndMinutes() {
        assertEquals("0m", ChatStatsBot.fmtDur(0));
        assertEquals("0m", ChatStatsBot.fmtDur(59));
        assertEquals("45m", ChatStatsBot.fmtDur(45 * 60));
        assertEquals("2h5m", ChatStatsBot.fmtDur(2 * 3600 + 5 * 60));
    }
}
