package com.lingkan.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

    @Test
    @DisplayName("交易日判定只看星期，不看盘中时段（周线同步用）")
    void tradingDayIgnoresSessionHours() {
        // 2026-10-30 周五、2026-10-31 周六、2026-11-01 周日、2026-11-02 周一
        assertTrue(checker.isTradingDay(LocalDate.of(2026, 10, 30)));
        assertFalse(checker.isTradingDay(LocalDate.of(2026, 10, 31)));
        assertFalse(checker.isTradingDay(LocalDate.of(2026, 11, 1)));
        assertTrue(checker.isTradingDay(LocalDate.of(2026, 11, 2)));
        assertFalse(checker.isTradingDay(null));
    }
}
