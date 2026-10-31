package com.lingkan.sync.service;

import com.lingkan.calculation.BoardProperties;
import com.lingkan.common.exception.BizException;
import com.lingkan.datasource.entity.DataSyncLog;
import com.lingkan.datasource.mapper.DataSyncLogMapper;
import com.lingkan.datasource.provider.WeeklyKlineProvider;
import com.lingkan.datasource.provider.dto.WeeklyBar;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.entity.StockWeeklyQuote;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.mapper.StockWeeklyQuoteMapper;
import com.lingkan.sync.dto.SyncAcceptVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 周线同步服务单元测试（spec 010 §4.4 / design §4.4）。
 *
 * <p>覆盖：取数失败保留库内旧数据（返回 false，不写库）；空周线视为成功；
 * 整窗口覆盖 upsert（新增插入、值未变不更新且不刷 fetch_time、值变化才更新）；
 * 缺日期/缺收盘价的脏数据单根跳过；受理入口（空范围、代码不存在、批次互斥）；
 * {@code data_sync_logs} 的 SUCCESS / PARTIAL 终态留痕。
 */
@ExtendWith(MockitoExtension.class)
class WeeklyKlineSyncServiceTest {

    /** 默认取数根数：bars-limit 130 > max(ma-long 120, ma-short 60, boll-n 20) */
    private static final int BARS_LIMIT = 130;

    @Mock
    private WeeklyKlineProvider weeklyKlineProvider;
    @Mock
    private StockWeeklyQuoteMapper weeklyQuoteMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private DataSyncLogMapper syncLogMapper;

    private BoardProperties boardProperties;
    private WeeklyKlineSyncService service;

    @BeforeEach
    void setUp() {
        boardProperties = new BoardProperties();
        boardProperties.getWeekly().setRequestIntervalMs(0);
        service = newService(Runnable::run);
    }

    private WeeklyKlineSyncService newService(Executor executor) {
        return new WeeklyKlineSyncService(weeklyKlineProvider, weeklyQuoteMapper, stockMapper,
                syncLogMapper, boardProperties, executor);
    }

    // ==================== 单只同步 ====================

    @Test
    @DisplayName("取数失败返回 false，保留库内旧周线，不写任何数据")
    void fetchFailureKeepsOldData() {
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(null);

        assertFalse(service.syncStockWeekly(stock(1L, "600519")));
        verify(weeklyQuoteMapper, never()).insert(any(StockWeeklyQuote.class));
        verify(weeklyQuoteMapper, never()).updateById(any(StockWeeklyQuote.class));
    }

    @Test
    @DisplayName("周线为空（新上市）视为成功，不写库")
    void emptyBarsCountsAsSuccess() {
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(List.of());

        assertTrue(service.syncStockWeekly(stock(1L, "600519")));
        verify(weeklyQuoteMapper, never()).insert(any(StockWeeklyQuote.class));
    }

    @Test
    @DisplayName("新周线插入：前复权 + eastmoney + 采集时间留痕")
    void insertsNewBars() {
        when(weeklyQuoteMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(List.of(
                bar(LocalDate.of(2026, 10, 23), "9.90", "10.00", "10.20", "9.80", "100", "1000"),
                bar(LocalDate.of(2026, 10, 30), "10.10", "11.00", "11.30", "10.05", "120", "1300")));

        assertTrue(service.syncStockWeekly(stock(1L, "600519")));

        ArgumentCaptor<StockWeeklyQuote> captor = ArgumentCaptor.forClass(StockWeeklyQuote.class);
        verify(weeklyQuoteMapper, times(2)).insert(captor.capture());
        verify(weeklyQuoteMapper, never()).updateById(any(StockWeeklyQuote.class));
        List<StockWeeklyQuote> rows = captor.getAllValues();
        assertEquals(List.of(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 30)),
                rows.stream().map(StockWeeklyQuote::getTradeDate).toList());
        for (StockWeeklyQuote row : rows) {
            assertEquals(1L, row.getStockId());
            assertEquals(StockWeeklyQuote.ADJUSTMENT_QFQ, row.getAdjustmentType());
            assertEquals("eastmoney", row.getSourceCode());
            assertNotNull(row.getFetchTime());
            assertNotNull(row.getCreatedAt());
        }
        StockWeeklyQuote last = rows.get(1);
        assertEquals(new BigDecimal("10.10"), last.getOpenPrice());
        assertEquals(new BigDecimal("11.00"), last.getClosePrice());
        assertEquals(new BigDecimal("11.30"), last.getHighPrice());
        assertEquals(new BigDecimal("10.05"), last.getLowPrice());
        assertEquals(new BigDecimal("120"), last.getVolume());
        assertEquals(new BigDecimal("1300"), last.getAmount());
    }

