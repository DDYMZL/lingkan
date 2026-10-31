package com.lingkan.calculation;

import com.lingkan.calculation.WeeklyIndicatorCalculator.Params;
import com.lingkan.calculation.WeeklyIndicatorCalculator.WeeklyIndicators;
import com.lingkan.calculation.WeeklyIndicatorCalculator.WeeklyStatus;
import com.lingkan.stock.entity.StockWeeklyQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周线指标计算测试（spec 010 §3.6 / design §八 / spec 007 Q7-2~Q7-8）。
 *
 * <p>样本一律用等差序列构造，均值与样本标准差可手算比对：
 * 连续整数 1..N 的样本方差为 (N²−1)/12 × N/(N−1) = N(N+1)/12，
 * 末尾 n 根连续整数的样本方差恒为 (n²−1)/12 × n/(n−1) = n(n+1)/12。
 * 例：n=20 → 方差 35 → σ=5.91608；n=3 → 方差 1 → σ=1。
 */
class WeeklyIndicatorCalculatorTest {

    /** 最新周线所属周末交易日（2026-10-30 为周五，ISO 2026-W44） */
    private static final LocalDate LAST_FRIDAY = LocalDate.of(2026, 10, 30);

    // ==================== 均线 ====================

    @Test
    @DisplayName("MA60/MA120 精确值：1..120 等差序列 → 60周=90.50、120周=60.50")
    void movingAveragesExact() {
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(sequence(120), Params.DEFAULT);

        assertEquals(WeeklyStatus.NORMAL, r.status());
        assertNull(r.reason());
        assertEquals(120, r.barCount());
        assertEquals(LAST_FRIDAY, r.lastBarDate());
        // 末 60 根 = 61..120，均值 (61+120)/2 = 90.5
        assertDecimal("90.50", r.maShort());
        // 全部 120 根 = 1..120，均值 60.5
        assertDecimal("60.50", r.maLong());
        // 布林窗口 = 末 20 根 101..120，均值 110.5，σ=√35=5.91608，K=2
        assertDecimal("110.50", r.bollMiddle());
        assertDecimal("122.33", r.bollUpper());
        assertDecimal("98.67", r.bollLower());
    }

    @Test
    @DisplayName("恰好 60 根：MA60 可算、MA120 为 null（禁止用已有根数凑算）")
    void exactlySixtyBars() {
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(sequence(60), Params.DEFAULT);

        assertEquals(WeeklyStatus.NORMAL, r.status());
        assertDecimal("30.50", r.maShort());
        assertNull(r.maLong(), "120 周均线在只有 60 根时必须为 null");
        // 布林窗口 = 末 20 根 41..60，均值 50.5
        assertDecimal("50.50", r.bollMiddle());
        assertDecimal("62.33", r.bollUpper());
        assertDecimal("38.67", r.bollLower());
    }

    @Test
    @DisplayName("差一根：59 根 → MA60 null；119 根 → MA120 null")
    void oneBarShort() {
        WeeklyIndicators r59 = WeeklyIndicatorCalculator.calculate(sequence(59), Params.DEFAULT);
        assertNull(r59.maShort());
        assertDecimal("49.50", r59.bollMiddle());   // 末 20 根 40..59
        assertEquals(WeeklyStatus.NORMAL, r59.status());

        WeeklyIndicators r119 = WeeklyIndicatorCalculator.calculate(sequence(119), Params.DEFAULT);
        assertNull(r119.maLong());
        assertDecimal("89.50", r119.maShort());     // 末 60 根 60..119
        assertEquals(WeeklyStatus.NORMAL, r119.status());
    }

