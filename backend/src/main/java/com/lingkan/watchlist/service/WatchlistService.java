package com.lingkan.watchlist.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.calculation.StockIndicatorService;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.common.response.PageResult;
import com.lingkan.metrics.dto.MetricValueDto;
import com.lingkan.metrics.service.MetricValueService;
import com.lingkan.stock.dto.StockRowDto;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.service.StockService;
import com.lingkan.sync.service.DividendFinancialSyncService;
import com.lingkan.watchlist.dto.BoardResponse;
import com.lingkan.watchlist.entity.UserWatchlist;
import com.lingkan.watchlist.mapper.UserWatchlistMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 自选股服务。
 */
@Service
public class WatchlistService {

    private final UserWatchlistMapper watchlistMapper;
    private final StockMapper stockMapper;
    private final StockService stockService;
    private final MetricValueService metricValueService;
    private final DividendFinancialSyncService dividendFinancialSyncService;
    private final StockIndicatorService stockIndicatorService;

    public WatchlistService(UserWatchlistMapper watchlistMapper, StockMapper stockMapper,
                            StockService stockService, MetricValueService metricValueService,
                            DividendFinancialSyncService dividendFinancialSyncService,
                            StockIndicatorService stockIndicatorService) {
        this.watchlistMapper = watchlistMapper;
        this.stockMapper = stockMapper;
        this.stockService = stockService;
        this.metricValueService = metricValueService;
        this.dividendFinancialSyncService = dividendFinancialSyncService;
        this.stockIndicatorService = stockIndicatorService;
    }

    public PageResult<StockRowDto> list(String userId, int page, int size) {
        List<Stock> ordered = listStocks(userId);
        if (ordered.isEmpty()) {
            return PageResult.empty(page, size);
        }
        Map<String, Map<String, MetricValueDto>> metrics =
                metricValueService.resolveMetricsBatch(ordered, userId);

        List<StockRowDto> rows = ordered.stream()
                .map(s -> new StockRowDto(s.getStockCode(), s.getStockName(), s.getExchange(),
                        s.getIndustry(), Boolean.TRUE.equals(s.getIsSt()),
                        metrics.getOrDefault(s.getStockCode(), Map.of())))
                .toList();

        int from = Math.min((Math.max(page, 1) - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return new PageResult<>(rows.subList(from, to), rows.size(), page, size);
    }

    /** 首页看板：按自选顺序聚合行情、股息率 TTM、PE TTM 与目标价，不分页。 */
    public BoardResponse board(String userId) {
        List<Stock> stocks = listStocks(userId);
        if (stocks.isEmpty()) {
            return BoardResponse.of(List.of());
        }
        return BoardResponse.of(stockIndicatorService.resolveAll(stocks));
    }

    /** 按 sortOrder 返回有效自选股票；已删除的股票静默跳过。 */
    private List<Stock> listStocks(String userId) {
        List<UserWatchlist> items = watchlistMapper.selectList(
                Wrappers.<UserWatchlist>lambdaQuery()
                        .eq(UserWatchlist::getUserId, userId)
                        .orderByAsc(UserWatchlist::getSortOrder));
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> stockIds = items.stream().map(UserWatchlist::getStockId).toList();
        List<Stock> stocks = stockMapper.selectList(
                Wrappers.<Stock>lambdaQuery().in(Stock::getId, stockIds));
        Map<Long, Stock> byId = new java.util.HashMap<>();
        stocks.forEach(s -> byId.put(s.getId(), s));

        List<Stock> ordered = new ArrayList<>();
        for (UserWatchlist item : items) {
            Stock stock = byId.get(item.getStockId());
            if (stock != null) {
                ordered.add(stock);
            }
        }
        return ordered;
    }

    @Transactional
    public void add(String userId, String stockCode, String remark) {
        Stock stock = stockService.requireStock(stockCode);
        UserWatchlist existing = watchlistMapper.selectOne(Wrappers.<UserWatchlist>lambdaQuery()
                .eq(UserWatchlist::getUserId, userId)
                .eq(UserWatchlist::getStockId, stock.getId()));
        if (existing != null) {
            if (remark != null && !remark.isBlank()) {
                existing.setRemark(remark);
                watchlistMapper.updateById(existing);
            }
            return;
        }
        long count = watchlistMapper.selectCount(
                Wrappers.<UserWatchlist>lambdaQuery().eq(UserWatchlist::getUserId, userId));
        UserWatchlist entity = new UserWatchlist();
        entity.setUserId(userId);
        entity.setStockId(stock.getId());
        entity.setSortOrder((int) count);
        entity.setRemark(remark);
        entity.setCreatedAt(LocalDateTime.now());
        watchlistMapper.insert(entity);
        // 添加自选成功后异步即时同步分红/财报（spec 010 §4.3）；失败不影响添加结果
        dividendFinancialSyncService.syncAsync(List.of(stockCode), "WATCHLIST_ADD");
    }

    @Transactional
    public void remove(String userId, String stockCode) {
        Stock stock = stockService.requireStock(stockCode);
        watchlistMapper.delete(Wrappers.<UserWatchlist>lambdaQuery()
                .eq(UserWatchlist::getUserId, userId)
                .eq(UserWatchlist::getStockId, stock.getId()));
    }

    @Transactional
    public int sort(String userId, List<String> stockCodesInOrder) {
        List<Stock> stocks = stockMapper.selectList(Wrappers.<Stock>lambdaQuery()
                .in(Stock::getStockCode, stockCodesInOrder));
        Map<String, Long> idByCode = new java.util.HashMap<>();
        stocks.forEach(s -> idByCode.put(s.getStockCode(), s.getId()));

        List<UserWatchlist> items = watchlistMapper.selectList(
                Wrappers.<UserWatchlist>lambdaQuery().eq(UserWatchlist::getUserId, userId));
        Map<Long, UserWatchlist> byStockId = new java.util.HashMap<>();
        items.forEach(i -> byStockId.put(i.getStockId(), i));

        int index = 0;
        for (String code : stockCodesInOrder) {
            Long stockId = idByCode.get(code);
            UserWatchlist item = stockId == null ? null : byStockId.get(stockId);
            if (item != null) {
                item.setSortOrder(index++);
                watchlistMapper.updateById(item);
            }
        }
        return index;
    }

    @Transactional
    public void updateRemark(String userId, String stockCode, String remark) {
        Stock stock = stockService.requireStock(stockCode);
        UserWatchlist item = watchlistMapper.selectOne(Wrappers.<UserWatchlist>lambdaQuery()
                .eq(UserWatchlist::getUserId, userId)
                .eq(UserWatchlist::getStockId, stock.getId()));
        if (item == null) {
            throw new BizException(ApiCode.BAD_REQUEST, "该股票不在自选中");
        }
        item.setRemark(remark);
        watchlistMapper.updateById(item);
    }
}
