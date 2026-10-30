package com.lingkan.sync.service;

import com.lingkan.datasource.provider.StockListProvider;
import com.lingkan.datasource.provider.StockListUnavailableException;
import com.lingkan.datasource.provider.impl.EastMoneyStockListProvider;
import com.lingkan.datasource.provider.impl.SinaStockListProvider;
import com.lingkan.sync.StockSyncProperties;
import com.lingkan.sync.dto.StockBasicDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 股票清单取数路由。
 *
 * <p>主数据源为东方财富（含行业字段）。实测 2026-10-30：东方财富 clist 接口在短时间内
 * 连续分页请求后会被上游直接断开连接，此时若中断整个同步，将导致首次初始化无法完成。
 * 因此提供备用数据源（新浪）兜底，并在任务备注中写明本次实际使用的数据源，
 * 保证"数据来源可追溯"。
 */
@Service
public class StockListService {

    private static final Logger log = LoggerFactory.getLogger(StockListService.class);

    private final EastMoneyStockListProvider eastmoney;
    private final SinaStockListProvider sina;
    private final StockSyncProperties properties;

    public StockListService(EastMoneyStockListProvider eastmoney, SinaStockListProvider sina,
                            StockSyncProperties properties) {
        this.eastmoney = eastmoney;
        this.sina = sina;
        this.properties = properties;
    }

    /**
     * 拉取全市场股票清单。
     *
     * @throws StockListUnavailableException 主备数据源均失败时抛出，调用方必须终止全量同步
     */
    public StockListResult fetchAll() {
        List<String> failures = new ArrayList<>();
        for (StockListProvider provider : providers()) {
            try {
                long start = System.currentTimeMillis();
                List<StockBasicDto> stocks = provider.fetchAllStocks();
                validate(stocks, provider.getProviderCode());
                log.info("股票清单获取成功，数据源={}，条数={}，耗时={}ms", provider.getProviderCode(),
                        stocks.size(), System.currentTimeMillis() - start);
                return new StockListResult(stocks, provider.getProviderCode(),
                        failures.isEmpty() ? null : String.join("; ", failures));
            } catch (Exception ex) {
                log.warn("股票清单数据源 {} 获取失败: {}", provider.getProviderCode(), ex.getMessage());
                failures.add(provider.getProviderCode() + ": " + ex.getMessage());
            }
        }
        throw new StockListUnavailableException("全部股票清单数据源均不可用 → " + String.join("; ", failures));
    }

    /** 单只股票补录：优先东方财富快照接口 */
    public Optional<StockBasicDto> fetchOne(String stockCode) {
        StockBasicDto dto = eastmoney.fetchStock(stockCode);
        return Optional.ofNullable(dto);
    }

    private List<StockListProvider> providers() {
        List<StockListProvider> list = new ArrayList<>();
        String primary = properties.getStockList().getProvider();
        String fallback = properties.getStockList().getFallback();
        for (StockListProvider provider : List.of(eastmoney, sina)) {
            if (provider.getProviderCode().equalsIgnoreCase(primary)) {
                list.add(provider);
            }
        }
        for (StockListProvider provider : List.of(eastmoney, sina)) {
            if (fallback != null && !fallback.isBlank()
                    && provider.getProviderCode().equalsIgnoreCase(fallback)
                    && !list.contains(provider)) {
                list.add(provider);
            }
        }
        if (list.isEmpty()) {
            list.add(eastmoney);
        }
        return list;
    }

    private void validate(List<StockBasicDto> stocks, String providerCode) {
        if (stocks == null || stocks.isEmpty()) {
            throw new StockListUnavailableException(providerCode + " 返回空股票清单");
        }
        int min = properties.getStockList().getMinStockCount();
        if (stocks.size() < min) {
            throw new StockListUnavailableException(
                    providerCode + " 股票数量异常：" + stocks.size() + " < " + min);
        }
        long distinct = stocks.stream().map(s -> s.exchange() + ":" + s.stockCode()).distinct().count();
        if (distinct != stocks.size()) {
            throw new StockListUnavailableException(providerCode + " 股票清单存在重复记录");
        }
        long missingName = stocks.stream()
                .filter(s -> s.stockName() == null || s.stockName().isBlank()).count();
        if (missingName > 0) {
            throw new StockListUnavailableException(providerCode + " 存在 " + missingName + " 条缺少股票名称的记录");
        }
    }

    /** 清单结果：股票列表 + 实际数据源 + 主源失败原因（用于任务备注） */
    public record StockListResult(List<StockBasicDto> stocks, String providerCode, String fallbackReason) {
    }
}
