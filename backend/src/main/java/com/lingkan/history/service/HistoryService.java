package com.lingkan.history.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.history.dto.HistorySeriesDto;
import com.lingkan.metrics.entity.StockDailyMetric;
import com.lingkan.metrics.mapper.StockDailyMetricMapper;
import com.lingkan.metrics.service.MetricDefinitionService;
import com.lingkan.stock.service.StockService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史指标查询服务。数据来源为 stock_daily_metrics 每日快照。
 */
@Service
public class HistoryService {

    private final StockDailyMetricMapper dailyMetricMapper;
    private final StockService stockService;
    private final MetricDefinitionService definitionService;

    public HistoryService(StockDailyMetricMapper dailyMetricMapper, StockService stockService,
                          MetricDefinitionService definitionService) {
        this.dailyMetricMapper = dailyMetricMapper;
        this.stockService = stockService;
        this.definitionService = definitionService;
    }

    public HistorySeriesDto series(String stockCode, String metricCode, String range) {
        var stock = stockService.requireStock(stockCode);
        var def = definitionService.get(metricCode);
        LocalDate from = switch (range == null ? "1Y" : range) {
            case "3Y" -> LocalDate.now().minusYears(3);
            case "5Y" -> LocalDate.now().minusYears(5);
            case "ALL" -> LocalDate.of(1990, 1, 1);
            default -> LocalDate.now().minusYears(1);
        };
        List<StockDailyMetric> rows = dailyMetricMapper.selectList(
                Wrappers.<StockDailyMetric>lambdaQuery()
                        .eq(StockDailyMetric::getStockId, stock.getId())
                        .eq(StockDailyMetric::getMetricCode, metricCode)
                        .ge(StockDailyMetric::getTradeDate, from)
                        .orderByAsc(StockDailyMetric::getTradeDate));
        List<HistorySeriesDto.Point> points = new ArrayList<>(rows.size());
        String sourceCode = null;
        for (StockDailyMetric row : rows) {
            if (sourceCode == null) {
                sourceCode = row.getSourceCode();
            }
            points.add(new HistorySeriesDto.Point(row.getTradeDate().toString(), row.getMetricValue()));
        }
        return new HistorySeriesDto(stockCode, metricCode, def.metricName(), def.unit(),
                range == null ? "1Y" : range, sourceCode, points);
    }
}
