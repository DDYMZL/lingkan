package com.lingkan.metrics;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.metrics.dto.MetricCategoryGroupDto;
import com.lingkan.metrics.dto.MetricDefinitionDto;
import com.lingkan.metrics.entity.MetricSourceBinding;
import com.lingkan.metrics.service.MetricDefinitionService;
import com.lingkan.datasource.service.DataSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "指标")
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {

    private final MetricDefinitionService definitionService;
    private final DataSourceService dataSourceService;

    public MetricController(MetricDefinitionService definitionService,
                            DataSourceService dataSourceService) {
        this.definitionService = definitionService;
        this.dataSourceService = dataSourceService;
    }

    @Operation(summary = "全部指标定义")
    @GetMapping
    public ApiResponse<List<MetricDefinitionDto>> list() {
        return ApiResponse.ok(definitionService.listAll());
    }

    @Operation(summary = "按分类聚合的指标定义")
    @GetMapping("/categories")
    public ApiResponse<List<MetricCategoryGroupDto>> categories() {
        return ApiResponse.ok(definitionService.listByCategory());
    }

    @Operation(summary = "单指标定义")
    @GetMapping("/{metricCode}")
    public ApiResponse<MetricDefinitionDto> get(@PathVariable String metricCode) {
        return ApiResponse.ok(definitionService.get(metricCode));
    }

    @Operation(summary = "指标绑定的数据源")
    @GetMapping("/{metricCode}/sources")
    public ApiResponse<List<MetricSourceBinding>> sources(@PathVariable String metricCode) {
        definitionService.get(metricCode);
        return ApiResponse.ok(dataSourceService.bindingsOf(metricCode));
    }

    @Operation(summary = "切换指标主数据源")
    @PutMapping("/{metricCode}/sources")
    public ApiResponse<List<MetricSourceBinding>> switchPrimary(@PathVariable String metricCode,
                                                                @RequestBody SwitchPrimaryRequest request) {
        definitionService.get(metricCode);
        List<MetricSourceBinding> bindings = dataSourceService.bindingsOf(metricCode);
        for (MetricSourceBinding binding : bindings) {
            binding.setIsPrimary(binding.getProviderCode().equals(request.providerCode()));
        }
        dataSourceService.saveBindings(bindings);
        return ApiResponse.ok(dataSourceService.bindingsOf(metricCode));
    }

    public record SwitchPrimaryRequest(String providerCode) {
    }
}
