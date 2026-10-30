package com.lingkan.system.service;

import com.lingkan.datasource.provider.StockDataProvider;
import com.lingkan.datasource.service.DataSourceService;
import com.lingkan.market.service.MarketService;
import com.lingkan.system.dto.DataStatusDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统状态服务：交易时段判断 + 采集状态汇总。
 *
 * <p>注意：当前未接入交易所日历，节假日仅按"工作日"近似判断，
 * 正式上线前需接入交易日历（见 docs/tasks/implementation-plan.md Phase 6）。
 */
@Service
public class SystemService {

    private final MarketService marketService;
    private final DataSourceService dataSourceService;

    @Value("${lingkan.market.session.morning-open:09:30}")
    private String morningOpen;
    @Value("${lingkan.market.session.morning-close:11:30}")
    private String morningClose;
    @Value("${lingkan.market.session.afternoon-open:13:00}")
    private String afternoonOpen;
    @Value("${lingkan.market.session.afternoon-close:15:00}")
    private String afternoonClose;

    public SystemService(MarketService marketService, DataSourceService dataSourceService) {
        this.marketService = marketService;
        this.dataSourceService = dataSourceService;
    }

    public DataStatusDto dataStatus() {
        LocalDateTime now = LocalDateTime.now();
        boolean tradingDay = now.getDayOfWeek() != DayOfWeek.SATURDAY
                && now.getDayOfWeek() != DayOfWeek.SUNDAY;
        String phase = resolvePhase(now, tradingDay);

        var overview = marketService.overview();
        List<DataStatusDto.CollectionStatus> collections = new ArrayList<>();
        dataSourceService.list().forEach(p -> collections.add(new DataStatusDto.CollectionStatus(
                p.providerCode(), p.providerName(), p.healthStatus(),
                p.lastSuccessAt(), p.lastFailureAt(),
                p.verified() ? "接口已实测验证" : "接口尚未验证")));

        return new DataStatusDto(now, tradingDay, phase, overview.fetchTime(), collections);
    }

    private String resolvePhase(LocalDateTime now, boolean tradingDay) {
        if (!tradingDay) {
            return "休市";
        }
        LocalTime time = now.toLocalTime();
        LocalTime mo = LocalTime.parse(morningOpen);
        LocalTime mc = LocalTime.parse(morningClose);
        LocalTime ao = LocalTime.parse(afternoonOpen);
        LocalTime ac = LocalTime.parse(afternoonClose);
        if (time.isBefore(mo)) {
            return "未开盘";
        }
        if (!time.isAfter(mc)) {
            return "上午交易中";
        }
        if (time.isBefore(ao)) {
            return "午间休市";
        }
        if (!time.isAfter(ac)) {
            return "下午交易中";
        }
        return "已收盘";
    }
}
