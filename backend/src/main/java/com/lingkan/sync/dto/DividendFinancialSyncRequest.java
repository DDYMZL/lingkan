package com.lingkan.sync.dto;

import java.util.List;

/**
 * 手动触发分红/财务同步的请求体。
 * stockCodes 为空或缺失时同步全部自选股。
 */
public record DividendFinancialSyncRequest(List<String> stockCodes) {
}
