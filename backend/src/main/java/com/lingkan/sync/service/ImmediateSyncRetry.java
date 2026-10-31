package com.lingkan.sync.service;

import com.lingkan.sync.dto.SyncAcceptVo;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 即时同步（添加自选触发）的有界重试。
 *
 * <p>各同步服务都用批次互斥（{@code running} 原子标志）保证同一时刻只跑一个批次，
 * 并发提交直接被拒绝且不排队。这对定时任务是对的，但对「添加自选后立即同步」不友好：
 * 连续添加多只股票时只有第一只被受理，其余被丢弃，后加的股票要等到下一次定时同步
 * （最长 1 小时，非交易日还要更久）才有数据，期间首页对应列一直显示 {@code --}。
 *
 * <p>这里把提交动作放到异步线程里做「提交 → 被拒 → 等待 → 重试」，最多
 * {@link #MAX_ATTEMPTS} 次；仍被拒绝则记日志放弃，由定时同步与手动接口兜底，
 * 业务流程（添加自选）不受影响。重试在异步线程执行，不占用 HTTP 请求线程。
 */
final class ImmediateSyncRetry {

    /** 最大提交次数（含首次） */
    static final int MAX_ATTEMPTS = 3;

    /** 相邻两次提交的等待间隔；单只股票的批次通常 1~2 秒内结束 */
    static final long RETRY_DELAY_MS = 5_000L;

    private ImmediateSyncRetry() {
    }

    static void run(Executor executor, Logger log, String label, String triggerType,
                    Supplier<SyncAcceptVo> submit) {
        run(executor, log, label, triggerType, submit, RETRY_DELAY_MS);
    }

    /** 带自定义等待间隔的重载，供单元测试用 0 间隔验证重试次数。 */
    static void run(Executor executor, Logger log, String label, String triggerType,
                    Supplier<SyncAcceptVo> submit, long retryDelayMs) {
        executor.execute(() -> {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    SyncAcceptVo vo = submit.get();
                    if (vo.accepted()) {
                        return;
                    }
                    log.info("{}即时同步第 {}/{} 次未受理（{}）：{}",
                            label, attempt, MAX_ATTEMPTS, triggerType, vo.message());
                } catch (Exception ex) {
                    // 参数非法、股票不存在等提交期异常重试也没有意义，直接放弃
                    log.warn("{}即时同步提交失败（{}）: {}", label, triggerType, ex.getMessage());
                    return;
                }
                if (attempt < MAX_ATTEMPTS && !waitBeforeRetry(retryDelayMs, log, label)) {
                    return;
                }
            }
        });
    }

    /** 等待下一次重试；被中断时恢复中断标记并放弃剩余重试。 */
    private static boolean waitBeforeRetry(long delayMs, Logger log, String label) {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("{}即时同步重试等待被中断，放弃剩余重试", label);
            return false;
        }
    }
}
