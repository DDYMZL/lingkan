package com.lingkan.calculation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页看板配置（前缀 {@code lingkan.board}）。
 *
 * <p>目标价格档位（%）为可扩展配置；未配置或配置为空时回退
 * {@link TargetPriceCalculator#DEFAULT_TARGET_YIELDS}（v1.2 起 4.0%~6.5% 共 6 档）。
 *
 * <p>v1.2 新增 {@link Weekly} 子配置（{@code lingkan.board.weekly.*}）：周线五指标
 * （60周/120周/周上/周中/周下）的周期参数与周线同步节奏，改数值不改代码（铁律 2）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lingkan.board")
public class BoardProperties {

    /** 目标股息率档位（%），如 4.0,4.5,…,6.5；空则用默认 6 档 */
    private List<Double> targetYields = new ArrayList<>();

    /** 周线指标与周线同步配置 */
    private Weekly weekly = new Weekly();

    /**
     * 周线配置（前缀 {@code lingkan.board.weekly}）。
     *
     * <p>默认口径由用户 2026-10-31 确认（spec 007 Q7-2/Q7-3/Q7-4）：
     * 60周 = MA(周收盘, 60)、120周 = MA(周收盘, 120)、
     * 周中/周上/周下 = 周线布林轨 N=20、K=2（样本标准差）。
     */
    @Data
    public static class Weekly {

        /** 周线同步与指标计算总开关；关闭后定时同步不执行，看板周线列按库内数据渲染 */
        private boolean enabled = true;

        /** 短周期均线根数（用户口语「60周」） */
        private int maShort = 60;

        /** 长周期均线根数（用户口语「120周」） */
        private int maLong = 120;

        /** 周线布林周期 N（中轨 = MA(周收盘, N)） */
        private int bollN = 20;

        /** 周线布林带宽倍数 K（上/下轨 = 中轨 ± K × 样本标准差） */
        private BigDecimal bollK = BigDecimal.valueOf(2);

        /**
         * 每次同步与计算读取的周线根数上限，必须 ≥ {@link #maLong} 且留余量。
         * 前复权基准随最新除权事件漂移，同步按整窗口覆盖 upsert，计算只读最近这么多根。
         */
        private int barsLimit = 130;

        /** 交易日定时同步间隔（毫秒），默认 60 分钟；已收盘周线每日至多变化一次 */
        private long refreshIntervalMs = 3_600_000L;

        /** 首次定时同步延迟（毫秒），避开应用启动期的行情轮询与全市场同步 */
        private long initialDelayMs = 120_000L;

        /** 相邻两次周 K 请求的最小间隔（毫秒），与其他东财适配器一致 */
        private int requestIntervalMs = 500;

        /** 转为计算引擎参数；周期非正时回退默认值，避免配置错误导致全列不可计算 */
        public WeeklyIndicatorCalculator.Params toParams() {
            WeeklyIndicatorCalculator.Params fallback = WeeklyIndicatorCalculator.Params.DEFAULT;
            return new WeeklyIndicatorCalculator.Params(
                    maShort > 0 ? maShort : fallback.maShort(),
                    maLong > 0 ? maLong : fallback.maLong(),
                    bollN > 1 ? bollN : fallback.bollN(),
                    bollK != null && bollK.signum() > 0 ? bollK : fallback.bollK());
        }

        /** 取数根数上限；非正或小于长周期时回退，保证 MA({@link #maLong}) 有足够窗口 */
        public int barsLimitOrDefault() {
            int required = Math.max(maLong, Math.max(maShort, bollN));
            return barsLimit > required ? barsLimit : required + 10;
        }
    }
}
