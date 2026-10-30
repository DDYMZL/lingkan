package com.lingkan.sync.controller;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.sync.dto.DividendFinancialSyncRequest;
import com.lingkan.sync.dto.SyncAcceptVo;
import com.lingkan.sync.service.DividendFinancialSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分红/财务手动同步接口（spec 010 §6）。
 *
 * <p>提交即返回受理结果，实际采集在后台线程池执行；
 * 未传 stockCodes 或为空时同步全部自选股。
 */
@Tag(name = "分红/财务同步")
@RestController
@RequestMapping("/api/v1/sync")
public class DividendFinancialSyncController {

    private final DividendFinancialSyncService syncService;

    public DividendFinancialSyncController(DividendFinancialSyncService syncService) {
        this.syncService = syncService;
    }

    @Operation(summary = "手动触发分红/财务同步（异步）")
    @PostMapping("/dividend-financial")
    public ApiResponse<SyncAcceptVo> sync(@RequestBody(required = false) DividendFinancialSyncRequest request) {
        List<String> codes = request == null ? null : request.stockCodes();
        if (codes == null || codes.isEmpty()) {
            return ApiResponse.ok(syncService.submitForWatchlist("MANUAL"));
        }
        return ApiResponse.ok(syncService.submitForStocks(codes, "MANUAL"));
    }
}
