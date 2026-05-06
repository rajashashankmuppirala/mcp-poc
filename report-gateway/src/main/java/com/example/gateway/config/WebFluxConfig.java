package com.example.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux configuration for serving static HTML and resources.
 */
@Configuration
public class WebFluxConfig {

    @Bean
    public RouterFunction<ServerResponse> staticResources() {
        return RouterFunctions.resources("/**", new ClassPathResource("static/"));
    }
}