    @Test
    @DisplayName("整窗口覆盖 upsert：值未变不更新也不刷 fetch_time，值变化才更新，新日期插入")
    void updatesOnlyChangedBars() {
        LocalDateTime oldFetch = LocalDateTime.of(2026, 10, 24, 9, 0);
        StockWeeklyQuote unchanged = existing(5L, LocalDate.of(2026, 10, 23),
                "9.90", "10.00", "10.20", "9.80", "100", "1000", oldFetch);
        StockWeeklyQuote changed = existing(6L, LocalDate.of(2026, 10, 30),
                "10.10", "11.00", "11.30", "10.05", "120", "1300", oldFetch);
        when(weeklyQuoteMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(unchanged, changed)));
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(List.of(
                // 数值相等但标度不同（10.0 vs 10.00）不算变化
                bar(LocalDate.of(2026, 10, 23), "9.9", "10.0", "10.2", "9.8", "100.0", "1000.00"),
                // 本周收盘价被上游刷新
                bar(LocalDate.of(2026, 10, 30), "10.10", "11.50", "11.60", "10.05", "150", "1600"),
                // 新增一周
                bar(LocalDate.of(2026, 11, 6), "11.60", "12.00", "12.40", "11.50", "90", "1080")));

        assertTrue(service.syncStockWeekly(stock(1L, "600519")));

        ArgumentCaptor<StockWeeklyQuote> insertCaptor = ArgumentCaptor.forClass(StockWeeklyQuote.class);
        verify(weeklyQuoteMapper, times(1)).insert(insertCaptor.capture());
        assertEquals(LocalDate.of(2026, 11, 6), insertCaptor.getValue().getTradeDate());

        ArgumentCaptor<StockWeeklyQuote> updateCaptor = ArgumentCaptor.forClass(StockWeeklyQuote.class);
        verify(weeklyQuoteMapper, times(1)).updateById(updateCaptor.capture());
        StockWeeklyQuote updated = updateCaptor.getValue();
        assertEquals(6L, updated.getId());
        assertEquals(new BigDecimal("11.50"), updated.getClosePrice());
        assertEquals(new BigDecimal("11.60"), updated.getHighPrice());
        assertNotNull(updated.getFetchTime());
        assertTrue(updated.getFetchTime().isAfter(oldFetch));

