package com.lingkan.calculation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 目标价格计算器单元测试（spec 010 §3.3 / 设计 §八 TargetPriceCalculatorTest）。
 */
class TargetPriceCalculatorTest {

    @Test
    @DisplayName("3 元分红：4%→75.00 / 5%→60.00 / 6%→50.00 / 7%→42.86 / 8%→37.50")
    void standardTargets() {
        Map<String, BigDecimal> prices = TargetPriceCalculator
                .calculate(new BigDecimal("3"), TargetPriceCalculator.DEFAULT_TARGET_YIELDS);
        assertEquals(new BigDecimal("75.00"), prices.get("4.0"));
        assertEquals(new BigDecimal("60.00"), prices.get("5.0"));
        assertEquals(new BigDecimal("50.00"), prices.get("6.0"));
        assertEquals(new BigDecimal("42.86"), prices.get("7.0"));
        assertEquals(new BigDecimal("37.50"), prices.get("8.0"));
    }

    @Test
    @DisplayName("默认档位为 4.0~8.0 共 9 档，键为档位字符串")
    void defaultYieldTiers() {
        Map<String, BigDecimal> prices = TargetPriceCalculator
                .calculate(new BigDecimal("3"), null);
        assertEquals(9, prices.size());
        assertEquals(List.of("4.0", "4.5", "5.0", "5.5", "6.0", "6.5", "7.0", "7.5", "8.0"),
                List.copyOf(prices.keySet()));
    }

    @Test
    @DisplayName("分红为 0/null → 所有档位值为 null（键仍齐全）")
    void invalidCashGivesNullPrices() {
        Map<String, BigDecimal> zero = TargetPriceCalculator
                .calculate(BigDecimal.ZERO, List.of(new BigDecimal("4.0")));
        assertEquals(1, zero.size());
        assertNull(zero.get("4.0"));

        Map<String, BigDecimal> nil = TargetPriceCalculator
                .calculate(null, List.of(new BigDecimal("4.0")));
        assertNull(nil.get("4.0"));
    }

    @Test
    @DisplayName("自定义档位生效，空档位回退默认")
    void customAndEmptyTiers() {
        Map<String, BigDecimal> custom = TargetPriceCalculator
                .calculate(new BigDecimal("3"), List.of(new BigDecimal("10.0")));
        assertEquals(new BigDecimal("30.00"), custom.get("10.0"));

        Map<String, BigDecimal> fallback = TargetPriceCalculator
                .calculate(new BigDecimal("3"), List.of());
        assertEquals(9, fallback.size());
    }
}
