package com.lingkan.sync.controller;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.common.response.PageResult;
import com.lingkan.sync.dto.StockSyncDetailVo;
import com.lingkan.sync.dto.StockSyncRequest;
import com.lingkan.sync.dto.StockSyncTaskVo;
import com.lingkan.sync.dto.SyncSubmitVo;
import com.lingkan.sync.service.StockSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全市场同步管理接口。
 *
 * <p>所有提交类接口都是"创建任务并立即返回"，实际执行在后台线程池，
 * 避免 HTTP 请求等待数十分钟；进度通过任务查询接口轮询。
 */
@Tag(name = "股票同步")
@RestController
@RequestMapping("/api/v1/stocks/sync")
public class StockSyncController {

    private final StockSyncService syncService;

    public StockSyncController(StockSyncService syncService) {
        this.syncService = syncService;
    }

    @Operation(summary = "手动全量同步（股票清单 + 全部行情 + 全部绑定指标）")
    @PostMapping("/full")
    public ApiResponse<SyncSubmitVo> full() {
        return ApiResponse.ok(syncService.submitFull("MANUAL"));
    }

    @Operation(summary = "手动增量同步（更新清单 + 全部行情 + 全部绑定指标）")
    @PostMapping("/incremental")
    public ApiResponse<SyncSubmitVo> incremental() {
        return ApiResponse.ok(syncService.submitIncremental("MANUAL"));
    }

    @Operation(summary = "指定股票同步")
    @PostMapping("/single")
    public ApiResponse<SyncSubmitVo> single(@RequestBody StockSyncRequest request) {
        return ApiResponse.ok(syncService.submitSingle(
                request == null ? null : request.stockCodes()));
    }

    @Operation(summary = "查询同步任务进度")
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<StockSyncTaskVo> task(@PathVariable Long taskId) {
        return ApiResponse.ok(syncService.detail(taskId));
    }

    @Operation(summary = "查询同步任务列表")
    @GetMapping("/tasks")
    public ApiResponse<PageResult<StockSyncTaskVo>> tasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(syncService.list(page, size));
    }

    @Operation(summary = "查询任务失败明细")
    @GetMapping("/tasks/{taskId}/failures")
    public ApiResponse<PageResult<StockSyncDetailVo>> failures(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(syncService.failures(taskId, page, size));
    }

    @Operation(summary = "重试任务失败项（只重试失败数据）")
    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<SyncSubmitVo> retry(@PathVariable Long taskId) {
        return ApiResponse.ok(syncService.retry(taskId));
    }
}
