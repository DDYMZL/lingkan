package com.lingkan.datasource.provider.impl;

import com.lingkan.datasource.provider.StockDataProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 已登记但当前不可直接取数的数据源。
 *
 * <p>每个数据源的不可用原因都来自实际验证结论（见 docs/specs/004-data-source/source-research.md），
 * 不做假设、不编造接口。
 */
@Configuration
public class PendingProviderConfig {

    @Bean
    public StockDataProvider cninfoProvider() {
        return new PendingProvider("cninfo", "巨潮资讯",
                "公告查询接口可用（实测 HTTP 200），但返回的是公告列表而非结构化财务指标，"
                        + "财报字段抽取需额外解析流程，待确认后再接入");
    }

    @Bean
    public StockDataProvider leguleguProvider() {
        return new PendingProvider("legulegu", "乐咕乐股",
                "按常见路径实测均返回 404，未取得可用公开接口；历史股息率数据需进一步确认接入方式");
    }

    @Bean
    public StockDataProvider tushareProvider() {
        return new PendingProvider("tushare", "Tushare",
                "接口可达但返回 code=40101（需要 Token）；部分接口需要积分，需先申请 Token 并确认权限");
    }

    @Bean
    public StockDataProvider akshareProvider() {
        return new PendingProvider("akshare", "AKShare 采集器",
                "AKShare 为 Python 库，需以独立采集容器方式接入，尚未部署采集服务");
    }

    @Bean
    public StockDataProvider baostockProvider() {
        return new PendingProvider("baostock", "BaoStock",
                "BaoStock 为 Python SDK（非 HTTP 接口），适合历史行情研究，需经 Python 采集器接入");
    }
}
