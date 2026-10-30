package com.lingkan.metrics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.common.config.CacheConfig;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.metrics.dto.MetricCategoryGroupDto;
import com.lingkan.metrics.dto.MetricDefinitionDto;
import com.lingkan.metrics.entity.MetricDefinition;
import com.lingkan.metrics.mapper.MetricDefinitionMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标库服务。指标清单与元数据是系统唯一的指标来源。
 */
@Service
public class MetricDefinitionService {

    private static final Map<String, String> CATEGORY_NAMES = new LinkedHashMap<>(Map.of(
            "basic", "基础信息",
            "quote", "实时行情",
            "valuation", "估值指标",
            "dividend", "股息指标",
            "financial", "财务指标",
            "technical", "技术指标"
    ));

    private static final List<String> CATEGORY_ORDER =
            List.of("basic", "quote", "valuation", "dividend", "financial", "technical");

    private final MetricDefinitionMapper mapper;

    public MetricDefinitionService(MetricDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    @Cacheable(cacheNames = CacheConfig.CACHE_METRICS, key = "'all'")
    public List<MetricDefinitionDto> listAll() {
        List<MetricDefinition> entities = mapper.selectList(
                Wrappers.<MetricDefinition>lambdaQuery().orderByAsc(MetricDefinition::getSortOrder));
        List<MetricDefinitionDto> result = new ArrayList<>(entities.size());
        entities.forEach(e -> result.add(toDto(e)));
        return result;
    }

    public List<MetricCategoryGroupDto> listByCategory() {
        List<MetricDefinitionDto> all = listAll();
        List<MetricCategoryGroupDto> groups = new ArrayList<>();
        for (String category : CATEGORY_ORDER) {
            List<MetricDefinitionDto> metrics = all.stream()
                    .filter(m -> category.equals(m.category()))
                    .toList();
            if (!metrics.isEmpty()) {
                groups.add(new MetricCategoryGroupDto(category,
                        CATEGORY_NAMES.getOrDefault(category, category), metrics));
            }
        }
        return groups;
    }

    public MetricDefinitionDto get(String metricCode) {
        return listAll().stream()
                .filter(m -> m.metricCode().equals(metricCode))
                .findFirst()
                .orElseThrow(() -> new BizException(ApiCode.METRIC_NOT_FOUND, "指标不存在: " + metricCode));
    }

    public MetricDefinition requireEntity(String metricCode) {
        MetricDefinition entity = mapper.selectOne(
                Wrappers.<MetricDefinition>lambdaQuery().eq(MetricDefinition::getMetricCode, metricCode));
        if (entity == null) {
            throw new BizException(ApiCode.METRIC_NOT_FOUND, "指标不存在: " + metricCode);
        }
        return entity;
    }

    private MetricDefinitionDto toDto(MetricDefinition e) {
        return new MetricDefinitionDto(
                e.getMetricCode(),
                e.getMetricName(),
                e.getMetricCategory(),
                e.getDataType(),
                e.getUnit() == null ? "" : e.getUnit(),
                Boolean.TRUE.equals(e.getEnabled()),
                e.getCalculationType(),
                e.getCalculationStatus(),
                e.getDefaultPrecision() == null ? 2 : e.getDefaultPrecision(),
                e.getDescription(),
                e.getCalculationExpression(),
                e.getPendingQuestions(),
                e.getSortOrder() == null ? 0 : e.getSortOrder()
        );
    }
}
