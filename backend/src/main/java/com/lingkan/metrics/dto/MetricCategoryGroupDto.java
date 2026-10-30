package com.lingkan.metrics.dto;

import java.util.List;

/**
 * 按分类聚合的指标定义。
 */
public record MetricCategoryGroupDto(String category, String categoryName,
                                     List<MetricDefinitionDto> metrics) {
}
