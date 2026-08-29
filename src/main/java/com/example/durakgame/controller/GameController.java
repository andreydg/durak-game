package com.example.durakgame.controller;

import com.example.durakgame.controller.dto.CreateGameRequest;
import com.example.durakgame.controller.dto.CreateGameResponse;
import com.example.durakgame.controller.dto.AddBotRequest;
import com.example.durakgame.controller.dto.AttackRequest;
import com.example.durakgame.controller.dto.DefendRequest;
import com.example.durakgame.controller.dto.GameResponse;
import com.example.durakgame.controller.dto.JoinGameRequest;
import com.example.durakgame.controller.dto.JoinGameResponse;
import com.example.durakgame.controller.dto.PlayerActionRequest;
import com.example.durakgame.controller.dto.StartGameRequest;
import com.example.durakgame.controller.dto.TransferRequest;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.service.GameService;
import com.example.durakgame.websocket.GameWebSocketHandler;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {
    /** Header carrying the per-player secret token that proves ownership for reads and actions. */
    private static final String TOKEN_HEADER = "X-Durak-Token";

    private final GameService gameService;
    private final GameWebSocketHandler webSocketHandler;

    public GameController(GameService gameService, GameWebSocketHandler webSocketHandler) {
        this.gameService = gameService;
        this.webSocketHandler = webSocketHandler;
    }

    @PostMapping
    public CreateGameResponse createGame(@Valid @RequestBody CreateGameRequest request) {
        boolean publicRoom = request.publicRoom() == null || request.publicRoom();
        return createResponse(gameService.createGame(request.hostName(), publicRoom));
    }

    @PostMapping("/quick-play")
    public CreateGameResponse quickPlay(@Valid @RequestBody CreateGameRequest request) {
        return createResponse(gameService.createQuickGame(request.hostName()));
    }

    private CreateGameResponse createResponse(Game game) {
        Player host = game.getPlayers().getFirst();
        return new CreateGameResponse(
                toResponse(game, host.getId()),
                host.getId(),
                host.getSecret()
        );
    }

    @PostMapping("/{code}/join")
    public JoinGameResponse joinGame(@PathVariable String code, @Valid @RequestBody JoinGameRequest request) {
        Player joined = gameService.joinGame(code, request.playerName());
        Game game = gameService.getGame(code);
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return new JoinGameResponse(
                toResponse(game, joined.getId()),
                joined.getId(),
                joined.getSecret()
        );
    }

    @PostMapping("/{code}/bots")
    public GameResponse addBot(
            @PathVariable String code,
            @Valid @RequestBody AddBotRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        gameService.addBot(code, request.playerId(), request.botName());
        Game game = gameService.getGame(code);
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @GetMapping("/{code}")
    public GameResponse getGame(
            @PathVariable String code,
            @RequestParam(required = false) String viewerPlayerId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        Game game = gameService.getGame(code);
        // Reveal the viewer's hand and legal moves only when the token proves ownership.
        String authorizedViewer = gameService.isAuthorized(game, viewerPlayerId, token) ? viewerPlayerId : null;
        return toResponse(game, authorizedViewer);
    }

    @PostMapping("/{code}/start")
    public GameResponse startGame(
            @PathVariable String code,
            @Valid @RequestBody StartGameRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.startGame(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/rematch")
    public GameResponse rematch(
            @PathVariable String code,
            @Valid @RequestBody PlayerActionRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.rematch(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/attack")
    public GameResponse attack(
            @PathVariable String code,
            @Valid @RequestBody AttackRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.attack(code, request.playerId(), Card.fromCode(request.card()));
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/defend")
    public GameResponse defend(
            @PathVariable String code,
            @Valid @RequestBody DefendRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.defend(
                code,
                request.playerId(),
                Card.fromCode(request.attackCard()),
                Card.fromCode(request.defenseCard())
        );
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/transfer")
    public GameResponse transfer(
            @PathVariable String code,
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.transfer(code, request.playerId(), Card.fromCode(request.card()));
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/take")
    public GameResponse take(
            @PathVariable String code,
            @Valid @RequestBody PlayerActionRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.takeCards(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/end-round")
    public GameResponse endRound(
            @PathVariable String code,
            @Valid @RequestBody PlayerActionRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        Game game = gameService.endRound(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/leave")
    public void leaveGame(
            @PathVariable String code,
            @Valid @RequestBody PlayerActionRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        // The leave-beacon cannot set headers, so fall back to a token carried in the body.
        String effectiveToken = token != null ? token : request.token();
        gameService.requireAuthorized(code, request.playerId(), effectiveToken);
        gameService.leaveGame(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code);
    }

    @PostMapping("/{code}/heartbeat")
    public void heartbeat(
            @PathVariable String code,
            @Valid @RequestBody PlayerActionRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        gameService.requireAuthorized(code, request.playerId(), token);
        gameService.heartbeat(code, request.playerId());
    }

    private GameResponse toResponse(Game game, String viewerPlayerId) {
        return GameResponse.from(
                game,
                gameService.getMaxPlayers(),
                viewerPlayerId,
                webSocketHandler.botThinkingForGame(game.getCode())
        );
    }

}
