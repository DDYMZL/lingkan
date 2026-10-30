package com.lingkan.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.metrics.entity.StockDailyMetric;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockDailyMetricMapper extends BaseMapper<StockDailyMetric> {

    /**
     * 批量写入每日指标快照（唯一键 stock_id + metric_code + trade_date + source_code）。
     *
     * <p>只用于行情类指标（category=quote）的每日留痕；财务类指标按报告期存储，
     * 不允许每天伪造一条新历史。
     */
    @Insert("""
            <script>
            INSERT INTO stock_daily_metrics
                (stock_id, metric_code, metric_value, trade_date, source_code, calculation_version, created_at)
            VALUES
            <foreach collection="list" item="d" separator=",">
                (#{d.stockId}, #{d.metricCode}, #{d.metricValue,jdbcType=NUMERIC}, #{d.tradeDate},
                 #{d.sourceCode}, #{d.calculationVersion,jdbcType=VARCHAR}, CURRENT_TIMESTAMP)
            </foreach>
            ON DUPLICATE KEY UPDATE
                metric_value         = COALESCE(excluded.metric_value, stock_daily_metrics.metric_value),
                calculation_version  = COALESCE(excluded.calculation_version,
                                                stock_daily_metrics.calculation_version)
            </script>
            """)
    int upsertBatch(@Param("list") List<StockDailyMetric> list);
}
