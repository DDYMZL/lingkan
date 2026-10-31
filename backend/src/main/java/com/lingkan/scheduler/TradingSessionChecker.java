package com.lingkan.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/** A 股工作日连续竞价时段判断；法定节假日暂不单独维护。 */
@Component
public class TradingSessionChecker {

    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private final LocalTime morningOpen;
    private final LocalTime morningClose;
    private final LocalTime afternoonOpen;
    private final LocalTime afternoonClose;

    public TradingSessionChecker(
            @Value("${lingkan.market.session.morning-open:09:30}") String morningOpen,
            @Value("${lingkan.market.session.morning-close:11:30}") String morningClose,
            @Value("${lingkan.market.session.afternoon-open:13:00}") String afternoonOpen,
            @Value("${lingkan.market.session.afternoon-close:15:00}") String afternoonClose) {
        this.morningOpen = LocalTime.parse(morningOpen);
        this.morningClose = LocalTime.parse(morningClose);
        this.afternoonOpen = LocalTime.parse(afternoonOpen);
        this.afternoonClose = LocalTime.parse(afternoonClose);
    }

    public boolean isTradingNow() {
        return isTradingTime(LocalDateTime.now(MARKET_ZONE));
    }

    public boolean isTradingTime(LocalDateTime dateTime) {
        if (dateTime == null || !isTradingDay(dateTime.toLocalDate())) {
            return false;
        }
        LocalTime time = dateTime.toLocalTime();
        boolean morning = !time.isBefore(morningOpen) && !time.isAfter(morningClose);
        boolean afternoon = !time.isBefore(afternoonOpen) && !time.isAfter(afternoonClose);
        return morning || afternoon;
    }

    /**
     * 是否交易日（工作日口径，法定节假日暂未单独维护）。
     *
     * <p>供「非盘中也要跑」的同步任务使用，例如周线同步：已收盘周线在收盘后仍会补正，
     * 交易日全天都需要刷新，而不只是连续竞价时段。
     */
    public boolean isTradingDay(LocalDate date) {
        return date != null
                && date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
