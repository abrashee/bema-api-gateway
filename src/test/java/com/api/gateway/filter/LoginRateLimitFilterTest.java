package com.api.gateway.filter;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoginRateLimitFilterTest {

    @Test
    void allowsLoginRequestWithinLimit() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(Flux.just(1L));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        LoginRateLimitFilter filter =
                new LoginRateLimitFilter(redisTemplate, 2, 60_000L);

        MockServerWebExchange exchange = loginExchange();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsLoginRequestAboveLimit() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(Flux.just(3L));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        LoginRateLimitFilter filter =
                new LoginRateLimitFilter(redisTemplate, 2, 60_000L);

        MockServerWebExchange exchange = loginExchange();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                exchange.getResponse().getStatusCode()
        );
        assertEquals(
                "60",
                exchange.getResponse()
                        .getHeaders()
                        .getFirst("Retry-After")
        );

        String body = exchange.getResponse()
                .getBodyAsString()
                .block();

        assertTrue(
                body != null
                        && body.contains("Too many login requests")
        );
    }

    @Test
    void doesNotRateLimitNonLoginAuthEndpoints() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        LoginRateLimitFilter filter =
                new LoginRateLimitFilter(redisTemplate, 2, 60_000L);

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/auth/refresh")
                                .remoteAddress(
                                        new InetSocketAddress(
                                                "203.0.113.10",
                                                54321
                                        )
                                )
                                .build()
                );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(chain).filter(exchange);
    }

    private MockServerWebExchange loginExchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .remoteAddress(
                                new InetSocketAddress(
                                        "203.0.113.10",
                                        54321
                                )
                        )
                        .build()
        );
    }
}
