package com.lingkan.sync.dto;

import java.util.List;

/**
 * 指定股票同步请求。
 *
 * @param stockCodes 股票代码列表，如 ["600519", "000858"]
 */
public record StockSyncRequest(List<String> stockCodes) {
}
