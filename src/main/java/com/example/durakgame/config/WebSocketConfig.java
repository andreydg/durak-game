package com.example.durakgame.config;

import com.example.durakgame.websocket.GameWebSocketHandler;
import com.example.durakgame.websocket.LobbyWebSocketHandler;
import com.example.durakgame.websocket.WebSocketAdmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketHandler gameWebSocketHandler;
    private final LobbyWebSocketHandler lobbyWebSocketHandler;
    private final WebSocketAdmission admission;
    private final String[] allowedOrigins;

    /**
     * @param allowedOrigins origin patterns allowed besides the site's own origin. Empty by default:
     *     Spring then accepts same-origin handshakes only, so another site's page can't open sockets
     *     through its visitors' browsers.
     */
    public WebSocketConfig(
            GameWebSocketHandler gameWebSocketHandler,
            LobbyWebSocketHandler lobbyWebSocketHandler,
            WebSocketAdmission admission,
            @Value("${app.websocket.allowed-origins:}") String[] allowedOrigins
    ) {
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.lobbyWebSocketHandler = lobbyWebSocketHandler;
        this.admission = admission;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/games/*")
                .addInterceptors(admission)
                .setAllowedOriginPatterns(allowedOrigins);
        registry.addHandler(lobbyWebSocketHandler, "/ws/lobbies")
                .addInterceptors(admission)
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
