package com.lingkan.calculation;

import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.entity.StockShareEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 股息率 TTM 计算器单元测试（spec 010 §3.2 / 设计 §八 DividendYieldCalculatorTest）。
 */
class DividendYieldCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private static StockDividend dividend(String id, String status, BigDecimal cash,
                                          LocalDate implDate, LocalDate exDate) {
        StockDividend d = new StockDividend();
        d.setDividendId(id);
        d.setPlanStatus(status);
        d.setCashDividendPerShare(cash);
        d.setImplementationDate(implDate);
        d.setExDividendDate(exDate);
        return d;
    }

    @Test
    @DisplayName("3 元 / 50 元 = 6%；48 元 = 6.25%；55 元 ≈ 5.45%")
    void yieldPercentBasic() {
        assertEquals(new BigDecimal("6.00"),
                DividendYieldCalculator.yieldPercent(new BigDecimal("3"), new BigDecimal("50")));
        assertEquals(new BigDecimal("6.25"),
                DividendYieldCalculator.yieldPercent(new BigDecimal("3"), new BigDecimal("48")));
        assertEquals(new BigDecimal("5.45"),
                DividendYieldCalculator.yieldPercent(new BigDecimal("3"), new BigDecimal("55")));
    }

    @Test
    @DisplayName("价格为 0/null 或分红为空 → null（禁止 0 冒充）")
    void yieldPercentInvalid() {
        assertNull(DividendYieldCalculator.yieldPercent(new BigDecimal("3"), BigDecimal.ZERO));
        assertNull(DividendYieldCalculator.yieldPercent(new BigDecimal("3"), null));
        assertNull(DividendYieldCalculator.yieldPercent(null, new BigDecimal("50")));
    }

    @Test
    @DisplayName("窗口边界：365 天前（含）计入，366 天前剔除")
    void windowBoundary() {
        StockDividend in = dividend("a", "实施分配", new BigDecimal("3"), null,
                TODAY.minusDays(365));
        StockDividend out = dividend("b", "实施分配", new BigDecimal("3"), null,
                TODAY.minusDays(366));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(in, out), List.of(), TODAY);
        assertEquals(new BigDecimal("3"), result.cashPerShare());
    }

    @Test
    @DisplayName("实施日期优先：实施日期在窗口内时，除息日在窗口外也计入")
    void implementationDatePreferred() {
        StockDividend d = dividend("a", "实施分配", new BigDecimal("3"),
                TODAY.minusDays(10), TODAY.minusDays(400));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(d), List.of(), TODAY);
        assertEquals(new BigDecimal("3"), result.cashPerShare());
    }

    @Test
    @DisplayName("实施日期在窗口外但除息日在窗口内 → 剔除（实施日期优先）")
    void implementationDateOutsideExcluded() {
        StockDividend d = dividend("a", "实施分配", new BigDecimal("3"),
                TODAY.minusDays(400), TODAY.minusDays(10));
        assertNull(DividendYieldCalculator.ttmCashPerShare(List.of(d), List.of(), TODAY));
    }

    @Test
    @DisplayName("预案/未实施/状态缺失一律剔除")
    void unimplementedExcluded() {
        StockDividend proposed = dividend("a", "预案", new BigDecimal("3"), null,
                TODAY.minusDays(10));
        StockDividend cancelled = dividend("b", "取消", new BigDecimal("3"), null,
                TODAY.minusDays(10));
        StockDividend noStatus = dividend("c", null, new BigDecimal("3"), null,
                TODAY.minusDays(10));
        assertNull(DividendYieldCalculator.ttmCashPerShare(
                List.of(proposed, cancelled, noStatus), List.of(), TODAY));
    }

    @Test
    @DisplayName("同年度多次已实施分红累加")
    void multipleSameYear() {
        StockDividend d1 = dividend("a", "实施分配", new BigDecimal("1"), null,
                TODAY.minusDays(200));
        StockDividend d2 = dividend("b", "实施分配", new BigDecimal("2"), null,
                TODAY.minusDays(20));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(d1, d2), List.of(), TODAY);
        assertEquals(new BigDecimal("3"), result.cashPerShare());
    }

    @Test
    @DisplayName("跨年度（12 个月窗口内）累加")
    void crossYear() {
        StockDividend d1 = dividend("a", "实施分配", new BigDecimal("1"), null,
                LocalDate.of(2025, 11, 10));
        StockDividend d2 = dividend("b", "实施分配", new BigDecimal("2"), null,
                LocalDate.of(2026, 5, 10));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(d1, d2), List.of(), TODAY);
        assertEquals(new BigDecimal("3"), result.cashPerShare());
    }

    @Test
    @DisplayName("重复 dividend_id 只计一笔，优先保留有实施日期的记录")
    void dedupeByDividendId() {
        StockDividend first = dividend("dup", "实施分配", new BigDecimal("3"), null,
                TODAY.minusDays(100));
        StockDividend second = dividend("dup", "实施分配", new BigDecimal("3"),
                TODAY.minusDays(90), TODAY.minusDays(90));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(first, second), List.of(), TODAY);
        assertEquals(new BigDecimal("3"), result.cashPerShare());
    }

    @Test
    @DisplayName("10 转 10 后 3 元 → 1.5 元口径（股本换算）")
    void shareBasisAdjusted() {
        StockDividend d = dividend("a", "实施分配", new BigDecimal("3"), null,
                LocalDate.of(2026, 6, 15));
        StockShareEvent transfer = new StockShareEvent();
        transfer.setEventDate(LocalDate.of(2026, 6, 15));
        transfer.setEventType(StockShareEvent.TYPE_TRANSFER);
        transfer.setTransferRatio(new BigDecimal("1.0"));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(d), List.of(transfer), TODAY);
        assertEquals(new BigDecimal("1.5"), result.cashPerShare());
    }

    @Test
    @DisplayName("数据时间 = 窗口内最新一笔分红的生效日期")
    void dataTimeIsLatestEffectiveDate() {
        StockDividend d1 = dividend("a", "实施分配", new BigDecimal("1"), null,
                TODAY.minusDays(200));
        StockDividend d2 = dividend("b", "实施分配", new BigDecimal("2"), null,
                TODAY.minusDays(20));
        DividendYieldCalculator.TtmResult result = DividendYieldCalculator
                .ttmCashPerShare(List.of(d1, d2), List.of(), TODAY);
        assertEquals(TODAY.minusDays(20), result.dataTime());
    }

    @Test
    @DisplayName("无有效分红返回 null")
    void nothingValid() {
        assertNull(DividendYieldCalculator.ttmCashPerShare(List.of(), List.of(), TODAY));
        assertNull(DividendYieldCalculator.ttmCashPerShare(null, List.of(), TODAY));
    }
}
