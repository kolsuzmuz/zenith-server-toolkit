package com.zenith.toolkit.module;

import com.google.gson.Gson;
import com.zenith.toolkit.ToolkitConfig;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitDmThrottleTest {

    @Test
    void testPerPlayerCooldownAndCaseInsensitiveMatching() {
        RateLimitDmThrottle throttle = new RateLimitDmThrottle();
        long start = 10_000L;

        assertTrue(throttle.tryAcquire("Steve", start, 3000, 10, 6));
        // Same player (different case) inside 3000ms cooldown is rejected
        assertFalse(throttle.tryAcquire("STEVE", start + 1500L, 3000, 10, 6));
        assertFalse(throttle.tryAcquire("steve", start + 2999L, 3000, 10, 6));
        // After cooldown elapses, request succeeds
        assertTrue(throttle.tryAcquire("sTeVe", start + 3000L, 3000, 10, 6));
    }

    @Test
    void testGlobalAndPerPlayerPerMinuteCapsAndResetAfterMinute() {
        RateLimitDmThrottle throttle = new RateLimitDmThrottle();
        long start = 100_000L;

        // Per-player per-minute cap = 2 (with 1000ms cooldown)
        assertTrue(throttle.tryAcquire("Alice", start, 1000, 3, 2));
        assertTrue(throttle.tryAcquire("Alice", start + 1000L, 1000, 3, 2));
        assertFalse(throttle.tryAcquire("Alice", start + 2000L, 1000, 3, 2));

        // Different player can still take the 3rd global slot
        assertTrue(throttle.tryAcquire("Bob", start + 2500L, 1000, 3, 2));
        // 4th global attempt within 60s fails
        assertFalse(throttle.tryAcquire("Charlie", start + 3000L, 1000, 3, 2));

        // After 60s from Alice's oldest DM, global and per-player slots free up
        assertTrue(throttle.tryAcquire("Alice", start + 60_001L, 1000, 3, 2));
    }

    @Test
    void testIdleEntriesAreCleanedUpAfterSixtySeconds() {
        RateLimitDmThrottle throttle = new RateLimitDmThrottle();
        assertTrue(throttle.tryAcquire("OldPlayer1", 1_000L, 3000, 10, 6));
        assertTrue(throttle.tryAcquire("OldPlayer2", 2_000L, 3000, 10, 6));
        assertEquals(2, throttle.trackedPlayerCount());
        assertEquals(2, throttle.trackedLastDmCount());

        // Trigger acquisition after > 60s so OldPlayer1 & OldPlayer2 are pruned
        assertTrue(throttle.tryAcquire("NewPlayer", 63_000L, 3000, 10, 6));
        assertEquals(1, throttle.trackedPlayerCount());
        assertEquals(1, throttle.trackedLastDmCount());
    }

    @Test
    void testLocaleRootCaseInsensitivityAndBlankPlayerRejection() {
        RateLimitDmThrottle throttle = new RateLimitDmThrottle();
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(throttle.tryAcquire("IPlayer", 1_000L, 3000, 10, 6));
            assertFalse(throttle.tryAcquire("iplayer", 2_000L, 3000, 10, 6));
        } finally {
            Locale.setDefault(prev);
        }
        assertFalse(throttle.tryAcquire(null, 5_000L, 3000, 10, 6));
        assertFalse(throttle.tryAcquire("   ", 5_000L, 3000, 10, 6));
    }

    @Test
    void testRoutingDecisionMatrix() {
        ToolkitConfig.ChatStatsBotConfig.RateLimitDm cfg = new ToolkitConfig.ChatStatsBotConfig.RateLimitDm();

        // Both enabled by default -> DM
        assertTrue(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.GLOBAL));
        assertTrue(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.PER_USER));

        // Master switch off -> drop both
        cfg.enabled = false;
        assertFalse(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.GLOBAL));
        assertFalse(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.PER_USER));

        // Only per-user enabled
        cfg.enabled = true;
        cfg.onGlobalLimit = false;
        cfg.onPerUserLimit = true;
        assertFalse(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.GLOBAL));
        assertTrue(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.PER_USER));

        // Only global enabled
        cfg.onGlobalLimit = true;
        cfg.onPerUserLimit = false;
        assertTrue(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.GLOBAL));
        assertFalse(ChatStatsBot.isRateLimitDmEligible(cfg, RateLimitDmThrottle.BlockReason.PER_USER));

        assertFalse(ChatStatsBot.isRateLimitDmEligible(null, RateLimitDmThrottle.BlockReason.GLOBAL));
        assertFalse(ChatStatsBot.isRateLimitDmEligible(cfg, null));
    }

    @Test
    void testTemplateFallbackAndConfigDefaultsWithLegacyJson() {
        String legacyJson = """
            {
              "sessionLogger": { "enabled": true },
              "chatStatsBot": {
                "enabled": true,
                "worldSeed": "999",
                "antiSpamDm": {
                  "enabled": false,
                  "dmCommandTemplate": "/tell {player} {message}"
                }
              }
            }
            """;
        ToolkitConfig loaded = new Gson().fromJson(legacyJson, ToolkitConfig.class);
        assertNotNull(loaded.chatStatsBot.rateLimitDm);
        assertTrue(loaded.chatStatsBot.rateLimitDm.enabled);
        assertTrue(loaded.chatStatsBot.rateLimitDm.onGlobalLimit);
        assertTrue(loaded.chatStatsBot.rateLimitDm.onPerUserLimit);
        assertEquals("", loaded.chatStatsBot.rateLimitDm.dmCommandTemplate);
        assertEquals(3000, loaded.chatStatsBot.rateLimitDm.perPlayerDmCooldownMs);
        assertEquals(10, loaded.chatStatsBot.rateLimitDm.maxDmPerMinute);
        assertEquals(6, loaded.chatStatsBot.rateLimitDm.maxDmPerMinutePerPlayer);
        assertFalse(loaded.chatStatsBot.antiSpamDm.enabled);

        // Blank rateLimitDm template inherits antiSpamDm template
        assertEquals("/tell {player} {message}",
            ChatStatsBot.resolveRateLimitTemplate(loaded.chatStatsBot.rateLimitDm, loaded.chatStatsBot.antiSpamDm));

        // Explicit rateLimitDm template overrides antiSpamDm template
        loaded.chatStatsBot.rateLimitDm.dmCommandTemplate = "/w {player} {message}";
        assertEquals("/w {player} {message}",
            ChatStatsBot.resolveRateLimitTemplate(loaded.chatStatsBot.rateLimitDm, loaded.chatStatsBot.antiSpamDm));
    }
}
