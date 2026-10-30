package com.lingkan.datasource;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.datasource.dto.ProviderDto;
import com.lingkan.datasource.dto.ProviderTestResult;
import com.lingkan.datasource.entity.DataSyncLog;
import com.lingkan.datasource.service.DataSourceService;
import com.lingkan.metrics.entity.MetricSourceBinding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "数据源")
@RestController
@RequestMapping("/api/v1/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Operation(summary = "数据源列表")
    @GetMapping
    public ApiResponse<List<ProviderDto>> list() {
        return ApiResponse.ok(dataSourceService.list());
    }

    @Operation(summary = "全部指标-数据源绑定")
    @GetMapping("/bindings")
    public ApiResponse<List<MetricSourceBinding>> bindings() {
        return ApiResponse.ok(dataSourceService.allBindings());
    }

    @Operation(summary = "数据源详情")
    @GetMapping("/{providerCode}")
    public ApiResponse<ProviderDto> get(@PathVariable String providerCode) {
        return ApiResponse.ok(dataSourceService.get(providerCode));
    }

    @Operation(summary = "连通性测试（后端发起真实请求）")
    @PostMapping("/{providerCode}/test")
    public ApiResponse<ProviderTestResult> test(@PathVariable String providerCode) {
        return ApiResponse.ok(dataSourceService.test(providerCode));
    }

    @Operation(summary = "启用数据源")
    @PostMapping("/{providerCode}/enable")
    public ApiResponse<ProviderDto> enable(@PathVariable String providerCode) {
        return ApiResponse.ok(dataSourceService.enable(providerCode));
    }

    @Operation(summary = "停用数据源")
    @PostMapping("/{providerCode}/disable")
    public ApiResponse<ProviderDto> disable(@PathVariable String providerCode) {
        return ApiResponse.ok(dataSourceService.disable(providerCode));
    }

    @Operation(summary = "采集日志")
    @GetMapping("/{providerCode}/logs")
    public ApiResponse<List<DataSyncLog>> logs(@PathVariable String providerCode,
                                               @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(dataSourceService.logs(providerCode, limit));
    }
}
