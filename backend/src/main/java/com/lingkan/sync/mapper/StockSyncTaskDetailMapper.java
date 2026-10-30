package com.lingkan.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.sync.entity.StockSyncTaskDetail;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockSyncTaskDetailMapper extends BaseMapper<StockSyncTaskDetail> {

    /**
     * 批量写入失败明细。同一任务内相同 (股票, 数据源, 指标, 类型) 只保留一条，
     * 重复出现时累加重试次数并覆盖为最新失败原因。
     */
    @Insert("""
            <script>
            INSERT INTO stock_sync_task_detail
                (task_id, stock_code, provider_code, metric_code, sync_type, status,
                 retry_count, error_message, created_at)
            VALUES
            <foreach collection="list" item="d" separator=",">
                (#{d.taskId}, #{d.stockCode,jdbcType=VARCHAR}, #{d.providerCode,jdbcType=VARCHAR},
                 #{d.metricCode,jdbcType=VARCHAR}, #{d.syncType}, #{d.status},
                 #{d.retryCount,jdbcType=INTEGER}, #{d.errorMessage,jdbcType=VARCHAR}, CURRENT_TIMESTAMP)
            </foreach>
            ON DUPLICATE KEY UPDATE
                status        = excluded.status,
                retry_count   = stock_sync_task_detail.retry_count + 1,
                error_message = excluded.error_message
            </script>
            """)
    int insertBatch(@Param("list") List<StockSyncTaskDetail> list);
}
