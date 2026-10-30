package com.lingkan.calculation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页看板配置（前缀 {@code lingkan.board}）。
 *
 * <p>目标价格档位（%）为可扩展配置；未配置或配置为空时回退
 * {@link TargetPriceCalculator#DEFAULT_TARGET_YIELDS}（4.0%~8.0% 共 9 档）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lingkan.board")
public class BoardProperties {

    /** 目标股息率档位（%），如 4.0,4.5,5.0,…,8.0；空则用默认 9 档 */
    private List<Double> targetYields = new ArrayList<>();
}
