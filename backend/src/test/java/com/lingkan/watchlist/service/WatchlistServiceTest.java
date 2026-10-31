package com.lingkan.watchlist.service;

import com.lingkan.calculation.StockIndicatorService;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.metrics.service.MetricValueService;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.service.StockService;
import com.lingkan.sync.service.DividendFinancialSyncService;
import com.lingkan.sync.service.WeeklyKlineSyncService;
import com.lingkan.watchlist.dto.BoardResponse;
import com.lingkan.watchlist.entity.UserWatchlist;
import com.lingkan.watchlist.mapper.UserWatchlistMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 自选服务测试：看板编排（顺序、空状态、行情时间）与新增自选的同步触发。 */
@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @Mock
    private UserWatchlistMapper watchlistMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private StockService stockService;
    @Mock
    private MetricValueService metricValueService;
    @Mock
    private DividendFinancialSyncService dividendFinancialSyncService;
    @Mock
    private WeeklyKlineSyncService weeklyKlineSyncService;
    @Mock
    private StockIndicatorService stockIndicatorService;
    @InjectMocks
    private WatchlistService service;

    @Test
    @DisplayName("空自选返回空看板，不触发行情与指标计算")
    void emptyBoard() {
        when(watchlistMapper.selectList(any())).thenReturn(List.of());
        BoardResponse response = service.board("local-user");
        assertEquals(0, response.count());
        assertEquals(List.of(), response.rows());
        assertNull(response.quoteTime());
        verify(stockIndicatorService, never()).resolveAll(anyList());
    }

    @Test
    @DisplayName("看板按 sortOrder 排序，quoteTime 取各行最新行情时间")
    void orderedBoardAndLatestQuoteTime() {
        UserWatchlist second = watchlistItem(2L);
        UserWatchlist first = watchlistItem(1L);
        when(watchlistMapper.selectList(any())).thenReturn(List.of(second, first));
        Stock stock1 = stock(1L, "600000");
        Stock stock2 = stock(2L, "000001");
        when(stockMapper.selectList(any())).thenReturn(List.of(stock1, stock2));
        LocalDateTime early = LocalDateTime.of(2026, 10, 31, 9, 30);
        LocalDateTime latest = LocalDateTime.of(2026, 10, 31, 9, 31);
        when(stockIndicatorService.resolveAll(anyList())).thenAnswer(invocation -> {
            List<Stock> stocks = invocation.getArgument(0);
            return List.of(row(stocks.get(0).getStockCode(), early),
                    row(stocks.get(1).getStockCode(), latest));
        });

        BoardResponse response = service.board("local-user");

        ArgumentCaptor<List<Stock>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockIndicatorService).resolveAll(captor.capture());
        assertEquals(List.of(2L, 1L), captor.getValue().stream().map(Stock::getId).toList());
        assertEquals(List.of("000001", "600000"),
                response.rows().stream().map(BoardRowDto::stockCode).toList());
        assertEquals(2, response.count());
        assertEquals(latest, response.quoteTime());
    }

    @Test
    @DisplayName("新增自选后立即触发分红财报与周线两条异步同步")
    void addTriggersDividendAndWeeklySync() {
        when(stockService.requireStock("600519")).thenReturn(stock(7L, "600519"));
        when(watchlistMapper.selectOne(any())).thenReturn(null);
        when(watchlistMapper.selectCount(any())).thenReturn(0L);

        service.add("local-user", "600519", "probe");

        verify(watchlistMapper).insert(any(UserWatchlist.class));
        verify(dividendFinancialSyncService).syncAsync(List.of("600519"), "WATCHLIST_ADD");
        verify(weeklyKlineSyncService).syncAsync(List.of("600519"), "WATCHLIST_ADD");
    }

    @Test
    @DisplayName("自选已存在时不重复触发同步")
    void addExistingSkipsSync() {
        when(stockService.requireStock("600519")).thenReturn(stock(7L, "600519"));
        when(watchlistMapper.selectOne(any())).thenReturn(watchlistItem(7L));

        service.add("local-user", "600519", null);

        verify(watchlistMapper, never()).insert(any(UserWatchlist.class));
        verifyNoInteractions(dividendFinancialSyncService, weeklyKlineSyncService);
    }

    private static UserWatchlist watchlistItem(Long stockId) {
        UserWatchlist item = new UserWatchlist();
        item.setStockId(stockId);
        return item;
    }

    private static Stock stock(Long id, String code) {
        Stock stock = new Stock();
        stock.setId(id);
        stock.setStockCode(code);
        return stock;
    }

    /** 周线缺失行：五个周线价格为 null，状态 MISSING，前端渲染 --（禁止用 0 冒充）。 */
    private static BoardRowDto row(String code, LocalDateTime quoteTime) {
        return new BoardRowDto(code, code, "SH", null, null, null, null, null, null,
                null, "N_A",
                null, null, null, null, null, "MISSING", null,
                null, quoteTime, null, null, "STALE");
    }
}
