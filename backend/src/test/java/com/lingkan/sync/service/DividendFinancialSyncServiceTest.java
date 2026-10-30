package com.lingkan.sync.service;

import com.lingkan.datasource.mapper.DataSyncLogMapper;
import com.lingkan.datasource.provider.DividendProvider;
import com.lingkan.datasource.provider.FinancialProvider;
import com.lingkan.datasource.provider.dto.DividendItem;
import com.lingkan.datasource.provider.dto.FinancialItem;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.entity.StockShareEvent;
import com.lingkan.dividend.mapper.StockDividendMapper;
import com.lingkan.dividend.mapper.StockShareEventMapper;
import com.lingkan.financial.entity.StockFinancial;
import com.lingkan.financial.mapper.StockFinancialMapper;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.sync.DividendFinancialSyncProperties;
import com.lingkan.watchlist.mapper.UserWatchlistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分红/财务同步服务的核心 upsert 逻辑单元测试（spec 010 §4.3 与设计 §八）。
 *
 * <p>覆盖：取数失败返回 false 且不写库；分红三级匹配（dividend_id / 年度+除息日 / 年度+公告日）；
 * 预案→实施原地更新且不覆盖 implementation_date；重复同步不重复插入；
 * 股本事件仅对已实施+有除息日+比例>0 的方案生成；财报按报告期 upsert、值未变不更新。
 */
@ExtendWith(MockitoExtension.class)
class DividendFinancialSyncServiceTest {

    @Mock
    private DividendProvider dividendProvider;
    @Mock
    private FinancialProvider financialProvider;
    @Mock
    private StockDividendMapper dividendMapper;
    @Mock
    private StockFinancialMapper financialMapper;
    @Mock
    private StockShareEventMapper shareEventMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private UserWatchlistMapper watchlistMapper;
    @Mock
    private DataSyncLogMapper syncLogMapper;
    @Mock
    private Executor dataSyncExecutor;

    private DividendFinancialSyncService service;

    private static final Stock STOCK = new Stock();

    @BeforeEach
    void setUp() {
        STOCK.setId(1L);
        STOCK.setStockCode("600519");
        DividendFinancialSyncProperties properties = new DividendFinancialSyncProperties();
        properties.setRequestIntervalMs(0);
        service = new DividendFinancialSyncService(dividendProvider, financialProvider,
                dividendMapper, financialMapper, shareEventMapper, stockMapper, watchlistMapper,
                syncLogMapper, properties, dataSyncExecutor);
    }

    private static DividendItem dividend(String dividendId, String fiscalYear,
                                         LocalDate announcementDate, LocalDate exDate,
                                         String planStatus, BigDecimal bonus, BigDecimal transfer) {
        return new DividendItem("600519", dividendId, fiscalYear, new BigDecimal("2.80"),
                announcementDate, null, exDate, null, null, planStatus, bonus, transfer,
                "10派28.00元(含税)");
    }

    // ==================== 分红 ====================

    @Test
    @DisplayName("分红：接口失败返回 false，不写任何数据")
    void dividendsFetchFailure() {
        when(dividendProvider.fetchDividends("600519")).thenReturn(null);

        assertFalse(service.syncStockDividends(STOCK));
        verify(dividendMapper, never()).insert(any(StockDividend.class));
        verify(dividendMapper, never()).updateById(any(StockDividend.class));
        verify(shareEventMapper, never()).insert(any(StockShareEvent.class));
    }

