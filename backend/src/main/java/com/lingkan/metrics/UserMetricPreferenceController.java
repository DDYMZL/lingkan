package com.lingkan.metrics;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.common.util.UserContext;
import com.lingkan.metrics.entity.UserMetricPreference;
import com.lingkan.metrics.service.MetricPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "用户指标偏好")
@RestController
@RequestMapping("/api/v1/user/metric-preferences")
public class UserMetricPreferenceController {

    private final MetricPreferenceService preferenceService;

    @Value("${lingkan.datasource.default-user-id:local-user}")
    private String defaultUserId;

    public UserMetricPreferenceController(MetricPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @Operation(summary = "查询指标偏好")
    @GetMapping
    public ApiResponse<List<UserMetricPreference>> list() {
        return ApiResponse.ok(preferenceService.list(userId()));
    }

    @Operation(summary = "保存指标偏好（整量）")
    @PutMapping
    public ApiResponse<List<UserMetricPreference>> save(@RequestBody PreferenceRequest request) {
        return ApiResponse.ok(preferenceService.save(userId(), request.preferences()));
    }

    @Operation(summary = "重置为系统默认偏好")
    @PostMapping("/reset")
    public ApiResponse<List<UserMetricPreference>> reset() {
        return ApiResponse.ok(preferenceService.reset(userId()));
    }

    public record PreferenceRequest(List<UserMetricPreference> preferences) {
    }

    private String userId() {
        return UserContext.get(defaultUserId);
    }
}
