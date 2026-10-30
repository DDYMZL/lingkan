package com.lingkan.watchlist;

import com.lingkan.common.config.AsyncConfig;
import com.lingkan.common.response.ApiResponse;
import com.lingkan.common.response.PageResult;
import com.lingkan.common.util.UserContext;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.stock.dto.StockRowDto;
import com.lingkan.watchlist.dto.BoardResponse;
import com.lingkan.watchlist.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Tag(name = "自选股")
@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final QuoteSnapshotService quoteService;
    private final Executor dataSyncExecutor;

    @Value("${lingkan.datasource.default-user-id:local-user}")
    private String defaultUserId;

    public WatchlistController(WatchlistService watchlistService, QuoteSnapshotService quoteService,
                               @Qualifier(AsyncConfig.DATA_SYNC_EXECUTOR) Executor dataSyncExecutor) {
        this.watchlistService = watchlistService;
        this.quoteService = quoteService;
        this.dataSyncExecutor = dataSyncExecutor;
    }

    @Operation(summary = "自选股列表")
    @GetMapping
    public ApiResponse<PageResult<StockRowDto>> list(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(watchlistService.list(userId(), page, size));
    }

    @Operation(summary = "首页自选看板")
    @GetMapping("/board")
    public ApiResponse<BoardResponse> board() {
        return ApiResponse.ok(watchlistService.board(userId()));
    }

    @Operation(summary = "加入自选")
    @PostMapping
    public ApiResponse<Map<String, Object>> add(@RequestBody AddRequest request) {
        watchlistService.add(userId(), request.stockCode(), request.remark());
        return ApiResponse.ok(Map.of("stockCode", request.stockCode()));
    }

    @Operation(summary = "移出自选")
    @DeleteMapping("/{stockCode}")
    public ApiResponse<Map<String, Object>> remove(@PathVariable String stockCode) {
        watchlistService.remove(userId(), stockCode);
        return ApiResponse.ok(Map.of("stockCode", stockCode));
    }

    @Operation(summary = "自选股排序")
    @PutMapping("/sort")
    public ApiResponse<Map<String, Object>> sort(@RequestBody SortRequest request) {
        int updated = watchlistService.sort(userId(), request.stockCodes());
        return ApiResponse.ok(Map.of("updated", updated));
    }

    @Operation(summary = "修改自选备注")
    @PutMapping("/{stockCode}/remark")
    public ApiResponse<Map<String, Object>> remark(@PathVariable String stockCode,
                                                  @RequestBody RemarkRequest request) {
        watchlistService.updateRemark(userId(), stockCode, request.remark());
        return ApiResponse.ok(Map.of("stockCode", stockCode));
    }

    @Operation(summary = "刷新自选股行情（异步）")
    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        PageResult<StockRowDto> page = watchlistService.list(userId(), 1, 100);
        List<String> codes = page.list().stream().map(StockRowDto::stockCode).toList();
        String taskId = "watchlist-refresh-" + System.currentTimeMillis();
        dataSyncExecutor.execute(() -> {
            List<QuoteResult> quotes = quoteService.getQuotes(codes);
            quoteService.persist(quotes);
        });
        return ApiResponse.ok(Map.of("taskId", taskId, "accepted", true, "count", codes.size()));
    }

    public record AddRequest(String stockCode, String remark) {
    }

    public record SortRequest(List<String> stockCodes) {
    }

    public record RemarkRequest(String remark) {
    }

    private String userId() {
        return UserContext.get(defaultUserId);
    }
}
