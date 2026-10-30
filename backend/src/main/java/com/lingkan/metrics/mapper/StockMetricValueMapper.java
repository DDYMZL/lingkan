package com.lingkan.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.metrics.entity.StockMetricValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockMetricValueMapper extends BaseMapper<StockMetricValue> {

    /**
     * 批量 Upsert 指标值（唯一键 stock_id + metric_code + source_code）。
     *
     * <p>同步流程单次写入 7 万级指标项，必须走批量语句；normalized_value 为 null 时
     * 保留库中已有有效值（"不允许用 NULL 覆盖有效指标"），仅刷新采集时间与状态。
     */
    @Insert("""
            <script>
            INSERT INTO stock_metric_values
                (stock_id, metric_code, source_code, raw_value, normalized_value, unit,
                 data_time, fetch_time, status, calculation_version)
            VALUES
            <foreach collection="list" item="v" separator=",">
                (#{v.stockId}, #{v.metricCode}, #{v.sourceCode}, #{v.rawValue,jdbcType=VARCHAR},
                 #{v.normalizedValue,jdbcType=NUMERIC}, #{v.unit,jdbcType=VARCHAR},
                 #{v.dataTime,jdbcType=TIMESTAMP}, #{v.fetchTime,jdbcType=TIMESTAMP},
                 #{v.status}, #{v.calculationVersion,jdbcType=VARCHAR})
            </foreach>
            ON DUPLICATE KEY UPDATE
                raw_value           = COALESCE(excluded.raw_value, stock_metric_values.raw_value),
                normalized_value    = COALESCE(excluded.normalized_value, stock_metric_values.normalized_value),
                unit                = COALESCE(excluded.unit, stock_metric_values.unit),
                data_time           = COALESCE(excluded.data_time, stock_metric_values.data_time),
                fetch_time          = excluded.fetch_time,
                status              = excluded.status,
                calculation_version = COALESCE(excluded.calculation_version,
                                               stock_metric_values.calculation_version)
            </script>
            """)
    int upsertBatch(@Param("list") List<StockMetricValue> list);
}
