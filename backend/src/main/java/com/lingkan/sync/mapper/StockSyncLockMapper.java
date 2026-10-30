package com.lingkan.sync.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 同步任务数据库锁。
 *
 * <p>为什么不用 Java synchronized：单机内 synchronized 无法约束多实例部署；
 * 数据库行锁是唯一廉价且跨实例一致的方案。
 * 获取顺序为"先抢过期锁、再尝试插入"，两步都是单条原子 SQL：
 * <ol>
 *   <li>锁行已过期（expires_at &lt; now）→ UPDATE 抢占成功即持有；</li>
 *   <li>锁行不存在 → INSERT 成功即持有，主键冲突说明别人先抢到；</li>
 *   <li>锁行未过期 → 获取失败，直接拒绝本次任务。</li>
 * </ol>
 * 释放时校验 owner，避免"旧任务误释放新任务持有的锁"（过期锁被抢占后旧任务执行完毕的场景）。
 */
@Mapper
public interface StockSyncLockMapper {

    @Update("""
            UPDATE stock_sync_lock
            SET owner = #{owner}, acquired_at = CURRENT_TIMESTAMP, expires_at = #{expiresAt},
                remark = #{remark}
            WHERE lock_name = #{lockName} AND expires_at < CURRENT_TIMESTAMP
            """)
    int takeExpired(@Param("lockName") String lockName, @Param("owner") String owner,
                    @Param("expiresAt") LocalDateTime expiresAt, @Param("remark") String remark);

    @Insert("""
            INSERT INTO stock_sync_lock (lock_name, owner, acquired_at, expires_at, remark)
            VALUES (#{lockName}, #{owner}, CURRENT_TIMESTAMP, #{expiresAt}, #{remark})
            """)
    int insert(@Param("lockName") String lockName, @Param("owner") String owner,
               @Param("expiresAt") LocalDateTime expiresAt, @Param("remark") String remark);

    @Update("""
            UPDATE stock_sync_lock SET expires_at = #{expiresAt}
            WHERE lock_name = #{lockName} AND owner = #{owner}
            """)
    int renew(@Param("lockName") String lockName, @Param("owner") String owner,
              @Param("expiresAt") LocalDateTime expiresAt);

    @Delete("DELETE FROM stock_sync_lock WHERE lock_name = #{lockName} AND owner = #{owner}")
    int release(@Param("lockName") String lockName, @Param("owner") String owner);

    @Select("SELECT owner || '|' || expires_at FROM stock_sync_lock WHERE lock_name = #{lockName}")
    String describe(@Param("lockName") String lockName);
}
