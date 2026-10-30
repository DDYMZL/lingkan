package com.lingkan.common.util;

/**
 * 计算版本号。任何指标计算口径变化时必须提升版本，保证历史数据可追溯。
 */
public final class CalcVersion {

    public static final String PE_TTM_V1 = "pe_ttm@v1";
    public static final String DIVIDEND_YIELD_TTM_V1 = "dividend_yield_ttm@v1";
    /** v2：PE-TTM 改为累计值→单季值换算 + 四季度连续性校验（spec 010 计算引擎） */
    public static final String PE_TTM_V2 = "pe_ttm@v2";
    /** v2：股息率 TTM 改为实施日期优先 + dividend_id 去重 + 股本换算（spec 010 计算引擎） */
    public static final String DIVIDEND_YIELD_TTM_V2 = "dividend_yield_ttm@v2";
    public static final String PROFIT_YOY_V1 = "net_profit_parent_yoy@v1";
    public static final String NEW_LOW_60D_V1 = "new_low_60d@v1";
    public static final String TECHNICAL_V1 = "technical@v1";

    private CalcVersion() {
    }
}
