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
 * {@link TargetPriceCalculator#DEFAULT_TARGET_YIELDS}（v1.2 起 4.0%~6.5% 共 6 档）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lingkan.board")
public class BoardProperties {

    /** 目标股息率档位（%），如 4.0,4.5,…,6.5；空则用默认 6 档 */
    private List<Double> targetYields = new ArrayList<>();
}
