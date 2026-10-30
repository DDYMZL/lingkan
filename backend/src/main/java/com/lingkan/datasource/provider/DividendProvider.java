package com.lingkan.datasource.provider;

import com.lingkan.datasource.provider.dto.DividendItem;

import java.util.List;

/**
 * 分红送配数据源能力接口。
 */
public interface DividendProvider {

    /**
     * 拉取一只股票的全部分红送配记录（含送转股信息）。
     * 接口失败或数据源无记录时返回空列表，绝不返回 null。
     */
    List<DividendItem> fetchDividends(String stockCode);
}
