package com.lingkan.websocket;

import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.common.config.JacksonConfig;
import com.lingkan.common.config.QuoteSyncProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotePushServiceTest {

    @Test
    @DisplayName("广播 STOCK_QUOTE_UPDATE，负载含周线五列但不含目标价")
    void publishesQuoteRowsWithoutTargetPrices() throws Exception {
        QuotePushService service = pushService();
        WebSocketSession session = openSession(service);

        BoardRowDto row = new BoardRowDto("600519", "贵州茅台", "SH", "白酒Ⅱ",
                new BigDecimal("1400.00"), new BigDecimal("1.20"), new BigDecimal("16.60"),
                new BigDecimal("30.00"), new BigDecimal("2.14"), new BigDecimal("20.00"), "NORMAL",
                new BigDecimal("1350.50"), new BigDecimal("1180.20"),
                new BigDecimal("1520.30"), new BigDecimal("1380.10"), new BigDecimal("1239.90"),
                "NORMAL", LocalDate.of(2026, 10, 30),
                Map.of("4.0", new BigDecimal("750.00")), LocalDateTime.of(2026, 10, 30, 10, 0),
                null, null, "NORMAL");
        int sent = service.publish(List.of(row));

        assertEquals(1, sent);
        String payload = lastPayload(session);
        assertTrue(payload.contains("STOCK_QUOTE_UPDATE"));
        assertTrue(payload.contains("600519"));
        assertFalse(payload.contains("targetPrices"));
        // 周线五项随行情推送：本周未收盘周线的收盘价被实时价覆盖（spec 007 Q7-8）
        assertTrue(payload.contains("\"ma60w\":1350.50"), payload);
        assertTrue(payload.contains("\"ma120w\":1180.20"), payload);
        assertTrue(payload.contains("\"weekUpper\":1520.30"), payload);
        assertTrue(payload.contains("\"weekMiddle\":1380.10"), payload);
        assertTrue(payload.contains("\"weekLower\":1239.90"), payload);
        assertTrue(payload.contains("\"weeklyStatus\":\"NORMAL\""), payload);
        assertTrue(payload.contains("\"weeklyDataTime\":\"2026-10-30\""), payload);

        service.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, service.publish(List.of(row)));
    }

    @Test
    @DisplayName("周线缺失时下发 null 与 MISSING，不用 0 冒充")
    void publishesNullWeeklyWhenMissing() throws Exception {
        QuotePushService service = pushService();
        WebSocketSession session = openSession(service);

        BoardRowDto row = new BoardRowDto("000001", "平安银行", "SZ", null,
                new BigDecimal("11.00"), null, null, null, null, null, "N_A",
                null, null, null, null, null, "MISSING", null,
                null, LocalDateTime.of(2026, 10, 30, 10, 0), null, null, "STALE");

        assertEquals(1, service.publish(List.of(row)));

        String payload = lastPayload(session);
        assertTrue(payload.contains("\"weeklyStatus\":\"MISSING\""), payload);
        assertTrue(payload.contains("\"ma60w\":null"), payload);
        assertTrue(payload.contains("\"ma120w\":null"), payload);
        assertTrue(payload.contains("\"weekUpper\":null"), payload);
        assertTrue(payload.contains("\"weekMiddle\":null"), payload);
        assertTrue(payload.contains("\"weekLower\":null"), payload);
        assertFalse(payload.contains("\"ma60w\":0"), payload);
    }

    /** 生产同款 Jackson 2 Bean（禁用时间戳），保证断言与真机下发格式一致。 */
    private static QuotePushService pushService() {
        QuoteSyncProperties properties = new QuoteSyncProperties();
        properties.setWebsocketEnabled(true);
        return new QuotePushService(new JacksonConfig().objectMapper(), properties);
    }

    private static WebSocketSession openSession(QuotePushService service) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("session-1");
        service.afterConnectionEstablished(session);
        return session;
    }

    private static String lastPayload(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }
}
