package com.zenith.toolkit.module;

import com.google.gson.Gson;
import com.zenith.toolkit.ToolkitConfig;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiSpamDmHandlerTest {

    @Test
    void testIsWarningWithCustomWarningText() {
        assertTrue(AntiSpamDmHandler.isWarning(
            "§c§lLütfen   SPAM   yapmayın!",
            "lütfen spam yapmayın",
            "spam"
        ));
        assertTrue(AntiSpamDmHandler.isWarning(
            "§x§f§f§0§0§0§0[Server] Slow DOWN before chatting again.",
            "slow down",
            "spam"
        ));
        assertFalse(AntiSpamDmHandler.isWarning(
            "§ePlease wait before sending spam again.",
            "slow down",
            "spam"
        ));
        assertFalse(AntiSpamDmHandler.isWarning(
            "",
            "slow down",
            "spam"
        ));
    }

    @Test
    void testIsWarningFallsBackToKeywordWhenWarningTextBlank() {
        assertTrue(AntiSpamDmHandler.isWarning(
            "§cDo not SPAM in chat!",
            "",
            "spam"
        ));
        assertTrue(AntiSpamDmHandler.isWarning(
            "§6Please stop spamming!",
            "   ",
            ""
        ));
        assertFalse(AntiSpamDmHandler.isWarning(
            "§aWelcome to the server!",
            "",
            "spam"
        ));
        assertTrue(AntiSpamDmHandler.isWarning(
            "§eFlood protection triggered",
            null,
            "flood"
        ));
    }

    @Test
    void testNormalizeAndLocaleRootSafety() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(AntiSpamDmHandler.isWarning("INVALID SPAM COMMAND", "invalid", "spam"));
            assertEquals("hello world", AntiSpamDmHandler.normalize("§a§lHello   §r\tWorld  "));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void testClaimLatestSingleClaimExpiryAndOrder() {
        AntiSpamDmHandler handler = new AntiSpamDmHandler();
        handler.registerSent("Steve", "first reply", 1_000L);
        handler.registerSent("Alex", "second reply", 2_000L);

        // Newest unresolved entry is returned first
        AntiSpamDmHandler.PendingReply firstClaim = handler.claimLatest(3_000L, 4_000);
        assertNotNull(firstClaim);
        assertEquals("Alex", firstClaim.playerName);
        assertEquals("second reply", firstClaim.message);
        assertTrue(firstClaim.resolved);

        // Second claim skips already-resolved Alex and picks Steve (within window)
        AntiSpamDmHandler.PendingReply secondClaim = handler.claimLatest(3_500L, 4_000);
        assertNotNull(secondClaim);
        assertEquals("Steve", secondClaim.playerName);
        assertTrue(secondClaim.resolved);

        // Same record cannot be claimed again
        assertNull(handler.claimLatest(3_600L, 4_000));

        // Expired entries cannot be claimed
        handler.registerSent("Bob", "third reply", 10_000L);
        assertNull(handler.claimLatest(14_001L, 4_000));
    }

    @Test
    void testRegisterSentCapsPendingQueueAtSixteen() {
        AntiSpamDmHandler handler = new AntiSpamDmHandler();
        for (int i = 1; i <= 20; i++) {
            handler.registerSent("Player" + i, "msg" + i, 1_000L + i);
        }

        int claimed = 0;
        AntiSpamDmHandler.PendingReply last = null;
        while (true) {
            AntiSpamDmHandler.PendingReply next = handler.claimLatest(2_000L, 4_000);
            if (next == null) break;
            last = next;
            claimed++;
        }
        assertEquals(16, claimed);
        assertNotNull(last);
        assertEquals("Player5", last.playerName);
    }

    @Test
    void testIsValidPlayerName() {
        assertTrue(AntiSpamDmHandler.isValidPlayerName("Steve"));
        assertTrue(AntiSpamDmHandler.isValidPlayerName("a_b1"));
        assertTrue(AntiSpamDmHandler.isValidPlayerName("1234567890123456"));

        assertFalse(AntiSpamDmHandler.isValidPlayerName("Steve; /op x"));
        assertFalse(AntiSpamDmHandler.isValidPlayerName(""));
        assertFalse(AntiSpamDmHandler.isValidPlayerName(null));
        assertFalse(AntiSpamDmHandler.isValidPlayerName("12345678901234567"));
        assertFalse(AntiSpamDmHandler.isValidPlayerName(".Bedrock"));
        assertFalse(AntiSpamDmHandler.isValidPlayerName("Steve\n/op"));
    }

    @Test
    void testBuildCommandLengthCapControlCharsAndSlashes() {
        String longMessage = "x".repeat(250);
        String cmd = AntiSpamDmHandler.buildCommand("/msg {player} {message}", "Steve", longMessage, 200);
        assertTrue(cmd.startsWith("/msg Steve "));
        assertEquals(200, cmd.length());

        String cleaned = AntiSpamDmHandler.buildCommand("/msg {player} {message}", "Steve", "hi\r\n\t there\u0000", 200);
        assertEquals("/msg Steve hi there", cleaned);

        assertEquals("/w Steve ok", AntiSpamDmHandler.buildCommand("w {player} {message}", "Steve", "ok", 200));
        assertEquals("/tell Steve ok", AntiSpamDmHandler.buildCommand("//tell {player} {message}", "Steve", "ok", 200));
        assertEquals("/msg Steve ok", AntiSpamDmHandler.buildCommand("   ", "Steve", "ok", 200));
        assertEquals("", AntiSpamDmHandler.buildCommand("/msg {player} {message}", "Steve", "\r\n\t", 200));
    }

    @Test
    void testAllowDmRateLimitAndWindowReset() {
        AntiSpamDmHandler handler = new AntiSpamDmHandler();
        long start = 100_000L;
        assertTrue(handler.allowDm(start, 2));
        assertTrue(handler.allowDm(start + 1_000L, 2));
        assertFalse(handler.allowDm(start + 2_000L, 2));

        // After 60 seconds have elapsed since the oldest DM, quota frees up again
        assertTrue(handler.allowDm(start + 60_001L, 2));
    }

    @Test
    void testConfigDefaultsAndBackwardCompatibleJsonLoad() {
        ToolkitConfig defaults = new ToolkitConfig();
        assertNotNull(defaults.chatStatsBot.antiSpamDm);
        assertFalse(defaults.chatStatsBot.antiSpamDm.enabled);
        assertEquals("", defaults.chatStatsBot.antiSpamDm.warningText);
        assertEquals("spam", defaults.chatStatsBot.antiSpamDm.fallbackKeyword);
        assertEquals("/msg {player} {message}", defaults.chatStatsBot.antiSpamDm.dmCommandTemplate);
        assertEquals(4000, defaults.chatStatsBot.antiSpamDm.detectionWindowMs);
        assertEquals(6, defaults.chatStatsBot.antiSpamDm.maxDmPerMinute);

        String legacyJson = """
            {
              "sessionLogger": { "enabled": true },
              "chatStatsBot": {
                "enabled": true,
                "worldSeed": "12345",
                "useSystemTimezone": false,
                "utcOffsetHours": 3
              }
            }
            """;
        ToolkitConfig loaded = new Gson().fromJson(legacyJson, ToolkitConfig.class);
        assertEquals("12345", loaded.chatStatsBot.worldSeed);
        assertNotNull(loaded.chatStatsBot.antiSpamDm);
        assertFalse(loaded.chatStatsBot.antiSpamDm.enabled);
        assertEquals("spam", loaded.chatStatsBot.antiSpamDm.fallbackKeyword);
        assertEquals("/msg {player} {message}", loaded.chatStatsBot.antiSpamDm.dmCommandTemplate);

        String partialAntiSpamJson = """
            {
              "chatStatsBot": {
                "antiSpamDm": {
                  "enabled": true,
                  "warningText": "Too fast!"
                }
              }
            }
            """;
        ToolkitConfig partial = new Gson().fromJson(partialAntiSpamJson, ToolkitConfig.class);
        assertTrue(partial.chatStatsBot.antiSpamDm.enabled);
        assertEquals("Too fast!", partial.chatStatsBot.antiSpamDm.warningText);
        assertEquals("spam", partial.chatStatsBot.antiSpamDm.fallbackKeyword);
        assertEquals(4000, partial.chatStatsBot.antiSpamDm.detectionWindowMs);
        assertEquals(6, partial.chatStatsBot.antiSpamDm.maxDmPerMinute);
    }
}
