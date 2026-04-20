package com.example.durakgame.controller;

import com.example.durakgame.controller.dto.LobbyGameSummary;
import com.example.durakgame.service.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class LobbyController {

    private final GameService gameService;

    public LobbyController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/lobbies")
    public List<LobbyGameSummary> listOpenLobbies() {
        return gameService.listOpenLobbies();
    }
}
