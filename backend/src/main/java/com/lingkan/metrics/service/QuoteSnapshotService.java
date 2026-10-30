package com.lingkan.metrics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lingkan.datasource.provider.ProviderRegistry;
import com.lingkan.datasource.provider.QuoteProvider;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.entity.StockQuote;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.mapper.StockQuoteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实时行情聚合服务。
 *
 * <p>职责：
 * <ol>
 *   <li>按需从已启用的批量行情数据源拉取最新行情（默认腾讯，可切换）；</li>
 *   <li>短时缓存，避免小程序频繁刷新打爆上游；</li>
 *   <li>落库到 stock_quotes（按 stock_id + source_code 唯一），保留来源与采集时间。</li>
 * </ol>
 */
@Service
public class QuoteSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotService.class);

    /** 行情缓存：秒级，避免重复请求同一批股票 */
    private final Cache<String, List<QuoteResult>> quoteCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(20))
            .maximumSize(200)
            .build();

    private final ProviderRegistry registry;
    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;

    /** 首选批量行情源，可通过配置切换 */
    private String preferredQuoteProvider = "tencent";

    public QuoteSnapshotService(ProviderRegistry registry, StockMapper stockMapper,
                                StockQuoteMapper quoteMapper) {
        this.registry = registry;
        this.stockMapper = stockMapper;
        this.quoteMapper = quoteMapper;
    }

    public List<QuoteResult> getQuotes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(stockCodes));
        String cacheKey = String.join(",", distinct);
        List<QuoteResult> cached = quoteCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<QuoteResult> quotes = fetchFromProviders(distinct);
        if (!quotes.isEmpty()) {
            quoteCache.put(cacheKey, quotes);
        }
        return quotes;
    }

    public Optional<QuoteResult> getQuote(String stockCode) {
        return getQuotes(List.of(stockCode)).stream().findFirst();
    }

    private List<QuoteResult> fetchFromProviders(List<String> stockCodes) {
        Optional<QuoteProvider> preferred = registry.findQuoteProvider(preferredQuoteProvider);
        if (preferred.isPresent()) {
            List<QuoteResult> result = preferred.get().fetchQuotes(stockCodes);
            if (!result.isEmpty()) {
                return result;
            }
            log.warn("首选行情源 {} 无返回，尝试其他批量行情源", preferredQuoteProvider);
        }
        for (var provider : registry.all()) {
            if (provider instanceof QuoteProvider quoteProvider
                    && !quoteProvider.getProviderCode().equals(preferredQuoteProvider)) {
                List<QuoteResult> result = quoteProvider.fetchQuotes(stockCodes);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    /**
     * 落库最新行情。仅写入已存在于 stocks 表的股票，避免产生孤立行情记录。
     */
    public int persist(List<QuoteResult> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return 0;
        }
        Map<String, Long> stockIdByCode = resolveStockIds(
                quotes.stream().map(QuoteResult::stockCode).toList());
        int written = 0;
        for (QuoteResult quote : quotes) {
            Long stockId = stockIdByCode.get(quote.stockCode());
            if (stockId == null) {
                continue;
            }
            StockQuote existing = quoteMapper.selectOne(Wrappers.<StockQuote>lambdaQuery()
                    .eq(StockQuote::getStockId, stockId)
                    .eq(StockQuote::getSourceCode, quote.sourceCode()));
            StockQuote entity = existing == null ? new StockQuote() : existing;
            entity.setStockId(stockId);
            entity.setSourceCode(quote.sourceCode());
            entity.setLatestPrice(quote.latestPrice());
            entity.setChangePercent(quote.changePercent());
            entity.setChangeAmount(quote.changeAmount());
            entity.setOpenPrice(quote.openPrice());
            entity.setHighPrice(quote.highPrice());
            entity.setLowPrice(quote.lowPrice());
            entity.setPreClose(quote.preClose());
            entity.setVolume(quote.volume());
            entity.setAmount(quote.amount());
            entity.setTurnoverRate(quote.turnoverRate());
            entity.setTotalMarketCap(quote.totalMarketCap());
            entity.setDataTime(quote.dataTime());
            entity.setFetchTime(quote.fetchTime() == null ? LocalDateTime.now() : quote.fetchTime());
            if (existing == null) {
                quoteMapper.insert(entity);
            } else {
                quoteMapper.updateById(entity);
            }
            written++;
        }
        return written;
    }

    private Map<String, Long> resolveStockIds(List<String> stockCodes) {
        Map<String, Long> map = new HashMap<>();
        for (String code : stockCodes) {
            Stock stock = stockMapper.selectOne(
                    Wrappers.<Stock>lambdaQuery().eq(Stock::getStockCode, code).last("LIMIT 1"));
            if (stock != null) {
                map.put(code, stock.getId());
            }
        }
        return map;
    }
}
