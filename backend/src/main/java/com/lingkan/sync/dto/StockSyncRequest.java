package com.lingkan.sync.dto;

import java.util.List;

/**
 * 指定股票范围的同步请求体，全市场同步 / 分红财务同步 / 周线同步共用。
 *
 * <p>各手动同步接口对「为空」的处理不同：全市场同步按参数校验拒绝，
 * 分红财务与周线同步则退化为「全部自选股」。
 *
 * @param stockCodes 股票代码列表，如 ["600519", "000858"]
 */
public record StockSyncRequest(List<String> stockCodes) {
}