        // 未变化的行保持原样：fetch_time 不被无意义刷新
        assertEquals(oldFetch, unchanged.getFetchTime());
        assertEquals(new BigDecimal("10.00"), unchanged.getClosePrice());
    }

    @Test
    @DisplayName("缺日期或缺收盘价的脏数据单根跳过，其余照常入库")
    void skipsInvalidBars() {
        when(weeklyQuoteMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(List.of(
                bar(null, "9.90", "10.00", "10.20", "9.80", "100", "1000"),
                bar(LocalDate.of(2026, 10, 23), "9.90", null, "10.20", "9.80", "100", "1000"),
                bar(LocalDate.of(2026, 10, 30), "10.10", "11.00", null, null, null, null)));

        assertTrue(service.syncStockWeekly(stock(1L, "600519")));

        ArgumentCaptor<StockWeeklyQuote> captor = ArgumentCaptor.forClass(StockWeeklyQuote.class);
        verify(weeklyQuoteMapper, times(1)).insert(captor.capture());
        assertEquals(LocalDate.of(2026, 10, 30), captor.getValue().getTradeDate());
        assertNull(captor.getValue().getHighPrice());
    }

    // ==================== 受理入口 ====================

    @Test
    @DisplayName("自选为空时返回受理但数量为 0，不查股票表")
    void submitAllWithEmptyScope() {
        SyncAcceptVo vo = service.submitAll(List.of(), "SCHEDULED");

        assertTrue(vo.accepted());
        assertEquals(0, vo.count());
        assertNotNull(vo.jobId());
        verifyNoInteractions(stockMapper, weeklyKlineProvider, syncLogMapper);
    }

    @Test
    @DisplayName("指定股票不存在时抛 BAD_REQUEST，不启动批次")
    void submitRejectsUnknownCode() {
        when(stockMapper.selectByCodes(List.of("999999"))).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> service.submitForStocks(List.of("999999"), "MANUAL"));
        assertTrue(ex.getMessage().contains("999999"), ex.getMessage());
        verifyNoInteractions(weeklyKlineProvider, syncLogMapper);
    }

    @Test
    @DisplayName("同一时刻只跑一个批次：执行中被拒绝，执行完可再次提交")
    void rejectsConcurrentSubmit() {
        Deque<Runnable> queued = new ArrayDeque<>();
        WeeklyKlineSyncService queuedService = newService(queued::add);
        when(stockMapper.selectByCodes(anyList())).thenReturn(List.of(stock(1L, "600519")));

        assertTrue(queuedService.submitForStocks(List.of("600519"), "MANUAL").accepted());

        SyncAcceptVo rejected = queuedService.submitForStocks(List.of("600519"), "MANUAL");
        assertFalse(rejected.accepted());
        assertNull(rejected.jobId());
        assertEquals(1, queued.size());

        queued.poll().run();
        assertEquals(0, queued.size());
        assertTrue(queuedService.submitForStocks(List.of("600519"), "SCHEDULED").accepted());
        assertEquals(1, queued.size());
    }

    // ==================== 同步日志留痕 ====================

    @Test
    @DisplayName("全部成功记 SUCCESS，errorMessage 为空")
    void logsSuccess() {
        when(stockMapper.selectByCodes(List.of("600519"))).thenReturn(List.of(stock(1L, "600519")));
        when(weeklyQuoteMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(List.of(
                bar(LocalDate.of(2026, 10, 30), "10.10", "11.00", "11.30", "10.05", "120", "1300")));

        SyncAcceptVo vo = service.submitForStocks(List.of("600519"), "MANUAL");

        assertTrue(vo.accepted());
        assertEquals(1, vo.count());
        DataSyncLog entry = capturedLog();
        assertEquals("WEEKLY_KLINE", entry.getSyncType());
        assertEquals("eastmoney", entry.getProviderCode());
        assertEquals(1, entry.getTotalCount());
        assertEquals("SUCCESS", entry.getSyncStatus());
        assertEquals(1, entry.getSuccessCount());
        assertEquals(0, entry.getFailedCount());
        assertNotNull(entry.getStartedAt());
        assertNotNull(entry.getFinishedAt());
        assertNull(entry.getErrorMessage());
    }

    @Test
    @DisplayName("部分失败记 PARTIAL 并写失败原因，成功那只照常入库")
    void logsPartial() {
        when(stockMapper.selectByCodes(anyList())).thenReturn(List.of(
                stock(1L, "600519"), stock(2L, "000001")));
        when(weeklyQuoteMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(weeklyKlineProvider.fetchWeeklyKline("600519", BARS_LIMIT)).thenReturn(List.of(
                bar(LocalDate.of(2026, 10, 30), "10.10", "11.00", "11.30", "10.05", "120", "1300")));
        when(weeklyKlineProvider.fetchWeeklyKline("000001", BARS_LIMIT)).thenReturn(null);

        service.submitAll(List.of("600519", "000001"), "SCHEDULED");

        DataSyncLog entry = capturedLog();
        assertEquals(2, entry.getTotalCount());
        assertEquals("PARTIAL", entry.getSyncStatus());
        assertEquals(1, entry.getSuccessCount());
        assertEquals(1, entry.getFailedCount());
        assertNotNull(entry.getErrorMessage());
        verify(weeklyQuoteMapper, times(1)).insert(any(StockWeeklyQuote.class));
    }

    /** startLog 与 finishLog 复用同一实体，这里取更新时的终态。 */
    private DataSyncLog capturedLog() {
        ArgumentCaptor<DataSyncLog> captor = ArgumentCaptor.forClass(DataSyncLog.class);
        verify(syncLogMapper, times(1)).insert(captor.capture());
        verify(syncLogMapper, times(1)).updateById(captor.capture());
        return captor.getAllValues().get(1);
    }

    // ==================== 构造辅助 ====================

    private static Stock stock(Long id, String code) {
        Stock stock = new Stock();
        stock.setId(id);
        stock.setStockCode(code);
        stock.setStockName(code);
        return stock;
    }

    private static WeeklyBar bar(LocalDate tradeDate, String open, String close,
                                 String high, String low, String volume, String amount) {
        return new WeeklyBar(tradeDate, decimal(open), decimal(close), decimal(high),
                decimal(low), decimal(volume), decimal(amount));
    }

    private static StockWeeklyQuote existing(Long id, LocalDate tradeDate, String open, String close,
                                             String high, String low, String volume, String amount,
                                             LocalDateTime fetchTime) {
        StockWeeklyQuote row = new StockWeeklyQuote();
        row.setId(id);
        row.setStockId(1L);
        row.setTradeDate(tradeDate);
        row.setOpenPrice(decimal(open));
        row.setClosePrice(decimal(close));
        row.setHighPrice(decimal(high));
        row.setLowPrice(decimal(low));
        row.setVolume(decimal(volume));
        row.setAmount(decimal(amount));
        row.setAdjustmentType(StockWeeklyQuote.ADJUSTMENT_QFQ);
        row.setSourceCode("eastmoney");
        row.setFetchTime(fetchTime);
        row.setCreatedAt(fetchTime);
        return row;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
