package com.lingkan.market.dto;

import java.time.LocalDateTime;

/**
 * 市场概览。available=false 表示当前没有可用行情数据，前端不得展示任何估算值。
 */
public record MarketOverviewDto(
        long totalCount,
        long upCount,
        long downCount,
        long flatCount,
        LocalDateTime dataTime,
        LocalDateTime fetchTime,
        boolean available
) {
}
