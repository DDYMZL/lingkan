package com.lingkan.calculation;

import com.lingkan.common.util.DecimalUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标价格计算器（spec 010 §3.3）。
 *
 * <p>目标价格 = TTM 每股现金分红 × 100 / 目标股息率（HALF_UP 保留 2 位）。
 *
 * <p>目标档位（v1.2 起默认 4.0%~6.5% 共 6 档，步长 0.5）来自配置
 * {@code lingkan.board.target-yields}，可扩展。用户 2026-10-31 裁决移除
 * 7%/7.5%/8% 三档；配置值与默认值必须保持一致。目标价格依赖 TTM 每股分红
 * （非行情），仅分红数据变化时重算（实现上按需惰性计算）。
 *
 * <p>输入无效（分红为空/非正，或某档收益率非正）→ 该档价格为 null
 * （对外展示 --），禁止用 0 冒充。
 */
public final class TargetPriceCalculator {

    /** 未配置目标档位时的默认值：4.0%~6.5% 共 6 档，步长 0.5（spec 010 v1.2 裁决 ⑦） */
    public static final List<BigDecimal> DEFAULT_TARGET_YIELDS = List.of(
            new BigDecimal("4.0"), new BigDecimal("4.5"), new BigDecimal("5.0"),
            new BigDecimal("5.5"), new BigDecimal("6.0"), new BigDecimal("6.5"));

    private TargetPriceCalculator() {
    }

    /**
     * 计算各目标档位对应的目标价格。
     *
     * @param ttmCashPerShare TTM 每股现金分红（当前股本口径）
     * @param targetYields    目标股息率档位（%）；为 null/空时用默认档位
     * @return 有序 map，键为档位字符串（如 "4.0"），值为目标价格；
     *         分红为空/非正时所有档位值为 null（键仍齐全，便于前端渲染固定列）
     */
    public static Map<String, BigDecimal> calculate(BigDecimal ttmCashPerShare,
                                                    List<BigDecimal> targetYields) {
        List<BigDecimal> yields = targetYields == null || targetYields.isEmpty()
                ? DEFAULT_TARGET_YIELDS : targetYields;
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (BigDecimal yield : yields) {
            result.put(yield.toPlainString(), targetPrice(ttmCashPerShare, yield));
        }
        return result;
    }

    /** 单档目标价格 = 每股分红 × 100 / 目标收益率；HALF_UP 保留 2 位；输入无效返回 null */
    public static BigDecimal targetPrice(BigDecimal cashPerShare, BigDecimal targetYield) {
        if (cashPerShare == null || cashPerShare.signum() <= 0
                || targetYield == null || targetYield.signum() <= 0) {
            return null;
        }
        return DecimalUtils.percent(cashPerShare, targetYield)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