    @Test
    @DisplayName("19 根 → 五个指标全 null 且 INSUFFICIENT；20 根 → 布林可算")
    void insufficientBoundary() {
        WeeklyIndicators r19 = WeeklyIndicatorCalculator.calculate(sequence(19), Params.DEFAULT);
        assertEquals(WeeklyStatus.INSUFFICIENT, r19.status());
        assertNull(r19.maShort());
        assertNull(r19.maLong());
        assertNull(r19.bollUpper());
        assertNull(r19.bollMiddle());
        assertNull(r19.bollLower());
        assertEquals(19, r19.barCount());
        assertTrue(r19.reason().contains("根数不足"), "原因应说明根数不足，实际：" + r19.reason());

        WeeklyIndicators r20 = WeeklyIndicatorCalculator.calculate(sequence(20), Params.DEFAULT);
        assertEquals(WeeklyStatus.NORMAL, r20.status());
        assertDecimal("10.50", r20.bollMiddle());
    }

    @Test
    @DisplayName("周期参数来自配置而非硬编码：Params(5,10,3,1) 全部生效")
    void honorsCustomParams() {
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(
                sequence(10), new Params(5, 10, 3, BigDecimal.ONE));

        assertDecimal("8.00", r.maShort());     // 末 5 根 6..10
        assertDecimal("5.50", r.maLong());      // 1..10
        assertDecimal("9.00", r.bollMiddle());  // 末 3 根 8,9,10
        assertDecimal("10.00", r.bollUpper());  // σ(样本,n-1)=1，K=1
        assertDecimal("8.00", r.bollLower());
    }

    // ==================== 样本标准差口径 ====================

    @Test
    @DisplayName("σ 为样本标准差（除以 N−1）：1..20 → 上轨 22.33 / 下轨 -1.33，非总体标准差的 22.03 / -1.03")
    void usesSampleStandardDeviation() {
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(sequence(20), Params.DEFAULT);

        assertDecimal("10.50", r.bollMiddle());
        // 方差 = 665/19 = 35，σ = 5.916080，2σ = 11.832159
        assertDecimal("22.33", r.bollUpper());
        assertDecimal("-1.33", r.bollLower());
    }

    @Test
    @DisplayName("σ=0：收盘价全相等时上下轨与中轨重合")
    void zeroStandardDeviation() {
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(flat(20, "10.00"), Params.DEFAULT);

        assertDecimal("10.00", r.bollMiddle());
        assertDecimal("10.00", r.bollUpper());
        assertDecimal("10.00", r.bollLower());
    }

    // ==================== 前复权负价防护 ====================

    @Test
    @DisplayName("窗口内含负价（前复权）：五个指标全 null + INSUFFICIENT，原因说明数据异常")
    void negativeCloseBlocksWindow() {
        // 实测贵州茅台 2001 年周线前复权为 −312.68，此类价格参与均值/标准差无经济意义
        List<StockWeeklyQuote> bars = sequence(60);
        bars.getLast().setClosePrice(new BigDecimal("-312.68"));

        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(bars, Params.DEFAULT);
        assertEquals(WeeklyStatus.INSUFFICIENT, r.status());
        assertNull(r.maShort());
        assertNull(r.maLong());
        assertNull(r.bollMiddle());
        assertTrue(r.reason().contains("非正"), "原因应说明收盘价非正，实际：" + r.reason());
    }

    @Test
    @DisplayName("窗口内含 0 价：同样全部不输出")
    void zeroCloseBlocksWindow() {
        List<StockWeeklyQuote> bars = flat(20, "10.00");
        bars.get(5).setClosePrice(BigDecimal.ZERO);

        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(bars, Params.DEFAULT);
        assertEquals(WeeklyStatus.INSUFFICIENT, r.status());
        assertNull(r.bollMiddle());
    }

    @Test
    @DisplayName("负价在窗口之外不影响：130 根中最早一根为负，MA120 仍按末 120 根计算")
    void negativeCloseOutsideWindowIgnored() {
        List<StockWeeklyQuote> bars = flat(130, "10.00");
        bars.getFirst().setClosePrice(new BigDecimal("-312.68"));

        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(bars, Params.DEFAULT);
        assertEquals(WeeklyStatus.NORMAL, r.status());
        assertDecimal("10.00", r.maShort());
        assertDecimal("10.00", r.maLong());
        assertDecimal("10.00", r.bollMiddle());
        assertEquals(130, r.barCount());
    }

