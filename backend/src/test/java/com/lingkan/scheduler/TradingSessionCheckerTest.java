package com.lingkan.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingSessionCheckerTest {

    private final TradingSessionChecker checker =
            new TradingSessionChecker("09:30", "11:30", "13:00", "15:00");

    @Test
    @DisplayName("工作日四个交易时段边界均纳入")
    void includesSessionBoundaries() {
        assertTrue(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 9, 30)));
        assertTrue(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 11, 30)));
        assertTrue(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 13, 0)));
        assertTrue(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 15, 0)));
    }

    @Test
    @DisplayName("盘前、午休和盘后不轮询")
    void excludesNonTradingHours() {
        assertFalse(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 9, 29)));
        assertFalse(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 12, 0)));
        assertFalse(checker.isTradingTime(LocalDateTime.of(2026, 10, 30, 15, 1)));
    }

    @Test
    @DisplayName("周末不轮询")
    void excludesWeekend() {
        assertFalse(checker.isTradingTime(LocalDateTime.of(2026, 10, 31, 10, 0)));
    }
}
