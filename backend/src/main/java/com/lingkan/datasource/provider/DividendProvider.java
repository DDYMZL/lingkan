package com.lingkan.datasource.provider;

import com.lingkan.datasource.provider.dto.DividendItem;

import java.util.List;

/**
 * 分红送配数据源能力接口。
 */
public interface DividendProvider {

    /**
     * 拉取一只股票的全部分红送配记录（含送转股信息）。
     * 接口失败（网络/解析异常）返回 null（MISSING 语义）；成功但无记录返回空列表。
     */
    List<DividendItem> fetchDividends(String stockCode);
}
