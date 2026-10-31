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
    @DisplayName("3 元分红：4%→75.00 / 4.5%→66.67 / 5%→60.00 / 5.5%→54.55 / 6%→50.00 / 6.5%→46.15")
    void standardTargets() {
        Map<String, BigDecimal> prices = TargetPriceCalculator
                .calculate(new BigDecimal("3"), TargetPriceCalculator.DEFAULT_TARGET_YIELDS);
        assertEquals(new BigDecimal("75.00"), prices.get("4.0"));
        assertEquals(new BigDecimal("66.67"), prices.get("4.5"));
        assertEquals(new BigDecimal("60.00"), prices.get("5.0"));
        assertEquals(new BigDecimal("54.55"), prices.get("5.5"));
        assertEquals(new BigDecimal("50.00"), prices.get("6.0"));
        assertEquals(new BigDecimal("46.15"), prices.get("6.5"));
    }

    @Test
    @DisplayName("默认档位为 4.0~6.5 共 6 档（v1.2 移除 7/7.5/8），键为档位字符串")
    void defaultYieldTiers() {
        Map<String, BigDecimal> prices = TargetPriceCalculator
                .calculate(new BigDecimal("3"), null);
        assertEquals(6, prices.size());
        assertEquals(List.of("4.0", "4.5", "5.0", "5.5", "6.0", "6.5"),
                List.copyOf(prices.keySet()));
        assertNull(prices.get("7.0"));
        assertNull(prices.get("7.5"));
        assertNull(prices.get("8.0"));
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
        assertEquals(6, fallback.size());
    }
}
