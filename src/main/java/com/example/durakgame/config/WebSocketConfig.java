package com.example.durakgame.config;

import com.example.durakgame.websocket.GameWebSocketHandler;
import com.example.durakgame.websocket.LobbyWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketHandler gameWebSocketHandler;
    private final LobbyWebSocketHandler lobbyWebSocketHandler;

    public WebSocketConfig(
            GameWebSocketHandler gameWebSocketHandler,
            LobbyWebSocketHandler lobbyWebSocketHandler
    ) {
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.lobbyWebSocketHandler = lobbyWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/games/*")
                .setAllowedOriginPatterns("*");
        registry.addHandler(lobbyWebSocketHandler, "/ws/lobbies")
                .setAllowedOriginPatterns("*");
    }
}
