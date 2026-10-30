package com.lingkan.datasource.provider;

import com.lingkan.sync.dto.StockBasicDto;

import java.util.List;

/**
 * 股票清单数据源接口。
 *
 * <p>职责边界：只负责"有哪些股票、基础信息是什么"，不负责行情与指标取数。
 * 行情仍由 {@link QuoteProvider} 负责，指标仍由 {@link StockDataProvider} 负责。
 */
public interface StockListProvider {

    /** 数据源编码，与 data_providers.provider_code 一致 */
    String getProviderCode();

    String getProviderName();

    /**
     * 获取全市场 A 股清单（沪市 + 深市 + 北交所）。
     *
     * <p>实现必须完整分页；若任一分页失败导致数据不完整，必须抛出异常，
     * 禁止把不完整结果当正常全量返回。
     */
    List<StockBasicDto> fetchAllStocks();

    /** 获取指定交易所（SH / SZ / BJ）股票清单 */
    List<StockBasicDto> fetchStocksByExchange(String exchange);

    /**
     * 获取单只股票基础信息，用于 SINGLE 模式补录。
     * 数据源无法提供时返回 null。
     */
    StockBasicDto fetchStock(String stockCode);
}
