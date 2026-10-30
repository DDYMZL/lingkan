package com.lingkan.market.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.stock.entity.StockQuote;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.mapper.StockQuoteMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 市场概览服务。
 *
 * <p>统计口径：以 stock_quotes 中已落库的最新行情为准（每个数据源各一条），
 * 无数据时返回 available=false，而不是返回 0 伪装成"平盘"。
 */
@Service
public class MarketService {

    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;

    public MarketService(StockMapper stockMapper, StockQuoteMapper quoteMapper) {
        this.stockMapper = stockMapper;
        this.quoteMapper = quoteMapper;
    }

    public com.lingkan.market.dto.MarketOverviewDto overview() {
        long total = stockMapper.selectCount(null);
        List<StockQuote> quotes = quoteMapper.selectList(
                Wrappers.<StockQuote>lambdaQuery().isNotNull(StockQuote::getChangePercent));
        if (quotes.isEmpty()) {
            return new com.lingkan.market.dto.MarketOverviewDto(total, 0, 0, 0,
                    null, null, false);
        }
        long up = 0;
        long down = 0;
        long flat = 0;
        LocalDateTime latestDataTime = null;
        LocalDateTime latestFetchTime = null;
        for (StockQuote quote : quotes) {
            int cmp = quote.getChangePercent().signum();
            if (cmp > 0) {
                up++;
            } else if (cmp < 0) {
                down++;
            } else {
                flat++;
            }
            if (quote.getDataTime() != null
                    && (latestDataTime == null || quote.getDataTime().isAfter(latestDataTime))) {
                latestDataTime = quote.getDataTime();
            }
            if (quote.getFetchTime() != null
                    && (latestFetchTime == null || quote.getFetchTime().isAfter(latestFetchTime))) {
                latestFetchTime = quote.getFetchTime();
            }
        }
        return new com.lingkan.market.dto.MarketOverviewDto(total, up, down, flat,
                latestDataTime, latestFetchTime, true);
    }
}
