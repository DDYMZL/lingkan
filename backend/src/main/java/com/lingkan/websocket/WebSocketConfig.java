package com.lingkan.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 首页自选股行情 WebSocket 端点。 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final QuotePushService quotePushService;

    public WebSocketConfig(QuotePushService quotePushService) {
        this.quotePushService = quotePushService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(quotePushService, "/ws/stocks/favorites")
                .setAllowedOriginPatterns("*");
    }
}
