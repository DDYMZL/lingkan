package com.lingkan.sync.service;

import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.sync.dto.SyncAcceptVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 即时同步有界重试测试：连续添加自选时，被批次互斥拒绝的提交要能补跑，
 * 否则后加的股票在看板上长时间显示 {@code --}。
 */
class ImmediateSyncRetryTest {

    private static final Logger log = LoggerFactory.getLogger(ImmediateSyncRetryTest.class);

    private final AtomicInteger attempts = new AtomicInteger();

    @Test
    @DisplayName("首次受理即结束，不重试")
    void stopsWhenAccepted() {
        run(() -> {
            attempts.incrementAndGet();
            return SyncAcceptVo.accepted("wk-1", 1, "已提交");
        });

        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("被互斥拒绝后重试，受理即停止")
    void retriesUntilAccepted() {
        run(() -> {
            attempts.incrementAndGet();
            return attempts.get() < 3
                    ? SyncAcceptVo.rejected("周线同步正在执行，本次提交被跳过")
                    : SyncAcceptVo.accepted("wk-1", 1, "已提交");
        });

        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("始终被拒绝时重试到上限后放弃，不抛出")
    void givesUpAfterMaxAttempts() {
        run(() -> {
            attempts.incrementAndGet();
            return SyncAcceptVo.rejected("周线同步正在执行，本次提交被跳过");
        });

        assertEquals(ImmediateSyncRetry.MAX_ATTEMPTS, attempts.get());
    }

    @Test
    @DisplayName("提交期异常（股票不存在）不重试")
    void doesNotRetryOnSubmitFailure() {
        run(() -> {
            attempts.incrementAndGet();
            throw new BizException(ApiCode.BAD_REQUEST, "股票不存在: 999999");
        });

        assertEquals(1, attempts.get());
    }

    /** 直接执行 + 0 等待，只验证重试次数与终止条件。 */
    private void run(Supplier<SyncAcceptVo> submit) {
        ImmediateSyncRetry.run(Runnable::run, log, "周线", "WATCHLIST_ADD", submit, 0L);
    }
}
