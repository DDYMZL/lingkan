package com.lingkan.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.sync.entity.StockSyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StockSyncTaskMapper extends BaseMapper<StockSyncTask> {

    /** 处于执行中的任务（用于互斥校验与人工排查） */
    @Select("SELECT * FROM stock_sync_task WHERE status IN ('PENDING', 'RUNNING') ORDER BY id DESC")
    List<StockSyncTask> selectActiveTasks();

    /** 任务编号序列：按天累计，用于生成 SYNCyyyyMMddNNNN */
    @Select("SELECT COUNT(*) + 1 FROM stock_sync_task WHERE task_no LIKE CONCAT(#{prefix}, '%')")
    int nextSequence(String prefix);

    /**
     * 把"僵尸任务"标记为失败。
     *
     * <p>服务异常退出（崩溃、被 kill）时任务会永久停在 PENDING/RUNNING，
     * 查询接口永远显示执行中，用户也无法再发起新任务。启动时统一清理，
     * 已入库的数据不回滚（符合"断点保留已成功数据"的要求）。
     */
    @Update("""
            UPDATE stock_sync_task
            SET status = 'FAILED', end_time = CURRENT_TIMESTAMP,
                error_message = #{message}, progress = 100
            WHERE status IN ('PENDING', 'RUNNING')
            """)
    int markActiveFailed(@Param("message") String message);
}
