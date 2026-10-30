package com.lingkan.calculation;

import com.lingkan.financial.entity.StockFinancial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PE-TTM 计算器单元测试（spec 010 §3.4 / 设计 §八 PeTtmCalculatorTest）。
 *
 * <p>口径核心：net_profit_parent 为报告期累计值，须换算单季后再求和；
 * 最近四个报告期必须连续，否则不可计算。
 */
class PeTtmCalculatorTest {

    private static final BigDecimal CAP = new BigDecimal("50000000000");

    private static StockFinancial f(String period, String type, String profit) {
        StockFinancial row = new StockFinancial();
        row.setReportPeriod(period);
        row.setReportType(type);
        row.setNetProfitParent(profit == null ? null : new BigDecimal(profit));
        return row;
    }

    /** TTM 结束于 2026Q3 的标准序列（累计值），单季 = 10/12/8/20 亿，TTM=50 亿 */
    private static List<StockFinancial> standard() {
        return List.of(
                f("2026Q3", "Q3", "3000000000"),
                f("2026Q2", "H1", "2200000000"),
                f("2026Q1", "Q1", "1000000000"),
                f("2025Q4", "ANNUAL", "2500000000"),
                f("2025Q3", "Q3", "500000000"));
    }

    @Test
    @DisplayName("500 亿 / 50 亿 = 10；600 亿 → 12；400 亿 → 8")
    void normalCases() {
        PeTtmCalculator.PeTtmResult r1 = PeTtmCalculator.calculate(CAP, standard());
        assertEquals(PeTtmCalculator.PeStatus.NORMAL, r1.status());
        assertDecimal(new BigDecimal("10"), r1.pe());
        assertDecimal(new BigDecimal("5000000000"), r1.profitTtm());

        PeTtmCalculator.PeTtmResult r2 = PeTtmCalculator.calculate(
                new BigDecimal("60000000000"), standard());
        assertDecimal(new BigDecimal("12"), r2.pe());

        PeTtmCalculator.PeTtmResult r3 = PeTtmCalculator.calculate(
                new BigDecimal("40000000000"), standard());
        assertDecimal(new BigDecimal("8"), r3.pe());
    }

    @Test
    @DisplayName("年报为最新报告期：TTM 结束于 Q4（单季=10/12/8/10 亿 → 40 亿）")
    void annualAsLatest() {
        List<StockFinancial> rows = List.of(
                f("2025Q4", "ANNUAL", "4000000000"),
                f("2025Q3", "Q3", "3000000000"),
                f("2025Q2", "H1", "2200000000"),
                f("2025Q1", "Q1", "1000000000"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.NORMAL, r.status());
        assertDecimal(new BigDecimal("12.5"), r.pe());
    }

    @Test
    @DisplayName("report_type 缺失时按期号推导换算类型")
    void typeDerivedFromPeriod() {
        List<StockFinancial> rows = List.of(
                f("2025Q4", null, "4000000000"),
                f("2025Q3", null, "3000000000"),
                f("2025Q2", null, "2200000000"),
                f("2025Q1", null, "1000000000"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.NORMAL, r.status());
        assertDecimal(new BigDecimal("12.5"), r.pe());
    }

    @Test
    @DisplayName("TTM 归母净利润为 0 → N_A（null）")
    void zeroProfit() {
        List<StockFinancial> rows = List.of(
                f("2026Q3", "Q3", "0"),
                f("2026Q2", "H1", "0"),
                f("2026Q1", "Q1", "0"),
                f("2025Q4", "ANNUAL", "0"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.N_A, r.status());
        assertNull(r.pe());
        assertNotNull(r.reason());
    }

    @Test
    @DisplayName("TTM 归母净利润为负 → LOSS（pe 为负值，展示「亏损」）")
    void lossProfit() {
        List<StockFinancial> rows = List.of(
                f("2026Q3", "Q3", "0"),
                f("2026Q2", "H1", "0"),
                f("2026Q1", "Q1", "0"),
                f("2025Q4", "ANNUAL", "-1000000000"),
                f("2025Q3", "Q3", "0"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.LOSS, r.status());
        assertNotNull(r.pe());
        assertTrue(r.pe().signum() < 0);
        assertDecimal(new BigDecimal("-1000000000"), r.profitTtm());
    }

    @Test
    @DisplayName("不足四个季度 → N_A")
    void insufficientQuarters() {
        List<StockFinancial> rows = List.of(
                f("2026Q3", "Q3", "30"),
                f("2026Q2", "H1", "22"),
                f("2026Q1", "Q1", "10"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.N_A, r.status());
        assertNull(r.pe());
    }

    @Test
    @DisplayName("期号不连续（缺 2026Q2）→ N_A")
    void discontinuousPeriods() {
        List<StockFinancial> rows = List.of(
                f("2026Q3", "Q3", "30"),
                f("2026Q1", "Q1", "10"),
                f("2025Q4", "ANNUAL", "25"),
                f("2025Q3", "Q3", "5"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.N_A, r.status());
        assertNull(r.pe());
    }

    @Test
    @DisplayName("缺少上一报告期累计值（年报无上年 Q3）→ N_A")
    void missingPriorForConversion() {
        List<StockFinancial> rows = List.of(
                f("2026Q3", "Q3", "30"),
                f("2026Q2", "H1", "22"),
                f("2026Q1", "Q1", "10"),
                f("2025Q4", "ANNUAL", "25"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.N_A, r.status());
        assertNull(r.pe());
    }

    @Test
    @DisplayName("任一期归母净利润为 null → N_A")
    void nullProfitValue() {
        List<StockFinancial> rows = List.of(
                f("2026Q3", "Q3", "30"),
                f("2026Q2", "H1", "22"),
                f("2026Q1", "Q1", "10"),
                f("2025Q4", "ANNUAL", null),
                f("2025Q3", "Q3", "5"));
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertEquals(PeTtmCalculator.PeStatus.N_A, r.status());
        assertNull(r.pe());
    }

    @Test
    @DisplayName("总市值缺失 → N_A；财务记录为空 → N_A")
    void missingInputs() {
        assertEquals(PeTtmCalculator.PeStatus.N_A,
                PeTtmCalculator.calculate(null, standard()).status());
        assertEquals(PeTtmCalculator.PeStatus.N_A,
                PeTtmCalculator.calculate(CAP, List.of()).status());
        assertEquals(PeTtmCalculator.PeStatus.N_A,
                PeTtmCalculator.calculate(CAP, null).status());
    }

    @Test
    @DisplayName("同报告期多条记录时取 fetchTime 较新者")
    void dedupeKeepsNewestFetch() {
        StockFinancial old = f("2026Q3", "Q3", "3000000000");
        StockFinancial fresh = f("2026Q3", "Q3", "3000000000");
        fresh.setFetchTime(java.time.LocalDateTime.of(2026, 10, 30, 6, 0));
        old.setFetchTime(java.time.LocalDateTime.of(2026, 10, 30, 5, 0));
        List<StockFinancial> rows = new java.util.ArrayList<>(standard());
        rows.add(old);
        rows.add(fresh);
        PeTtmCalculator.PeTtmResult r = PeTtmCalculator.calculate(CAP, rows);
        assertDecimal(new BigDecimal("10"), r.pe());
    }

    private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertEquals(0, expected.compareTo(actual),
                "expected " + expected + " but was " + actual);
    }
}
