package com.example.gateway.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        String clientIp = httpReq.getRemoteAddr();
        RateBucket bucket = buckets.computeIfAbsent(clientIp, k -> new RateBucket());
        long now = System.currentTimeMillis();
        if (now - bucket.windowStart > 60_000) {
            bucket.windowStart = now;
            bucket.requests.set(1);
        } else {
            bucket.requests.incrementAndGet();
        }

        if (bucket.requests.get() > MAX_REQUESTS_PER_MINUTE) {
            ((jakarta.servlet.http.HttpServletResponse) response).setStatus(429);
            response.getWriter().write("{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }

        log.info("[{}] {} {}", correlationId, httpReq.getMethod(), httpReq.getRequestURI());
        ((jakarta.servlet.http.HttpServletResponse) response).setHeader("X-Correlation-ID", correlationId);

        chain.doFilter(request, response);

        log.info("[{}] Completed in {}ms", correlationId, System.currentTimeMillis() - start);
    }

    private static class RateBucket {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger requests = new AtomicInteger(0);
    }
}
