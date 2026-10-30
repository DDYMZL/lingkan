package com.lingkan.metrics.service;

import com.lingkan.common.config.QuoteSyncProperties;
import com.lingkan.datasource.provider.ProviderRegistry;
import com.lingkan.datasource.provider.QuoteProvider;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.entity.StockQuote;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.mapper.StockQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteSnapshotServiceTest {

    @Mock
    private ProviderRegistry registry;
    @Mock
    private QuoteProvider provider;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private StockQuoteMapper quoteMapper;

    private QuoteSnapshotService service;

    @BeforeEach
    void setUp() {
        QuoteSyncProperties properties = new QuoteSyncProperties();
        properties.setQuoteCacheTtlSeconds(60);
        properties.setQuoteStaleAfterSeconds(90);
        service = new QuoteSnapshotService(registry, stockMapper, quoteMapper, properties);
    }

    @Test
    @DisplayName("不同批次按股票代码共享缓存，强制刷新才再次请求上游")
    void sharesCacheAcrossBatches() {
        when(registry.findQuoteProvider("tencent")).thenReturn(Optional.of(provider));
        when(provider.fetchQuotes(anyList())).thenAnswer(invocation -> {
            List<String> codes = invocation.getArgument(0);
            return codes.stream().map(code -> quote(code, new BigDecimal("10"))).toList();
        });

        service.getQuotes(List.of("000001", "600000"));
        service.getQuotes(List.of("600000"));
        service.getQuotes(List.of("000001"));
        verify(provider, times(1)).fetchQuotes(anyList());

        service.refreshQuotes(List.of("600000"));
        verify(provider, times(2)).fetchQuotes(anyList());
    }

    @Test
    @DisplayName("上游失败时读取最近持久化行情并识别为过期")
    void fallsBackToPersistedQuote() {
        when(registry.findQuoteProvider("tencent")).thenReturn(Optional.of(provider));
        when(provider.fetchQuotes(anyList())).thenReturn(List.of());
        Stock stock = stock(1L, "600519");
        when(stockMapper.selectList(any())).thenReturn(List.of(stock));
        StockQuote persisted = new StockQuote();
        persisted.setStockId(1L);
        persisted.setSourceCode("tencent");
        persisted.setLatestPrice(new BigDecimal("1400"));
        persisted.setFetchTime(LocalDateTime.now().minusMinutes(5));
        when(quoteMapper.selectList(any())).thenReturn(List.of(persisted));

        List<QuoteResult> result = service.getQuotes(List.of("600519"));

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).latestPrice().compareTo(new BigDecimal("1400")));
        assertTrue(service.isStale(result.get(0)));
    }

    @Test
    @DisplayName("持久化时 BigDecimal 标度不同但现价相同也跳过写库")
    void persistsOnlyActualPriceChanges() {
        Stock stock = stock(1L, "600519");
        when(stockMapper.selectOne(any())).thenReturn(stock);
        StockQuote existing = new StockQuote();
        existing.setId(9L);
        existing.setStockId(1L);
        existing.setSourceCode("tencent");
        existing.setLatestPrice(new BigDecimal("10.00"));
        when(quoteMapper.selectOne(any())).thenReturn(existing);

        int unchanged = service.persistChanged(List.of(quote("600519", new BigDecimal("10.0"))));
        assertEquals(0, unchanged);
        verify(quoteMapper, never()).updateById(any(StockQuote.class));

        int changed = service.persistChanged(List.of(quote("600519", new BigDecimal("10.01"))));
        assertEquals(1, changed);
        verify(quoteMapper, times(1)).updateById(any(StockQuote.class));
    }

    private static Stock stock(Long id, String code) {
        Stock stock = new Stock();
        stock.setId(id);
        stock.setStockCode(code);
        stock.setStockName(code);
        return stock;
    }

    private static QuoteResult quote(String code, BigDecimal price) {
        return new QuoteResult(code, code, "tencent", price, null, null, null, null, null,
                null, null, null, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now());
    }
}
