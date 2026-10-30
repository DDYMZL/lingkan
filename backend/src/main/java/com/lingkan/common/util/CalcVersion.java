package com.lingkan.common.util;

/**
 * 计算版本号。任何指标计算口径变化时必须提升版本，保证历史数据可追溯。
 */
public final class CalcVersion {

    public static final String PE_TTM_V1 = "pe_ttm@v1";
    public static final String DIVIDEND_YIELD_TTM_V1 = "dividend_yield_ttm@v1";
    public static final String PROFIT_YOY_V1 = "net_profit_parent_yoy@v1";
    public static final String NEW_LOW_60D_V1 = "new_low_60d@v1";
    public static final String TECHNICAL_V1 = "technical@v1";

    private CalcVersion() {
    }
}
