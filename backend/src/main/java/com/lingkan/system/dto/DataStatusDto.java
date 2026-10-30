package com.lingkan.system.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统数据状态。前端用它在首页展示"行情更新时间"和交易状态。
 */
public record DataStatusDto(
        LocalDateTime serverTime,
        boolean tradingDay,
        String marketPhase,
        LocalDateTime lastMarketSyncAt,
        List<CollectionStatus> collections
) {

    public record CollectionStatus(
            String providerCode,
            String providerName,
            String healthStatus,
            LocalDateTime lastSuccessAt,
            LocalDateTime lastFailureAt,
            String message
    ) {
    }
}
