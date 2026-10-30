package com.lingkan.datasource.provider;

import java.util.List;

/**
 * 支持批量行情查询的数据源。
 */
public interface QuoteProvider extends StockDataProvider {

    List<QuoteResult> fetchQuotes(List<String> stockCodes);
}
