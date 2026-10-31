package com.lingkan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.common.config.QuoteSyncProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
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
    @DisplayName("广播 STOCK_QUOTE_UPDATE，负载不含目标价")
    void publishesQuoteRowsWithoutTargetPrices() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        QuoteSyncProperties properties = new QuoteSyncProperties();
        properties.setWebsocketEnabled(true);
        QuotePushService service = new QuotePushService(objectMapper, properties);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("session-1");
        service.afterConnectionEstablished(session);

        BoardRowDto row = new BoardRowDto("600519", "贵州茅台", "SH", "白酒Ⅱ",
                new BigDecimal("1400.00"), new BigDecimal("1.20"), new BigDecimal("16.60"),
                new BigDecimal("30.00"), new BigDecimal("2.14"), new BigDecimal("20.00"), "NORMAL",
                Map.of("4.0", new BigDecimal("750.00")), LocalDateTime.of(2026, 10, 30, 10, 0),
                null, null, "NORMAL");
        int sent = service.publish(List.of(row));

        assertEquals(1, sent);
        org.mockito.ArgumentCaptor<TextMessage> captor =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("STOCK_QUOTE_UPDATE"));
        assertTrue(payload.contains("600519"));
        assertFalse(payload.contains("targetPrices"));

        service.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, service.publish(List.of(row)));
    }
}
