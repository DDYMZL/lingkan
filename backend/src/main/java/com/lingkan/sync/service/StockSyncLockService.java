package com.lingkan.sync.service;

import com.lingkan.sync.StockSyncProperties;
import com.lingkan.sync.mapper.StockSyncLockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 同步任务互斥锁（数据库实现）。
 *
 * <p>不依赖 Java synchronized：它无法约束多实例；也不依赖 PostgreSQL advisory lock：
 * openGauss 7.0 的兼容性未经验证。这里只用"单条原子 SQL + 主键冲突"实现，
 * 语义在 openGauss / PostgreSQL 上都成立。
 */
@Service
public class StockSyncLockService {

    private static final Logger log = LoggerFactory.getLogger(StockSyncLockService.class);

    /** 全市场同步只有一把锁：FULL / INCREMENTAL / RETRY 互斥 */
    public static final String LOCK_NAME = "stock_full_sync";

    private final StockSyncLockMapper lockMapper;
    private final StockSyncProperties properties;

    public StockSyncLockService(StockSyncLockMapper lockMapper, StockSyncProperties properties) {
        this.lockMapper = lockMapper;
        this.properties = properties;
    }

    /**
     * 尝试获取锁。允许抢占"已过期"的锁（服务异常退出后无需人工清理）。
     *
     * @return true 表示获取成功
     */
    public boolean tryAcquire(String owner, String remark) {
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusMinutes(Math.max(1, properties.getTask().getLockTimeoutMinutes()));
        int taken = lockMapper.takeExpired(LOCK_NAME, owner, expiresAt, remark);
        if (taken > 0) {
            log.info("同步锁已抢占（原锁已过期），owner={}", owner);
            return true;
        }
        try {
            lockMapper.insert(LOCK_NAME, owner, expiresAt, remark);
            return true;
        } catch (DuplicateKeyException ex) {
            log.info("同步锁已被占用，owner={}，当前锁={}", owner, lockMapper.describe(LOCK_NAME));
            return false;
        } catch (Exception ex) {
            // 唯一约束在不同驱动下可能抛出不同的异常类型，统一按"未获取到锁"处理
            log.warn("同步锁获取失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 续期（长任务防止锁提前过期被别的任务抢占） */
    public void renew(String owner) {
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusMinutes(Math.max(1, properties.getTask().getLockTimeoutMinutes()));
        int updated = lockMapper.renew(LOCK_NAME, owner, expiresAt);
        if (updated == 0) {
            log.warn("同步锁续期失败（锁可能已被抢占或已释放），owner={}", owner);
        }
    }

    /** 释放：校验 owner，避免旧任务释放新任务持有的锁 */
    public void release(String owner) {
        int released = lockMapper.release(LOCK_NAME, owner);
        if (released == 0) {
            log.warn("同步锁释放未生效（owner 不匹配或已被清理），owner={}", owner);
        }
    }

    public String describe() {
        return lockMapper.describe(LOCK_NAME);
    }
}
