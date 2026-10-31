package com.lingkan.scheduler;

import com.lingkan.calculation.StockIndicatorService;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.common.config.QuoteSyncProperties;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.stock.entity.Stock;
import com.lingkan.watchlist.service.WatchlistService;
import com.lingkan.websocket.QuotePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteSyncSchedulerTest {

    @Mock
    private QuoteSnapshotService quoteService;
    @Mock
    private WatchlistService watchlistService;
    @Mock
    private StockIndicatorService stockIndicatorService;
    @Mock
    private QuotePushService quotePushService;
    @Mock
    private TradingSessionChecker tradingSessionChecker;

    private QuoteSyncProperties properties;
    private QuoteSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new QuoteSyncProperties();
        properties.setEnabled(true);
        properties.setMarketHoursOnly(true);
        properties.setQuoteBatchSize(2);
        properties.setWebsocketEnabled(true);
        scheduler = new QuoteSyncScheduler(quoteService, watchlistService, stockIndicatorService,
                quotePushService, tradingSessionChecker, properties);
    }

    @Test
    @DisplayName("非交易时段不读取自选也不请求上游")
    void skipsNonTradingHours() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);
        when(tradingSessionChecker.isTradingTime(now)).thenReturn(false);
        scheduler.syncAt(now);
        verifyNoInteractions(watchlistService, quoteService, stockIndicatorService, quotePushService);
    }

    @Test
    @DisplayName("按批次刷新，只在价格变化时重算并推送")
    void batchesAndPushesOnlyPriceChanges() {
        List<Stock> stocks = List.of(stock("000001"), stock("600000"), stock("600519"));
        when(tradingSessionChecker.isTradingTime(any())).thenReturn(true);
        when(watchlistService.listAllFavoriteStocks()).thenReturn(stocks);
        Map<String, BigDecimal> prices = new HashMap<>();
        stocks.forEach(stock -> prices.put(stock.getStockCode(), new BigDecimal("10.00")));
        when(quoteService.refreshQuotes(anyList())).thenAnswer(invocation -> {
            List<String> codes = invocation.getArgument(0);
            return codes.stream().map(code -> quote(code, prices.get(code))).toList();
        });
        when(quoteService.persistChanged(anyList())).thenReturn(1);
        when(stockIndicatorService.resolveQuoteUpdate(any(Stock.class), any(QuoteResult.class)))
                .thenAnswer(invocation -> {
                    QuoteResult quote = invocation.getArgument(1);
                    return row(quote.stockCode(), quote.latestPrice());
                });
        when(quotePushService.publish(anyList())).thenReturn(1);
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 10, 0);

        scheduler.syncAt(now);
        scheduler.syncAt(now.plusSeconds(15));
        verify(quotePushService, times(2)).publish(anyList());
        verify(quotePushService, never()).publish(List.of());

        prices.put("000001", new BigDecimal("11.00"));
        scheduler.syncAt(now.plusSeconds(30));

        ArgumentCaptor<List<BoardRowDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(quotePushService, times(3)).publish(captor.capture());
        assertEquals(List.of("000001"),
                captor.getValue().stream().map(BoardRowDto::stockCode).toList());
        verify(quoteService, times(6)).refreshQuotes(anyList());
    }

    private static Stock stock(String code) {
        Stock stock = new Stock();
        stock.setStockCode(code);
        stock.setStockName(code);
        return stock;
    }

    private static QuoteResult quote(String code, BigDecimal price) {
        return new QuoteResult(code, code, "tencent", price, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                LocalDateTime.of(2026, 10, 30, 10, 0), LocalDateTime.of(2026, 10, 30, 10, 0));
    }

    private static BoardRowDto row(String code, BigDecimal price) {
        return new BoardRowDto(code, code, "SH", null, price, null, null, null, null,
                null, "N_A", null, null, null, null, "NORMAL");
    }
}