    @Test
    @DisplayName("分红：新方案插入；已实施且有除息日时生成送/转两笔股本事件")
    void dividendsInsertWithShareEvents() {
        when(dividendProvider.fetchDividends("600519")).thenReturn(List.of(
                dividend("em:600519:2024:2025-06-20", "2024",
                        LocalDate.of(2025, 3, 15), LocalDate.of(2025, 6, 20),
                        "实施分配", new BigDecimal("1.0"), new BigDecimal("0.4"))));
        when(dividendMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(shareEventMapper.selectOne(any())).thenReturn(null);

        assertTrue(service.syncStockDividends(STOCK));

        ArgumentCaptor<StockDividend> dividendCaptor = ArgumentCaptor.forClass(StockDividend.class);
        verify(dividendMapper, times(1)).insert(dividendCaptor.capture());
        StockDividend row = dividendCaptor.getValue();
        assertEquals("eastmoney", row.getSourceCode());
        assertEquals(LocalDate.of(2025, 6, 20), row.getExDividendDate());
        assertNull(row.getImplementationDate());
        assertNotNull(row.getCreatedAt());

        ArgumentCaptor<StockShareEvent> eventCaptor = ArgumentCaptor.forClass(StockShareEvent.class);
        verify(shareEventMapper, times(2)).insert(eventCaptor.capture());
        List<StockShareEvent> events = eventCaptor.getAllValues();
        assertEquals(List.of("BONUS", "TRANSFER"),
                events.stream().map(StockShareEvent::getEventType).sorted().toList());
        for (StockShareEvent event : events) {
            assertEquals(LocalDate.of(2025, 6, 20), event.getEventDate());
            assertEquals("eastmoney", event.getSourceCode());
        }
    }

    @Test
    @DisplayName("分红：预案方案不生成股本事件")
    void dividendsProposedNoShareEvents() {
        when(dividendProvider.fetchDividends("600519")).thenReturn(List.of(
                dividend("em:600519:2025:2025-03-15", "2025",
                        LocalDate.of(2025, 3, 15), null,
                        "预案", new BigDecimal("1.0"), new BigDecimal("0.4"))));
        when(dividendMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertTrue(service.syncStockDividends(STOCK));
        verify(dividendMapper, times(1)).insert(any(StockDividend.class));
        verify(shareEventMapper, never()).insert(any(StockShareEvent.class));
    }

    @Test
    @DisplayName("分红：预案→实施（公告日匹配）原地更新，不覆盖 implementation_date")
    void dividendsPlanProgressUpdate() {
        StockDividend existing = new StockDividend();
        existing.setId(10L);
        existing.setStockId(1L);
        existing.setFiscalYear("2025");
        existing.setDividendId("em:600519:2025:2025-03-15");
        existing.setAnnouncementDate(LocalDate.of(2025, 3, 15));
        existing.setExDividendDate(null);
        existing.setPlanStatus("预案");
        existing.setImplementationDate(LocalDate.of(2025, 6, 25)); // 人工填写的实施日期
        existing.setSourceCode("eastmoney");
        when(dividendMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(dividendProvider.fetchDividends("600519")).thenReturn(List.of(
                dividend("em:600519:2025:2025-06-20", "2025",
                        LocalDate.of(2025, 3, 15), LocalDate.of(2025, 6, 20),
                        "实施分配", new BigDecimal("1.0"), null)));

        assertTrue(service.syncStockDividends(STOCK));

        ArgumentCaptor<StockDividend> captor = ArgumentCaptor.forClass(StockDividend.class);
        verify(dividendMapper, never()).insert(any(StockDividend.class));
        verify(dividendMapper, times(1)).updateById(captor.capture());
        StockDividend updated = captor.getValue();
        assertEquals(10L, updated.getId());
        assertEquals("实施分配", updated.getPlanStatus());
        assertEquals(LocalDate.of(2025, 6, 20), updated.getExDividendDate());
        assertEquals("em:600519:2025:2025-06-20", updated.getDividendId());
        assertEquals(LocalDate.of(2025, 6, 25), updated.getImplementationDate());
    }

    @Test
    @DisplayName("分红：dividend_id 匹配且无变化时不更新（重复同步去重）")
    void dividendsDuplicateSyncNoUpdate() {
        StockDividend existing = new StockDividend();
        existing.setId(10L);
        existing.setStockId(1L);
        existing.setFiscalYear("2025");
        existing.setDividendId("em:600519:2025:2025-06-20");
        existing.setCashDividendPerShare(new BigDecimal("2.80"));
        existing.setAnnouncementDate(LocalDate.of(2025, 3, 15));
        existing.setExDividendDate(LocalDate.of(2025, 6, 20));
        existing.setPlanStatus("实施分配");
        existing.setSourceCode("eastmoney");
        when(dividendMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(dividendProvider.fetchDividends("600519")).thenReturn(List.of(
                dividend("em:600519:2025:2025-06-20", "2025",
                        LocalDate.of(2025, 3, 15), LocalDate.of(2025, 6, 20),
                        "实施分配", null, null)));

        assertTrue(service.syncStockDividends(STOCK));
        verify(dividendMapper, never()).insert(any(StockDividend.class));
        verify(dividendMapper, never()).updateById(any(StockDividend.class));
    }

    // ==================== 财报 ====================

    @Test
    @DisplayName("财报：接口失败返回 false，不写任何数据")
    void financialsFetchFailure() {
        when(financialProvider.fetchFinancials("600519")).thenReturn(null);

        assertFalse(service.syncStockFinancials(STOCK));
        verify(financialMapper, never()).insert(any(StockFinancial.class));
        verify(financialMapper, never()).updateById(any(StockFinancial.class));
    }

    @Test
    @DisplayName("财报：新报告期插入；同报告期值未变不更新、值变化才更新")
    void financialsUpsert() {
        StockFinancial existing = new StockFinancial();
        existing.setId(20L);
        existing.setStockId(1L);
        existing.setReportPeriod("2026Q3");
        existing.setReportType("Q3");
        existing.setRevenue(new BigDecimal("1000.00"));
        existing.setNetProfitParent(new BigDecimal("300.00"));
        existing.setEps(new BigDecimal("2.5"));
        existing.setRoe(new BigDecimal("8.8"));
        existing.setPublishDate(LocalDate.of(2026, 10, 20));
        existing.setSourceCode("eastmoney");
        when(financialMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing)));
        when(financialProvider.fetchFinancials("600519")).thenReturn(List.of(
                // 未变化（数值相等、标度不同不算变化）
                new FinancialItem("600519", "2026Q3", "Q3", LocalDate.of(2026, 9, 30),
                        new BigDecimal("1000.0"), new BigDecimal("300.000"),
                        new BigDecimal("2.50"), new BigDecimal("8.80"),
                        LocalDate.of(2026, 10, 20)),
                // 新报告期
                new FinancialItem("600519", "2025Q4", "ANNUAL", LocalDate.of(2025, 12, 31),
                        new BigDecimal("5000.0"), new BigDecimal("1500.0"),
                        new BigDecimal("12.0"), new BigDecimal("25.0"),
                        LocalDate.of(2026, 3, 30)),
                // 已有报告期、值变化
                new FinancialItem("600519", "2026Q3", "Q3", LocalDate.of(2026, 9, 30),
                        new BigDecimal("1200.0"), new BigDecimal("320.0"),
                        new BigDecimal("2.7"), new BigDecimal("9.1"),
                        LocalDate.of(2026, 10, 21))));

        assertTrue(service.syncStockFinancials(STOCK));
        // 3 条：1 条无变化（不写库）+ 1 条新增 + 1 条变化（更新）
        verify(financialMapper, times(1)).insert(any(StockFinancial.class));
        verify(financialMapper, times(1)).updateById(any(StockFinancial.class));
    }
}
