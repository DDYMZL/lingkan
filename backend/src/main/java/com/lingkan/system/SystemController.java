package com.lingkan.system;

import com.lingkan.common.response.ApiResponse;
import com.lingkan.system.dto.DataStatusDto;
import com.lingkan.system.service.SystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统")
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @Operation(summary = "数据状态与交易时段")
    @GetMapping("/data-status")
    public ApiResponse<DataStatusDto> dataStatus() {
        return ApiResponse.ok(systemService.dataStatus());
    }
}
