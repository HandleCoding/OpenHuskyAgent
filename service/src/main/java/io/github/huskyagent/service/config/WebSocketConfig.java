package io.github.huskyagent.service.config;

import io.github.huskyagent.service.controller.TuiWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TuiWebSocketHandler tuiWebSocketHandler;
    private final TuiWsConfig tuiWsConfig;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tuiWebSocketHandler, tuiWsConfig.getPath())
                .setAllowedOrigins(tuiWsConfig.getAllowedOrigins());
    }
}