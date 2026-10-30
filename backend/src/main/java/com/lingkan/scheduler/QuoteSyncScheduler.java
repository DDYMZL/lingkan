package com.lingkan.scheduler;

import com.lingkan.calculation.StockIndicatorService;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.common.config.QuoteSyncProperties;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.stock.entity.Stock;
import com.lingkan.watchlist.service.WatchlistService;
import com.lingkan.websocket.QuotePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 自选股行情 15 秒轮询：交易时段、共享缓存、变化落库与 WebSocket 推送。 */
@Component
public class QuoteSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteSyncScheduler.class);

    private final QuoteSnapshotService quoteService;
    private final WatchlistService watchlistService;
    private final StockIndicatorService stockIndicatorService;
    private final QuotePushService quotePushService;
    private final TradingSessionChecker tradingSessionChecker;
    private final QuoteSyncProperties properties;
    private final Map<String, BigDecimal> lastPushedPrices = new ConcurrentHashMap<>();

    public QuoteSyncScheduler(QuoteSnapshotService quoteService, WatchlistService watchlistService,
                              StockIndicatorService stockIndicatorService, QuotePushService quotePushService,
                              TradingSessionChecker tradingSessionChecker, QuoteSyncProperties properties) {
        this.quoteService = quoteService;
        this.watchlistService = watchlistService;
        this.stockIndicatorService = stockIndicatorService;
        this.quotePushService = quotePushService;
        this.tradingSessionChecker = tradingSessionChecker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${lingkan.sync.quote-interval-ms:15000}",
            initialDelayString = "${lingkan.sync.initial-delay-ms:30000}")
    public void syncQuotes() {
        syncAt(LocalDateTime.now(TradingSessionChecker.MARKET_ZONE));
    }

    void syncAt(LocalDateTime now) {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.isMarketHoursOnly() && !tradingSessionChecker.isTradingTime(now)) {
            return;
        }
        List<Stock> stocks = watchlistService.listAllFavoriteStocks();
        if (stocks.isEmpty()) {
            return;
        }
        Map<String, Stock> stockByCode = stocks.stream()
                .collect(Collectors.toMap(Stock::getStockCode, Function.identity(), (left, right) -> left));
        lastPushedPrices.keySet().retainAll(stockByCode.keySet());

        int batchSize = properties.batchSizeOrDefault();
        for (int from = 0; from < stocks.size(); from += batchSize) {
            List<Stock> batch = stocks.subList(from, Math.min(from + batchSize, stocks.size()));
            refreshBatch(batch, stockByCode);
        }
    }

    private void refreshBatch(List<Stock> batch, Map<String, Stock> stockByCode) {
        List<String> codes = batch.stream().map(Stock::getStockCode).toList();
        try {
            List<QuoteResult> quotes = quoteService.refreshQuotes(codes);
            if (quotes.isEmpty()) {
                log.debug("本批自选行情无新结果，保留最近有效快照：{} 只", codes.size());
                return;
            }
            int written = quoteService.persistChanged(quotes);
            List<QuoteResult> changed = quotes.stream().filter(this::priceChanged).toList();
            if (!changed.isEmpty() && properties.isWebsocketEnabled()) {
                List<BoardRowDto> updates = new ArrayList<>(changed.size());
                for (QuoteResult quote : changed) {
                    Stock stock = stockByCode.get(quote.stockCode());
                    if (stock != null) {
                        updates.add(stockIndicatorService.resolveQuoteUpdate(stock, quote));
                    }
                }
                quotePushService.publish(updates);
            }
            changed.forEach(quote -> lastPushedPrices.put(quote.stockCode(), quote.latestPrice()));
            log.debug("自选行情批次完成：{} 只，价格变化 {} 只，落库 {} 条",
                    codes.size(), changed.size(), written);
        } catch (Exception ex) {
            log.warn("自选行情批次失败，保留最近有效行情并等待下轮：{} 只", codes.size(), ex);
        }
    }

    private boolean priceChanged(QuoteResult quote) {
        if (quote == null || quote.stockCode() == null) {
            return false;
        }
        BigDecimal previous = lastPushedPrices.get(quote.stockCode());
        if (!lastPushedPrices.containsKey(quote.stockCode())) {
            return true;
        }
        return previous == null ? quote.latestPrice() != null
                : quote.latestPrice() == null || previous.compareTo(quote.latestPrice()) != 0;
    }
}
