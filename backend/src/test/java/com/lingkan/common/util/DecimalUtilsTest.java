package com.lingkan.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 关键金融计算的单元测试。
 *
 * <p>覆盖技术文档《十四、测试与验收 / 14.3 数据计算》中要求验证的场景：
 * 负 PE、零利润、负利润、股息率为零、未实施分红、财报未披露、周线数据不足等。
 */
class DecimalUtilsTest {

    @Test
    @DisplayName("同比：正常增长")
    void yoyNormal() {
        assertDecimalEquals(new BigDecimal("20"),
                DecimalUtils.yoy(new BigDecimal("120"), new BigDecimal("100")));
    }

    @Test
    @DisplayName("同比：上年同期为负数时按绝对值计算")
    void yoyPreviousNegative() {
        // (50 - (-100)) / |−100| * 100 = 150
        assertDecimalEquals(new BigDecimal("150"),
                DecimalUtils.yoy(new BigDecimal("50"), new BigDecimal("-100")));
    }

    @Test
    @DisplayName("同比：上年同期为 0 时不可计算，返回 null 而不是无穷大")
    void yoyPreviousZero() {
        assertNull(DecimalUtils.yoy(new BigDecimal("50"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("同比：财报未披露（null）时不可计算")
    void yoyMissingData() {
        assertNull(DecimalUtils.yoy(null, new BigDecimal("100")));
        assertNull(DecimalUtils.yoy(new BigDecimal("100"), null));
    }

    @Test
    @DisplayName("PE-TTM：正常计算")
    void peTtmNormal() {
        BigDecimal pe = DecimalUtils.divide(new BigDecimal("100000000000"), new BigDecimal("4000000000"));
        assertDecimalEquals(new BigDecimal("25"), pe);
    }

    @Test
    @DisplayName("PE-TTM：净利润为 0 时不可计算")
    void peTtmZeroProfit() {
        assertNull(DecimalUtils.divide(new BigDecimal("100000000000"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("PE-TTM：净利润为负时得到负值，由上层标记亏损")
    void peTtmNegativeProfit() {
        BigDecimal pe = DecimalUtils.divide(new BigDecimal("100"), new BigDecimal("-50"));
        assertDecimalEquals(new BigDecimal("-2"), pe);
        assertEquals(-1, pe.signum());
    }

    @Test
    @DisplayName("股息率：多次现金分红累加")
    void dividendYieldMultiple() {
        BigDecimal cash = new BigDecimal("1.2").add(new BigDecimal("0.8"));
        BigDecimal yield = DecimalUtils.percent(cash, new BigDecimal("40"));
        assertDecimalEquals(new BigDecimal("5"), yield);
    }

    @Test
    @DisplayName("股息率：未实施分红不参与计算，返回 null")
    void dividendYieldNotImplemented() {
        assertNull(DecimalUtils.percent(null, new BigDecimal("40")));
    }

    @Test
    @DisplayName("股息率：股价为 0 时不可计算")
    void dividendYieldZeroPrice() {
        assertNull(DecimalUtils.percent(new BigDecimal("1.2"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("带内位置：正常区间")
    void bandPositionNormal() {
        assertDecimalEquals(new BigDecimal("50"),
                DecimalUtils.bandPosition(new BigDecimal("15"), new BigDecimal("10"), new BigDecimal("20")));
    }

    @Test
    @DisplayName("带内位置：上下轨相等时不可计算")
    void bandPositionZeroRange() {
        assertNull(DecimalUtils.bandPosition(new BigDecimal("15"), new BigDecimal("10"), new BigDecimal("10")));
    }

    @Test
    @DisplayName("带内位置：价格超出上轨时大于 100%")
    void bandPositionAboveUpper() {
        BigDecimal position = DecimalUtils.bandPosition(new BigDecimal("25"), new BigDecimal("10"),
                new BigDecimal("20"));
        assertDecimalEquals(new BigDecimal("150"), position);
    }

    @Test
    @DisplayName("数值解析：非法字符串返回 null")
    void parseInvalid() {
        assertNull(DecimalUtils.of("--"));
        assertNull(DecimalUtils.of(""));
        assertEquals(new BigDecimal("3.14"), DecimalUtils.of("3.14"));
    }

    @Test
    @DisplayName("舍入：四舍五入到指定位数")
    void roundHalfUp() {
        assertEquals(new BigDecimal("2.35"), DecimalUtils.round(new BigDecimal("2.345"), 2));
        assertEquals(new BigDecimal("-2.35"), DecimalUtils.round(new BigDecimal("-2.345"), 2));
    }

    /**
     * 数值相等断言：DecimalUtils 内部使用 MathContext(16) 计算，精确除尽时
     * 结果不补尾零（标度随运算自然变化），因此断言数值相等而非标度相等。
     */
    private void assertDecimalEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                "期望 " + expected + " 实际 " + actual);
    }
}
