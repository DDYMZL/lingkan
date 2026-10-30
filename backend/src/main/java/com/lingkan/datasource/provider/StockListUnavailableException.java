package com.lingkan.datasource.provider;

/**
 * 股票清单获取失败（网络异常、分页不完整、数据完整性校验未通过）。
 *
 * <p>调用方必须把本异常视为"本次清单不可信"：全量同步必须中止，
 * 禁止用空列表或残缺列表覆盖数据库中的股票基础信息。
 */
public class StockListUnavailableException extends RuntimeException {

    public StockListUnavailableException(String message) {
        super(message);
    }

    public StockListUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
