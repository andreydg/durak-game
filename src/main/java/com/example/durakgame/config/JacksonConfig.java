package com.example.durakgame.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 autoconfigures Jackson 3's {@code tools.jackson} mapper, not the classic
 * {@code com.fasterxml.jackson.databind.ObjectMapper} this app uses for its own serialization.
 * Expose a single shared instance so components inject one configured mapper instead of each
 * allocating their own.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
