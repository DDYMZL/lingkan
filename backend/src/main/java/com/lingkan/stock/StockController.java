package com.lingkan.stock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.common.config.AsyncConfig;
import com.lingkan.common.response.ApiResponse;
import com.lingkan.common.response.PageResult;
import com.lingkan.common.util.UserContext;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.metrics.dto.MetricSourceComparisonDto;
import com.lingkan.metrics.dto.MetricValueDto;
import com.lingkan.metrics.service.MetricValueService;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.stock.dto.StockDetailDto;
import com.lingkan.stock.dto.StockRowDto;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.service.StockService;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.mapper.StockDividendMapper;
import com.lingkan.history.dto.HistorySeriesDto;
import com.lingkan.history.service.HistoryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Tag(name = "股票")
@RestController
@RequestMapping("/api/v1/stocks")
public class StockController {

    private final StockService stockService;
    private final MetricValueService metricValueService;
    private final QuoteSnapshotService quoteService;
    private final HistoryService historyService;
    private final StockDividendMapper dividendMapper;
    private final ObjectMapper objectMapper;
    private final Executor dataSyncExecutor;

    @Value("${lingkan.datasource.default-user-id:local-user}")
    private String defaultUserId;

    public StockController(StockService stockService, MetricValueService metricValueService,
                           QuoteSnapshotService quoteService, HistoryService historyService,
                           StockDividendMapper dividendMapper, ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Qualifier(AsyncConfig.DATA_SYNC_EXECUTOR)
                           Executor dataSyncExecutor) {
        this.stockService = stockService;
        this.metricValueService = metricValueService;
        this.quoteService = quoteService;
        this.historyService = historyService;
        this.dividendMapper = dividendMapper;
        this.objectMapper = objectMapper;
        this.dataSyncExecutor = dataSyncExecutor;
    }

    @Operation(summary = "全市场股票列表（字段由指标配置决定）")
    @GetMapping
    public ApiResponse<PageResult<StockRowDto>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String sortMetric,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) String metricFilters) {
        return ApiResponse.ok(stockService.page(page, size, keyword, exchange, sortMetric,
                sortOrder, parseFilters(metricFilters), userId()));
    }

    @Operation(summary = "股票搜索")
    @GetMapping("/search")
    public ApiResponse<List<StockRowDto>> search(@RequestParam String keyword,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(stockService.search(keyword, limit, userId()));
    }

    @Operation(summary = "股票详情")
    @GetMapping("/{stockCode}")
    public ApiResponse<StockDetailDto> detail(@PathVariable String stockCode) {
        return ApiResponse.ok(stockService.detail(stockCode, userId()));
    }

    @Operation(summary = "单只股票实时行情")
    @GetMapping("/{stockCode}/quote")
    public ApiResponse<Object> quote(@PathVariable String stockCode) {
        Stock stock = stockService.requireStock(stockCode);
        return ApiResponse.ok(quoteService.getQuote(stock.getStockCode())
                .map(q -> (Object) q)
                .orElse(Map.of("stockCode", stockCode, "available", false, "message", "暂无行情数据")));
    }

    @Operation(summary = "单只股票全部启用指标")
    @GetMapping("/{stockCode}/metrics")
    public ApiResponse<List<MetricValueDto>> metrics(@PathVariable String stockCode) {
        Stock stock = stockService.requireStock(stockCode);
        return ApiResponse.ok(new ArrayList<>(
                metricValueService.resolveMetrics(stock, userId()).values()));
    }

    @Operation(summary = "指标历史序列")
    @GetMapping("/{stockCode}/history")
    public ApiResponse<HistorySeriesDto> history(@PathVariable String stockCode,
                                                 @RequestParam String metricCode,
                                                 @RequestParam(defaultValue = "1Y") String range) {
        return ApiResponse.ok(historyService.series(stockCode, metricCode, range));
    }

    @Operation(summary = "多指标历史序列")
    @GetMapping("/{stockCode}/history/batch")
    public ApiResponse<List<HistorySeriesDto>> historyBatch(@PathVariable String stockCode,
                                                            @RequestParam String metricCodes,
                                                            @RequestParam(defaultValue = "1Y") String range) {
        List<HistorySeriesDto> result = new ArrayList<>();
        for (String code : metricCodes.split(",")) {
            if (!code.isBlank()) {
                result.add(historyService.series(stockCode, code.trim(), range));
            }
        }
        return ApiResponse.ok(result);
    }

    @Operation(summary = "分红记录")
    @GetMapping("/{stockCode}/dividends")
    public ApiResponse<List<StockDividend>> dividends(@PathVariable String stockCode) {
        Stock stock = stockService.requireStock(stockCode);
        return ApiResponse.ok(dividendMapper.selectList(Wrappers.<StockDividend>lambdaQuery()
                .eq(StockDividend::getStockId, stock.getId())
                .orderByDesc(StockDividend::getFiscalYear)));
    }

    @Operation(summary = "单指标多数据源对比")
    @GetMapping("/{stockCode}/metrics/{metricCode}/sources")
    public ApiResponse<MetricSourceComparisonDto> sources(@PathVariable String stockCode,
                                                          @PathVariable String metricCode) {
        Stock stock = stockService.requireStock(stockCode);
        return ApiResponse.ok(metricValueService.compare(stock, metricCode, userId(),
                stock.getStockName()));
    }

    @Operation(summary = "手动刷新单只股票数据（异步）")
    @PostMapping("/{stockCode}/refresh")
    public ApiResponse<Map<String, Object>> refresh(@PathVariable String stockCode) {
        Stock stock = stockService.requireStock(stockCode);
        String taskId = "refresh-" + stockCode + "-" + System.currentTimeMillis();
        dataSyncExecutor.execute(() -> {
            List<QuoteResult> quotes = quoteService.getQuotes(List.of(stock.getStockCode()));
            quoteService.persist(quotes);
        });
        return ApiResponse.ok(Map.of("taskId", taskId, "accepted", true));
    }

    private String userId() {
        return UserContext.get(defaultUserId);
    }

    private Map<String, String> parseFilters(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }
}
