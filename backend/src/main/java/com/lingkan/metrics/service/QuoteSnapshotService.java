package com.lingkan.metrics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lingkan.common.config.QuoteSyncProperties;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 实时行情聚合、按股票共享缓存与最近快照持久化。 */
@Service
public class QuoteSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotService.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    /** 按股票代码共享，调度批次和看板全量请求可复用同一份新鲜行情。 */
    private final Cache<String, QuoteResult> quoteCache;

    private final ProviderRegistry registry;
    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;
    private final QuoteSyncProperties properties;

    /** 首选批量行情源，可通过配置切换。 */
    private String preferredQuoteProvider = "tencent";

    public QuoteSnapshotService(ProviderRegistry registry, StockMapper stockMapper,
                                StockQuoteMapper quoteMapper, QuoteSyncProperties properties) {
        this.registry = registry;
        this.stockMapper = stockMapper;
        this.quoteMapper = quoteMapper;
        this.properties = properties;
        this.quoteCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlOrDefault()))
                .maximumSize(10000)
                .build();
    }

    /** 优先使用新鲜缓存；上游失败时以持久化快照兜底，不用空值覆盖旧行情。 */
    public List<QuoteResult> getQuotes(List<String> stockCodes) {
        List<String> distinct = distinctCodes(stockCodes);
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<String, QuoteResult> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String code : distinct) {
            QuoteResult cached = quoteCache.getIfPresent(code);
            if (cached != null) {
                resolved.put(code, cached);
            } else {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            for (QuoteResult quote : refreshQuotes(missing)) {
                resolved.put(quote.stockCode(), quote);
            }
            List<String> unresolved = missing.stream().filter(code -> !resolved.containsKey(code)).toList();
            if (!unresolved.isEmpty()) {
                resolved.putAll(loadPersistedQuotes(unresolved));
            }
        }
        return distinct.stream().map(resolved::get).filter(java.util.Objects::nonNull).toList();
    }

    /** 调度专用：绕过新鲜缓存拉取并共享写缓存，不读取持久化快照冒充新结果。 */
    public List<QuoteResult> refreshQuotes(List<String> stockCodes) {
        List<String> distinct = distinctCodes(stockCodes);
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<QuoteResult> quotes = fetchFromProviders(distinct);
        for (QuoteResult quote : quotes) {
            if (quote != null && quote.stockCode() != null && !quote.stockCode().isBlank()) {
                quoteCache.put(quote.stockCode(), quote);
            }
        }
        return quotes;
    }

    public Optional<QuoteResult> getQuote(String stockCode) {
        return getQuotes(List.of(stockCode)).stream().findFirst();
    }

    /** 最近持久化快照仅在超过阈值后视为过期；新拉取但 fetchTime 缺失不按过期处理。 */
    public boolean isStale(QuoteResult quote) {
        if (quote == null) {
            return true;
        }
        LocalDateTime fetchTime = quote.fetchTime();
        return fetchTime != null && fetchTime.isBefore(
                LocalDateTime.now(MARKET_ZONE).minusSeconds(properties.staleAfterOrDefault()));
    }

    private List<QuoteResult> fetchFromProviders(List<String> stockCodes) {
        Optional<QuoteProvider> preferred = registry.findQuoteProvider(preferredQuoteProvider);
        if (preferred.isPresent()) {
            List<QuoteResult> result = fetchQuietly(preferred.get(), stockCodes);
            if (!result.isEmpty()) {
                return result;
            }
            log.warn("首选行情源 {} 无返回，尝试其他批量行情源", preferredQuoteProvider);
        }
        for (var provider : registry.all()) {
            if (provider instanceof QuoteProvider quoteProvider
                    && !quoteProvider.getProviderCode().equals(preferredQuoteProvider)) {
                List<QuoteResult> result = fetchQuietly(quoteProvider, stockCodes);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    private List<QuoteResult> fetchQuietly(QuoteProvider provider, List<String> stockCodes) {
        try {
            List<QuoteResult> result = provider.fetchQuotes(stockCodes);
            return result == null ? List.of() : result;
        } catch (Exception ex) {
            log.warn("行情源 {} 拉取失败，保留最近有效行情", provider.getProviderCode(), ex);
            return List.of();
        }
    }

    /** 原手动刷新语义：每次有效结果均落库。 */
    public int persist(List<QuoteResult> quotes) {
        return persistInternal(quotes, false);
    }

    /** 调度专用：已有相同现价时跳过写库，新股票或价格变化才落库。 */
    public int persistChanged(List<QuoteResult> quotes) {
        return persistInternal(quotes, true);
    }

    private int persistInternal(List<QuoteResult> quotes, boolean onlyPriceChanges) {
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
            if (onlyPriceChanges && existing != null
                    && sameNumber(existing.getLatestPrice(), quote.latestPrice())) {
                continue;
            }
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

    private Map<String, QuoteResult> loadPersistedQuotes(List<String> stockCodes) {
        List<Stock> stocks = stockMapper.selectList(
                Wrappers.<Stock>lambdaQuery().in(Stock::getStockCode, stockCodes));
        if (stocks.isEmpty()) {
            return Map.of();
        }
        Map<Long, Stock> stockById = new HashMap<>();
        stocks.forEach(stock -> stockById.put(stock.getId(), stock));
        List<StockQuote> persisted = quoteMapper.selectList(Wrappers.<StockQuote>lambdaQuery()
                .in(StockQuote::getStockId, stockById.keySet()));
        Map<String, QuoteResult> result = new LinkedHashMap<>();
        for (StockQuote quote : persisted) {
            Stock stock = stockById.get(quote.getStockId());
            if (stock == null) {
                continue;
            }
            QuoteResult candidate = toQuoteResult(stock, quote);
            result.merge(stock.getStockCode(), candidate, this::selectPreferredSnapshot);
        }
        return result;
    }

    private QuoteResult selectPreferredSnapshot(QuoteResult left, QuoteResult right) {
        boolean leftPreferred = preferredQuoteProvider.equals(left.sourceCode());
        boolean rightPreferred = preferredQuoteProvider.equals(right.sourceCode());
        if (leftPreferred != rightPreferred) {
            return leftPreferred ? left : right;
        }
        LocalDateTime leftTime = left.fetchTime();
        LocalDateTime rightTime = right.fetchTime();
        if (leftTime == null) {
            return right;
        }
        if (rightTime == null) {
            return left;
        }
        return leftTime.isAfter(rightTime) ? left : right;
    }

    private QuoteResult toQuoteResult(Stock stock, StockQuote quote) {
        return new QuoteResult(stock.getStockCode(), stock.getStockName(), quote.getSourceCode(),
                quote.getLatestPrice(), quote.getChangePercent(), quote.getChangeAmount(),
                quote.getOpenPrice(), quote.getHighPrice(), quote.getLowPrice(), quote.getPreClose(),
                quote.getVolume(), quote.getAmount(), quote.getTurnoverRate(), quote.getTotalMarketCap(),
                null, null, null, quote.getDataTime(), quote.getFetchTime());
    }

    private Map<String, Long> resolveStockIds(List<String> stockCodes) {
        Map<String, Long> map = new HashMap<>();
        for (String code : distinctCodes(stockCodes)) {
            Stock stock = stockMapper.selectOne(
                    Wrappers.<Stock>lambdaQuery().eq(Stock::getStockCode, code).last("LIMIT 1"));
            if (stock != null) {
                map.put(code, stock.getId());
            }
        }
        return map;
    }

    private List<String> distinctCodes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String code : stockCodes) {
            if (code != null && !code.isBlank()) {
                distinct.add(code.trim());
            }
        }
        return new ArrayList<>(distinct);
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
