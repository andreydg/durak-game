package com.example.durakgame.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegacyCardImageControllerTest {
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LegacyCardImageController()).build();

    @Test
    void everyOldCardUrlRedirectsToAWebpFileThatExists() throws Exception {
        assertEquals(37, LegacyCardImageController.CARD_NAMES.size(), "36 cards and the back");
        for (String name : LegacyCardImageController.CARD_NAMES) {
            String target = "/cards/" + name + ".webp";
            mockMvc.perform(get("/cards/{name}.png", name))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", target))
                    .andExpect(header().doesNotExist("Cache-Control"));
            assertTrue(new ClassPathResource("static" + target).exists(), target);
        }
    }

    @Test
    void otherPngNamesUnderCardsAreNotFound() throws Exception {
        for (String name : List.of("5C", "11H", "AX", "7h", "back", "social-card")) {
            mockMvc.perform(get("/cards/{name}.png", name)).andExpect(status().isNotFound());
        }
    }
}
