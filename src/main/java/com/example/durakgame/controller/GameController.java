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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService gameService;
    private final GameWebSocketHandler webSocketHandler;

    public GameController(GameService gameService, GameWebSocketHandler webSocketHandler) {
        this.gameService = gameService;
        this.webSocketHandler = webSocketHandler;
    }

    @PostMapping
    public CreateGameResponse createGame(@Valid @RequestBody CreateGameRequest request) {
        Game game = gameService.createGame(request.hostName());
        Player host = game.getPlayers().getFirst();
        return new CreateGameResponse(
                toResponse(game, host.getId()),
                host.getId()
        );
    }

    @PostMapping("/{code}/join")
    public JoinGameResponse joinGame(@PathVariable String code, @Valid @RequestBody JoinGameRequest request) {
        Player joined = gameService.joinGame(code, request.playerName());
        Game game = gameService.getGame(code);
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return new JoinGameResponse(
                toResponse(game, joined.getId()),
                joined.getId()
        );
    }

    @PostMapping("/{code}/bots")
    public GameResponse addBot(@PathVariable String code, @Valid @RequestBody AddBotRequest request) {
        gameService.addBot(code, request.playerId(), request.botName());
        Game game = gameService.getGame(code);
        webSocketHandler.broadcastGameUpdated(code);
        return toResponse(game, request.playerId());
    }

    @GetMapping("/{code}")
    public GameResponse getGame(@PathVariable String code, @RequestParam(required = false) String viewerPlayerId) {
        return toResponse(gameService.getGame(code), viewerPlayerId);
    }

    @PostMapping("/{code}/start")
    public GameResponse startGame(@PathVariable String code, @Valid @RequestBody StartGameRequest request) {
        Game game = gameService.startGame(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/attack")
    public GameResponse attack(@PathVariable String code, @Valid @RequestBody AttackRequest request) {
        Game game = gameService.attack(code, request.playerId(), Card.fromCode(request.card()));
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/defend")
    public GameResponse defend(@PathVariable String code, @Valid @RequestBody DefendRequest request) {
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
    public GameResponse transfer(@PathVariable String code, @Valid @RequestBody TransferRequest request) {
        Game game = gameService.transfer(code, request.playerId(), Card.fromCode(request.card()));
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/take")
    public GameResponse take(@PathVariable String code, @Valid @RequestBody PlayerActionRequest request) {
        Game game = gameService.takeCards(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/end-round")
    public GameResponse endRound(@PathVariable String code, @Valid @RequestBody PlayerActionRequest request) {
        Game game = gameService.endRound(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code, game.getVersion());
        return toResponse(game, request.playerId());
    }

    @PostMapping("/{code}/leave")
    public void leaveGame(@PathVariable String code, @Valid @RequestBody PlayerActionRequest request) {
        gameService.leaveGame(code, request.playerId());
        webSocketHandler.broadcastGameUpdated(code);
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
