package com.example.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebFlux filter for correlation ID generation, request logging, and rate limiting.
 * Replaces the servlet-based RequestLoggingFilter.
 */
@Component
public class RequestLoggingWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingWebFilter.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";

        // Rate limiting per client IP
        RateBucket bucket = buckets.computeIfAbsent(clientIp, k -> new RateBucket());
        long now = System.currentTimeMillis();
        if (now - bucket.windowStart > 60_000) {
            bucket.windowStart = now;
            bucket.requests.set(1);
        } else {
            bucket.requests.incrementAndGet();
        }

        if (bucket.requests.get() > MAX_REQUESTS_PER_MINUTE) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(body);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        log.info("[{}] {} {}", correlationId, exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath());
        exchange.getResponse().getHeaders().set("X-Correlation-ID", correlationId);

        return chain.filter(exchange)
                .doFinally(signalType -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[{}] Completed in {}ms", correlationId, elapsed);
                });
    }

    @Override
    public int getOrder() { return -1; }

    private static class RateBucket {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger requests = new AtomicInteger(0);
    }
}
