package com.lingkan.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.common.response.PageResult;
import com.lingkan.metrics.dto.MetricValueDto;
import com.lingkan.metrics.service.MetricValueService;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.datasource.provider.ProviderRegistry;
import com.lingkan.datasource.provider.StockDataProvider;
import com.lingkan.stock.dto.StockDetailDto;
import com.lingkan.stock.dto.StockRowDto;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 股票查询服务。
 *
 * <p>排序/筛选条件中的指标编码做白名单校验，避免拼接 SQL 造成注入。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final Pattern METRIC_CODE_PATTERN = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final StockMapper stockMapper;
    private final MetricValueService metricValueService;
    private final QuoteSnapshotService quoteService;
    private final ProviderRegistry registry;

    public StockService(StockMapper stockMapper, MetricValueService metricValueService,
                        QuoteSnapshotService quoteService, ProviderRegistry registry) {
        this.stockMapper = stockMapper;
        this.metricValueService = metricValueService;
        this.quoteService = quoteService;
        this.registry = registry;
    }

    public PageResult<StockRowDto> page(int page, int size, String keyword, String exchange,
                                       String sortMetric, String sortOrder,
                                       Map<String, String> metricFilters, String userId) {
        QueryWrapper<Stock> query = Wrappers.query();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like("stock_name", kw).or().like("stock_code", kw));
        }
        if (exchange != null && !exchange.isBlank()) {
            query.eq("exchange", exchange);
        }
        applyMetricFilters(query, metricFilters);
        applySort(query, sortMetric, sortOrder);

        IPage<Stock> pageResult = stockMapper.selectPage(new Page<>(Math.max(1, page), clampSize(size)), query);
        List<Stock> records = pageResult.getRecords();
        Map<String, Map<String, MetricValueDto>> metrics = metricValueService
                .resolveMetricsBatch(records, userId);
        List<StockRowDto> rows = new ArrayList<>(records.size());
        for (Stock stock : records) {
            rows.add(new StockRowDto(stock.getStockCode(), stock.getStockName(), stock.getExchange(),
                    stock.getIndustry(), Boolean.TRUE.equals(stock.getIsSt()),
                    metrics.getOrDefault(stock.getStockCode(), Map.of())));
        }
        return new PageResult<>(rows, pageResult.getTotal(), page, clampSize(size));
    }

    public List<StockRowDto> search(String keyword, int limit, String userId) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        List<Stock> stocks = stockMapper.selectList(Wrappers.<Stock>lambdaQuery()
                .and(w -> w.like(Stock::getStockName, kw).or().like(Stock::getStockCode, kw))
                .orderByAsc(Stock::getStockCode)
                .last("LIMIT " + clampSize(limit)));
        Map<String, Map<String, MetricValueDto>> metrics = metricValueService
                .resolveMetricsBatch(stocks, userId);
        return stocks.stream()
                .map(s -> new StockRowDto(s.getStockCode(), s.getStockName(), s.getExchange(),
                        s.getIndustry(), Boolean.TRUE.equals(s.getIsSt()),
                        metrics.getOrDefault(s.getStockCode(), Map.of())))
                .toList();
    }

    public Stock requireStock(String stockCode) {
        Stock stock = stockMapper.selectOne(Wrappers.<Stock>lambdaQuery()
                .eq(Stock::getStockCode, stockCode).last("LIMIT 1"));
        if (stock == null) {
            throw new BizException(ApiCode.STOCK_NOT_FOUND, "股票不存在: " + stockCode);
        }
        return stock;
    }

    public StockDetailDto detail(String stockCode, String userId) {
        Stock stock = requireStock(stockCode);
        Optional<QuoteResult> quote = quoteService.getQuote(stockCode);
        Map<String, MetricValueDto> metrics = metricValueService.resolveMetrics(stock, userId);
        StockDetailDto.Quote quoteDto = quote.map(q -> new StockDetailDto.Quote(
                q.stockCode(), q.latestPrice(), q.changePercent(), q.changeAmount(),
                q.openPrice(), q.highPrice(), q.lowPrice(), q.preClose(), q.volume(), q.amount(),
                q.turnoverRate(), q.totalMarketCap(), q.sourceCode(),
                registry.find(q.sourceCode()).map(StockDataProvider::getProviderName)
                        .orElse(q.sourceCode()),
                q.dataTime(), q.fetchTime())).orElse(null);
        return new StockDetailDto(
                new StockDetailDto.StockBasic(stock.getStockCode(), stock.getStockName(),
                        stock.getExchange(), stock.getMarket(), stock.getBoard(), stock.getIndustry(),
                        stock.getListingDate() == null ? null : stock.getListingDate().toString(),
                        Boolean.TRUE.equals(stock.getIsSt())),
                quoteDto,
                new ArrayList<>(metrics.values()));
    }

    private void applySort(QueryWrapper<Stock> query, String sortMetric, String sortOrder) {
        if (sortMetric == null || sortMetric.isBlank()) {
            query.orderByAsc("stock_code");
            return;
        }
        if (!METRIC_CODE_PATTERN.matcher(sortMetric).matches()) {
            log.warn("非法排序指标编码被忽略: {}", sortMetric);
            query.orderByAsc("stock_code");
            return;
        }
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
        // 排序取 stock_metric_values 中该指标最新一条记录；无数据排最后
        query.last("ORDER BY (SELECT v.normalized_value FROM stock_metric_values v "
                + "WHERE v.stock_id = stocks.id AND v.metric_code = '" + sortMetric + "' "
                + "ORDER BY v.fetch_time DESC LIMIT 1) " + direction + " NULLS LAST");
    }

    private void applyMetricFilters(QueryWrapper<Stock> query, Map<String, String> metricFilters) {
        if (metricFilters == null || metricFilters.isEmpty()) {
            return;
        }
        metricFilters.forEach((metricCode, expression) -> {
            if (!METRIC_CODE_PATTERN.matcher(metricCode).matches() || expression == null) {
                return;
            }
            String[] conditions = expression.split("&");
            for (String condition : conditions) {
                String trimmed = condition.trim();
                if (!trimmed.matches("^(>=|<=|>|<|=)-?\\d+(\\.\\d+)?$")) {
                    continue;
                }
                String operator = trimmed.replaceAll("^(-?\\d+(\\.\\d+)?)$", "=")
                        .replaceAll("[0-9.\\-]", "");
                String number = trimmed.replaceAll("^(>=|<=|>|<|=)", "");
                query.exists("SELECT 1 FROM stock_metric_values v WHERE v.stock_id = stocks.id "
                        + "AND v.metric_code = '" + metricCode + "' AND v.normalized_value "
                        + operator + " " + number);
            }
        });
    }

    private int clampSize(int size) {
        return Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
    }
}