    // ==================== 缺失与顺序 ====================

    @Test
    @DisplayName("无周线数据：null / 空列表 / 全无效记录 → MISSING")
    void missingData() {
        assertEquals(WeeklyStatus.MISSING,
                WeeklyIndicatorCalculator.calculate(null, Params.DEFAULT).status());
        assertEquals(WeeklyStatus.MISSING,
                WeeklyIndicatorCalculator.calculate(List.of(), Params.DEFAULT).status());

        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(invalidBars(), Params.DEFAULT);
        assertEquals(WeeklyStatus.MISSING, r.status());
        assertEquals(0, r.barCount());
        assertNull(r.lastBarDate());
        assertNull(r.maShort());
    }

    @Test
    @DisplayName("乱序输入内部排序：倒序与升序结果一致")
    void sortsUnorderedInput() {
        List<StockWeeklyQuote> ascending = sequence(20);
        List<StockWeeklyQuote> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);

        WeeklyIndicators a = WeeklyIndicatorCalculator.calculate(ascending, Params.DEFAULT);
        WeeklyIndicators d = WeeklyIndicatorCalculator.calculate(descending, Params.DEFAULT);

        assertEquals(a.bollMiddle(), d.bollMiddle());
        assertEquals(a.bollUpper(), d.bollUpper());
        assertEquals(a.lastBarDate(), d.lastBarDate());
        assertEquals(LAST_FRIDAY, d.lastBarDate());
    }

    // ==================== 本周未收盘覆盖（Q7-8） ====================

    @Test
    @DisplayName("同 ISO 周：本周未收盘周线收盘价被实时价覆盖（周六 10-31 与周五 10-30 同属 W44）")
    void overridesCurrentWeekClose() {
        List<StockWeeklyQuote> bars = flat(20, "10.00");
        BigDecimal livePrice = new BigDecimal("30.00");

        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(bars, livePrice,
                LocalDateTime.of(2026, 10, 31, 10, 0), Params.DEFAULT);

        // 覆盖后：19 根 10 + 1 根 30 → 均值 11.0，方差 380/19=20，σ=4.472136，2σ=8.944272
        assertDecimal("11.00", r.bollMiddle());
        assertDecimal("19.94", r.bollUpper());
        assertDecimal("2.06", r.bollLower());
        assertEquals(20, r.barCount());
        assertEquals(LAST_FRIDAY, r.lastBarDate());
    }

    @Test
    @DisplayName("跨 ISO 周不覆盖：行情时间 11-02（W45）晚于最新周线 10-30（W44）")
    void doesNotOverrideAcrossIsoWeek() {
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(flat(20, "10.00"),
                new BigDecimal("30.00"), LocalDateTime.of(2026, 11, 2, 10, 0), Params.DEFAULT);

        assertDecimal("10.00", r.bollMiddle());
        assertDecimal("10.00", r.bollUpper());
    }

    @Test
    @DisplayName("跨年同 ISO 周仍覆盖：周线 2026-12-28 与行情 2027-01-01 同属 2026-W53")
    void overridesAcrossYearWithinSameIsoWeek() {
        List<StockWeeklyQuote> bars = flatEnding(LocalDate.of(2026, 12, 28), 20, "10.00");

        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(bars, new BigDecimal("30.00"),
                LocalDateTime.of(2027, 1, 1, 10, 0), Params.DEFAULT);

        assertDecimal("11.00", r.bollMiddle());
        assertEquals(LocalDate.of(2026, 12, 28), r.lastBarDate());
    }

    @Test
    @DisplayName("实时价非正或行情时间缺失时不覆盖")
    void doesNotOverrideWithInvalidQuote() {
        List<StockWeeklyQuote> bars = flat(20, "10.00");
        LocalDateTime sameWeek = LocalDateTime.of(2026, 10, 31, 10, 0);

        assertDecimal("10.00", WeeklyIndicatorCalculator
                .calculate(bars, BigDecimal.ZERO, sameWeek, Params.DEFAULT).bollMiddle());
        assertDecimal("10.00", WeeklyIndicatorCalculator
                .calculate(bars, new BigDecimal("-1"), sameWeek, Params.DEFAULT).bollMiddle());
        assertDecimal("10.00", WeeklyIndicatorCalculator
                .calculate(bars, new BigDecimal("30.00"), null, Params.DEFAULT).bollMiddle());
        assertDecimal("10.00", WeeklyIndicatorCalculator
                .calculate(bars, null, sameWeek, Params.DEFAULT).bollMiddle());
    }

    @Test
    @DisplayName("覆盖只改最新一根：均线窗口同步反映实时价")
    void overrideAffectsMovingAverage() {
        List<StockWeeklyQuote> bars = flat(5, "10.00");
        WeeklyIndicators r = WeeklyIndicatorCalculator.calculate(bars, new BigDecimal("20.00"),
                LocalDateTime.of(2026, 10, 31, 10, 0), new Params(5, 5, 5, BigDecimal.ONE));

        // (10×4 + 20) / 5 = 12.00
        assertDecimal("12.00", r.maShort());
        assertDecimal("12.00", r.maLong());
        assertNotNull(r.bollMiddle());
    }

    // ==================== 构造工具 ====================

    /** 1..n 递增收盘价，日期为截至 {@link #LAST_FRIDAY} 的连续周五（升序） */
    private static List<StockWeeklyQuote> sequence(int n) {
        String[] closes = new String[n];
        for (int i = 0; i < n; i++) {
            closes[i] = String.valueOf(i + 1);
        }
        return weeklyBarsEnding(LAST_FRIDAY, closes);
    }

    /** n 根相同收盘价，日期为截至 {@link #LAST_FRIDAY} 的连续周五（升序） */
    private static List<StockWeeklyQuote> flat(int n, String close) {
        return flatEnding(LAST_FRIDAY, n, close);
    }

    private static List<StockWeeklyQuote> flatEnding(LocalDate lastDate, int n, String close) {
        String[] closes = new String[n];
        for (int i = 0; i < n; i++) {
            closes[i] = close;
        }
        return weeklyBarsEnding(lastDate, closes);
    }

    private static List<StockWeeklyQuote> weeklyBarsEnding(LocalDate lastDate, String... closes) {
        List<StockWeeklyQuote> bars = new ArrayList<>(closes.length);
        for (int i = 0; i < closes.length; i++) {
            StockWeeklyQuote bar = new StockWeeklyQuote();
            bar.setStockId(1L);
            bar.setTradeDate(lastDate.minusWeeks(closes.length - 1L - i));
            bar.setClosePrice(new BigDecimal(closes[i]));
            bar.setAdjustmentType("QFQ");
            bar.setSourceCode("eastmoney");
            bar.setFetchTime(LocalDateTime.of(2026, 10, 31, 8, 0));
            bars.add(bar);
        }
        return bars;
    }

    /** 缺日期 / 缺收盘价 / null 元素：均应被剔除 */
    private static List<StockWeeklyQuote> invalidBars() {
        List<StockWeeklyQuote> bars = new ArrayList<>();
        bars.add(null);
        StockWeeklyQuote noDate = new StockWeeklyQuote();
        noDate.setClosePrice(BigDecimal.TEN);
        bars.add(noDate);
        StockWeeklyQuote noClose = new StockWeeklyQuote();
        noClose.setTradeDate(LAST_FRIDAY);
        bars.add(noClose);
        return bars;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual, "期望 " + expected + " 但为 null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + actual.toPlainString());
        assertEquals(2, actual.scale(), "输出应统一保留 2 位小数");
    }
}
