package com.lingkan.datasource.provider;

import com.lingkan.datasource.provider.dto.WeeklyBar;

import java.util.List;

/**
 * 周线 K 线数据源能力接口。
 */
public interface WeeklyKlineProvider {

    /**
     * 拉取一只股票最近 {@code limit} 根周线（按日期升序返回）。
     *
     * <p>接口失败（网络/非 200/解析异常）返回 null（MISSING 语义）；
     * 成功但无 K 线返回空列表，与 {@link DividendProvider} 契约一致。
     *
     * @param stockCode 股票代码（可带交易所后缀，适配器自行清洗）
     * @param limit     请求根数上限，&le; 0 时由适配器用默认值
     */
    List<WeeklyBar> fetchWeeklyKline(String stockCode, int limit);
}
