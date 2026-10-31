package com.lingkan.sync.scheduler;

import com.lingkan.calculation.BoardProperties;
import com.lingkan.scheduler.TradingSessionChecker;
import com.lingkan.stock.entity.Stock;
import com.lingkan.sync.dto.SyncAcceptVo;
import com.lingkan.sync.service.WeeklyKlineSyncService;
import com.lingkan.watchlist.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 周线同步调度测试：开关、交易日门槛、自选范围与异常吞噬（spec 010 §4.4）。 */
@ExtendWith(MockitoExtension.class)
class WeeklyKlineSyncSchedulerTest {

    /** 2026-10-30 周五（交易日）、2026-10-31 周六 */
    private static final LocalDateTime FRIDAY = LocalDateTime.of(2026, 10, 30, 10, 0);
    private static final LocalDateTime SATURDAY = LocalDateTime.of(2026, 10, 31, 10, 0);

    @Mock
    private WeeklyKlineSyncService syncService;
    @Mock
    private WatchlistService watchlistService;
    @Mock
    private TradingSessionChecker tradingSessionChecker;

    private BoardProperties boardProperties;
    private WeeklyKlineSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        boardProperties = new BoardProperties();
        scheduler = new WeeklyKlineSyncScheduler(syncService, watchlistService,
                tradingSessionChecker, boardProperties);
    }

    @Test
    @DisplayName("开关关闭时不读自选也不提交")
    void skipsWhenDisabled() {
        boardProperties.getWeekly().setEnabled(false);

        scheduler.syncAt(FRIDAY);

        verifyNoInteractions(tradingSessionChecker, watchlistService, syncService);
    }

    @Test
    @DisplayName("非交易日跳过（周末周线不变化）")
    void skipsNonTradingDay() {
        when(tradingSessionChecker.isTradingDay(LocalDate.of(2026, 10, 31))).thenReturn(false);

        scheduler.syncAt(SATURDAY);

        verifyNoInteractions(watchlistService, syncService);
    }

    @Test
    @DisplayName("交易日按自选股并集提交，触发方式 SCHEDULED")
    void submitsFavoriteCodesOnTradingDay() {
        when(tradingSessionChecker.isTradingDay(FRIDAY.toLocalDate())).thenReturn(true);
        when(watchlistService.listAllFavoriteStocks())
                .thenReturn(List.of(stock("000001"), stock("600519")));
        when(syncService.submitAll(anyList(), anyString()))
                .thenReturn(SyncAcceptVo.accepted("wk-1", 2, "已提交 2 只股票的周线同步"));

        scheduler.syncAt(FRIDAY);

        verify(syncService).submitAll(List.of("000001", "600519"), "SCHEDULED");
    }

    @Test
    @DisplayName("自选为空时仍走受理入口（返回无需同步），不抛异常")
    void emptyWatchlistIsAccepted() {
        when(tradingSessionChecker.isTradingDay(FRIDAY.toLocalDate())).thenReturn(true);
        when(watchlistService.listAllFavoriteStocks()).thenReturn(List.of());
        when(syncService.submitAll(anyList(), anyString()))
                .thenReturn(SyncAcceptVo.accepted("wk-2", 0, "自选股为空，无需同步"));

        scheduler.syncAt(FRIDAY);

        verify(syncService).submitAll(List.of(), "SCHEDULED");
    }

    @Test
    @DisplayName("提交异常被吞掉，不影响后续调度")
    void swallowsSubmitFailure() {
        when(tradingSessionChecker.isTradingDay(FRIDAY.toLocalDate())).thenReturn(true);
        when(watchlistService.listAllFavoriteStocks()).thenReturn(List.of(stock("600519")));
        when(syncService.submitAll(anyList(), anyString()))
                .thenThrow(new IllegalStateException("数据库连接失败"));

        assertDoesNotThrow(() -> scheduler.syncAt(FRIDAY));
        verify(syncService).submitAll(anyList(), any());
    }

    private static Stock stock(String code) {
        Stock stock = new Stock();
        stock.setStockCode(code);
        stock.setStockName(code);
        return stock;
    }
}
