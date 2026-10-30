package com.lingkan.market;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.market.dto.MarketOverviewDto;
import com.lingkan.market.service.MarketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "市场")
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @Operation(summary = "市场概览")
    @GetMapping("/overview")
    public ApiResponse<MarketOverviewDto> overview() {
        return ApiResponse.ok(marketService.overview());
    }

    @Operation(summary = "触发全市场刷新（异步任务）")
    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        // 全市场行情采集属于定时任务范畴，需在确认数据源方案并开启 lingkan.sync.enabled 后生效
        return ApiResponse.ok(Map.of(
                "taskId", "market-refresh-" + System.currentTimeMillis(),
                "accepted", false,
                "message", "全市场采集任务尚未启用，请先确认数据源方案并开启同步开关"));
    }
}
