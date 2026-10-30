package com.lingkan.datasource.provider;

import com.lingkan.datasource.provider.dto.FinancialItem;

import java.util.List;

/**
 * 财务报告数据源能力接口。
 */
public interface FinancialProvider {

    /**
     * 拉取一只股票的历史财务报告（含年报与各季度报）。
     * 接口失败或数据源无记录时返回空列表，绝不返回 null。
     */
    List<FinancialItem> fetchFinancials(String stockCode);
}
