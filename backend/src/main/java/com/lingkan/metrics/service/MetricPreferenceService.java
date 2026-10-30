package com.lingkan.metrics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.metrics.dto.MetricDefinitionDto;
import com.lingkan.metrics.entity.MetricDefinition;
import com.lingkan.metrics.entity.UserMetricPreference;
import com.lingkan.metrics.mapper.MetricDefinitionMapper;
import com.lingkan.metrics.mapper.UserMetricPreferenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用户指标偏好服务。
 *
 * <p>默认偏好：仅启用公式已确认（READY）的核心指标，待确认指标默认不启用，
 * 避免小程序展示"待确认"的空指标。
 */
@Service
public class MetricPreferenceService {

    /** 默认启用并展示的指标（与技术文档中的示例配置一致） */
    private static final List<String> DEFAULT_ENABLED = List.of(
            "stock_name", "latest_price", "change_percent",
            "pe_ttm", "dividend_yield_ttm", "net_profit_parent_yoy"
    );

    /** 默认参与首页/自选/详情展示的指标 */
    private static final Set<String> DEFAULT_VISIBLE = Set.of(
            "latest_price", "change_percent", "pe_ttm", "dividend_yield_ttm",
            "net_profit_parent_yoy", "stock_name"
    );

    private final UserMetricPreferenceMapper preferenceMapper;
    private final MetricDefinitionMapper definitionMapper;

    public MetricPreferenceService(UserMetricPreferenceMapper preferenceMapper,
                                   MetricDefinitionMapper definitionMapper) {
        this.preferenceMapper = preferenceMapper;
        this.definitionMapper = definitionMapper;
    }

    public List<UserMetricPreference> list(String userId) {
        List<UserMetricPreference> list = query(userId);
        if (list.isEmpty()) {
            return initialize(userId);
        }
        return list;
    }

    private List<UserMetricPreference> query(String userId) {
        return preferenceMapper.selectList(Wrappers.<UserMetricPreference>lambdaQuery()
                .eq(UserMetricPreference::getUserId, userId)
                .orderByAsc(UserMetricPreference::getSortOrder));
    }

    /**
     * 首次使用时按指标库生成默认偏好。
     */
    @Transactional
    public List<UserMetricPreference> initialize(String userId) {
        List<MetricDefinition> definitions = definitionMapper.selectList(
                Wrappers.<MetricDefinition>lambdaQuery().orderByAsc(MetricDefinition::getSortOrder));
        List<UserMetricPreference> preferences = new ArrayList<>(definitions.size());
        for (MetricDefinition def : definitions) {
            UserMetricPreference pref = new UserMetricPreference();
            pref.setUserId(userId);
            pref.setMetricCode(def.getMetricCode());
            boolean enabled = DEFAULT_ENABLED.contains(def.getMetricCode())
                    && "READY".equals(def.getCalculationStatus());
            pref.setEnabled(enabled);
            pref.setShowInHome(enabled && DEFAULT_VISIBLE.contains(def.getMetricCode()));
            pref.setShowInWatchlist(enabled && DEFAULT_VISIBLE.contains(def.getMetricCode()));
            pref.setShowInDetail(enabled);
            pref.setSortOrder(def.getSortOrder() == null ? 0 : def.getSortOrder());
            pref.setFilterable(false);
            pref.setSortable(!"basic".equals(def.getMetricCategory()));
            pref.setRecordHistory(enabled && Set.of("pe_ttm", "dividend_yield_ttm", "latest_price")
                    .contains(def.getMetricCode()));
            pref.setCreatedAt(LocalDateTime.now());
            pref.setUpdatedAt(LocalDateTime.now());
            preferenceMapper.insert(pref);
            preferences.add(pref);
        }
        return preferences;
    }

    /**
     * 整量保存偏好。只允许修改展示配置，不允许修改指标公式。
     */
    @Transactional
    public List<UserMetricPreference> save(String userId, List<UserMetricPreference> incoming) {
        List<UserMetricPreference> existing = list(userId);
        for (UserMetricPreference pref : incoming) {
            UserMetricPreference target = existing.stream()
                    .filter(e -> e.getMetricCode().equals(pref.getMetricCode()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                continue;
            }
            target.setEnabled(Boolean.TRUE.equals(pref.getEnabled()));
            target.setShowInHome(Boolean.TRUE.equals(pref.getShowInHome()));
            target.setShowInWatchlist(Boolean.TRUE.equals(pref.getShowInWatchlist()));
            target.setShowInDetail(Boolean.TRUE.equals(pref.getShowInDetail()));
            target.setFilterable(Boolean.TRUE.equals(pref.getFilterable()));
            target.setSortable(pref.getSortable() == null || pref.getSortable());
            target.setRecordHistory(Boolean.TRUE.equals(pref.getRecordHistory()));
            target.setSortOrder(pref.getSortOrder() == null ? 0 : pref.getSortOrder());
            target.setSelectedSource(pref.getSelectedSource());
            target.setPrecision(pref.getPrecision());
            target.setDisplayName(pref.getDisplayName());
            target.setUnit(pref.getUnit());
            target.setUpdatedAt(LocalDateTime.now());
            preferenceMapper.updateById(target);
        }
        return query(userId);
    }

    @Transactional
    public List<UserMetricPreference> reset(String userId) {
        preferenceMapper.delete(Wrappers.<UserMetricPreference>lambdaQuery()
                .eq(UserMetricPreference::getUserId, userId));
        return initialize(userId);
    }

    /** 合并指标定义与用户偏好，得到前端需要的展示元数据 */
    public List<MetricDefinitionDto> mergeEnabledDefinitions(String userId) {
        List<UserMetricPreference> preferences = list(userId);
        List<MetricDefinition> definitions = definitionMapper.selectList(
                Wrappers.<MetricDefinition>lambdaQuery().orderByAsc(MetricDefinition::getSortOrder));
        List<MetricDefinitionDto> result = new ArrayList<>();
        for (UserMetricPreference pref : preferences) {
            if (!Boolean.TRUE.equals(pref.getEnabled())) {
                continue;
            }
            definitions.stream()
                    .filter(d -> d.getMetricCode().equals(pref.getMetricCode()))
                    .findFirst()
                    .ifPresent(d -> result.add(new MetricDefinitionDto(
                            d.getMetricCode(),
                            pref.getDisplayName() == null || pref.getDisplayName().isBlank()
                                    ? d.getMetricName() : pref.getDisplayName(),
                            d.getMetricCategory(),
                            d.getDataType(),
                            pref.getUnit() == null || pref.getUnit().isBlank()
                                    ? (d.getUnit() == null ? "" : d.getUnit()) : pref.getUnit(),
                            true,
                            d.getCalculationType(),
                            d.getCalculationStatus(),
                            pref.getPrecision() == null ? d.getDefaultPrecision() : pref.getPrecision(),
                            d.getDescription(),
                            d.getCalculationExpression(),
                            d.getPendingQuestions(),
                            pref.getSortOrder() == null ? 0 : pref.getSortOrder()
                    )));
        }
        return result;
    }
}
