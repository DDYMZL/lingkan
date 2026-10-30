package com.lingkan.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.common.config.QuoteSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例自选股行情 WebSocket 会话与推送服务。 */
@Component
public class QuotePushService extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(QuotePushService.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;
    private final QuoteSyncProperties properties;

    public QuotePushService(ObjectMapper objectMapper, QuoteSyncProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!properties.isWebsocketEnabled()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.add(session);
        log.debug("首页行情 WebSocket 已连接：{}，当前会话数 {}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        sessions.remove(session);
        log.debug("首页行情 WebSocket 传输异常：{}", session.getId(), exception);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /** 向全部首页会话广播变化行；返回实际发送成功的会话数。 */
    public int publish(List<BoardRowDto> rows) {
        if (!properties.isWebsocketEnabled() || rows == null || rows.isEmpty() || sessions.isEmpty()) {
            return 0;
        }
        final TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(QuoteUpdateMessage.fromRows(rows)));
        } catch (Exception ex) {
            log.warn("首页行情消息序列化失败，保留 HTTP 看板兜底", ex);
            return 0;
        }
        int sent = 0;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
                sent++;
            } catch (Exception ex) {
                sessions.remove(session);
                log.debug("首页行情推送失败，移除会话：{}", session.getId(), ex);
            }
        }
        return sent;
    }
}
