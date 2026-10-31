package com.lingkan.sync.scheduler;

import com.lingkan.calculation.BoardProperties;
import com.lingkan.scheduler.TradingSessionChecker;
import com.lingkan.stock.entity.Stock;
import com.lingkan.sync.dto.SyncAcceptVo;
import com.lingkan.sync.service.WeeklyKlineSyncService;
import com.lingkan.watchlist.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 周线同步调度器（spec 010 §4.4）。
 *
 * <p>交易日按 {@code lingkan.board.weekly.refresh-interval-ms}（默认 60 分钟）同步全部自选股周线。
 * 用「交易日」而非「连续竞价时段」判断：已收盘周线在收盘后仍会被数据源补正，
 * 且本周未收盘周线需要盘中刷新，因此交易日全天都要跑；周末不跑（周线不会变化）。
 *
 * <p>只负责确定范围与提交，不直接执行采集逻辑；开关关闭后定时任务不执行，
 * 手动接口与添加自选触发仍可用。
 */
@Component
public class WeeklyKlineSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyKlineSyncScheduler.class);

    private final WeeklyKlineSyncService syncService;
    private final WatchlistService watchlistService;
    private final TradingSessionChecker tradingSessionChecker;
    private final BoardProperties boardProperties;

    public WeeklyKlineSyncScheduler(WeeklyKlineSyncService syncService,
                                    WatchlistService watchlistService,
                                    TradingSessionChecker tradingSessionChecker,
                                    BoardProperties boardProperties) {
        this.syncService = syncService;
        this.watchlistService = watchlistService;
        this.tradingSessionChecker = tradingSessionChecker;
        this.boardProperties = boardProperties;
    }

    @Scheduled(fixedDelayString = "${lingkan.board.weekly.refresh-interval-ms:3600000}",
            initialDelayString = "${lingkan.board.weekly.initial-delay-ms:120000}")
    public void syncWeeklyKline() {
        syncAt(LocalDateTime.now(TradingSessionChecker.MARKET_ZONE));
    }

    void syncAt(LocalDateTime now) {
        if (!boardProperties.getWeekly().isEnabled()) {
            log.debug("周线同步已关闭（lingkan.board.weekly.enabled=false），跳过本次定时任务");
            return;
        }
        if (!tradingSessionChecker.isTradingDay(now.toLocalDate())) {
            log.debug("非交易日，跳过周线同步");
            return;
        }
        List<String> codes = watchlistService.listAllFavoriteStocks().stream()
                .map(Stock::getStockCode)
                .toList();
        try {
            SyncAcceptVo vo = syncService.submitAll(codes, "SCHEDULED");
            if (vo.accepted() && vo.count() > 0) {
                log.info("定时周线同步提交：{}", vo.message());
            } else {
                log.debug("定时周线同步未提交：{}", vo.message());
            }
        } catch (Exception ex) {
            // 定时任务不能抛出异常，否则调度线程会吞掉后续触发
            log.error("定时周线同步提交失败: {}", ex.getMessage());
        }
    }
}
