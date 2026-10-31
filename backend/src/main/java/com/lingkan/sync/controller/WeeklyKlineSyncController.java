package com.lingkan.sync.controller;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.stock.entity.Stock;
import com.lingkan.sync.dto.StockSyncRequest;
import com.lingkan.sync.dto.SyncAcceptVo;
import com.lingkan.sync.service.WeeklyKlineSyncService;
import com.lingkan.watchlist.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 周线手动同步接口（spec 010 §6）。
 *
 * <p>提交即返回受理结果，实际采集在后台线程池执行；
 * 未传 stockCodes 或为空时同步全部自选股。看板接口只读本地周线表，
 * 首次部署或新增自选后需要靠本接口（或定时任务/添加自选触发）把周线补齐。
 */
@Tag(name = "周线同步")
@RestController
@RequestMapping("/api/v1/sync")
public class WeeklyKlineSyncController {

    private final WeeklyKlineSyncService syncService;
    private final WatchlistService watchlistService;

    public WeeklyKlineSyncController(WeeklyKlineSyncService syncService,
                                     WatchlistService watchlistService) {
        this.syncService = syncService;
        this.watchlistService = watchlistService;
    }

    @Operation(summary = "手动触发自选股周线同步（异步）")
    @PostMapping("/weekly-kline")
    public ApiResponse<SyncAcceptVo> sync(@RequestBody(required = false) StockSyncRequest request) {
        List<String> codes = request == null ? null : request.stockCodes();
        if (codes == null || codes.isEmpty()) {
            codes = watchlistService.listAllFavoriteStocks().stream()
                    .map(Stock::getStockCode)
                    .toList();
        }
        return ApiResponse.ok(syncService.submitAll(codes, "MANUAL"));
    }
}
