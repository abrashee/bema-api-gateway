package com.api.gateway.filter;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
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

class GatewayRateLimitFilterTest {

    @Test
    void allowsRequestWithinLimit() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(Flux.just(1L));

        GatewayFilterChain chain = successfulChain();

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(HttpMethodValue.GET, "/api/policies");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsRequestAboveLimit() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(Flux.just(3L));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(HttpMethodValue.GET, "/api/policies");

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
                        && body.contains("\"statusCode\":429")
                        && body.contains("Too many requests")
        );
    }

    @Test
    void excludesLoginFromGatewayWideLimit() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        GatewayFilterChain chain = successfulChain();

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(HttpMethodValue.POST, "/api/auth/login");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(chain).filter(exchange);
    }

    @Test
    void excludesActuatorRequests() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        GatewayFilterChain chain = successfulChain();

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(HttpMethodValue.GET, "/actuator/health");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(chain).filter(exchange);
    }

    @Test
    void includesSocketIoRequests() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(Flux.just(3L));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(
                        HttpMethodValue.GET,
                        "/socket.io/?EIO=4&transport=polling"
                );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                exchange.getResponse().getStatusCode()
        );
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(
                        Flux.error(
                                new RedisConnectionFailureException(
                                        "Redis unavailable"
                                )
                        )
                );

        GatewayFilterChain chain = successfulChain();

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(HttpMethodValue.GET, "/api/users");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void doesNotRetryChainWhenDownstreamFails() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        when(redisTemplate.execute(any(), any(), any(java.util.List.class)))
                .thenReturn(Flux.just(1L));

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any()))
                .thenReturn(Mono.error(new IllegalStateException(
                        "Downstream failure"
                )));

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                exchange(HttpMethodValue.GET, "/api/policies");

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorMatches(exception ->
                        exception instanceof IllegalStateException
                                && "Downstream failure".equals(
                                        exception.getMessage()
                                )
                )
                .verify();

        verify(chain, times(1)).filter(exchange);
    }

    @Test
    void skipsLimitWhenClientAddressCannotBeResolved() {
        ReactiveStringRedisTemplate redisTemplate =
                mock(ReactiveStringRedisTemplate.class);

        GatewayFilterChain chain = successfulChain();

        GatewayRateLimitFilter filter =
                new GatewayRateLimitFilter(
                        redisTemplate,
                        2,
                        60_000L
                );

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/users").build()
                );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(chain).filter(exchange);
    }

    private GatewayFilterChain successfulChain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private MockServerWebExchange exchange(
            HttpMethodValue method,
            String path
    ) {
        MockServerHttpRequest.BaseBuilder<?> request =
                method == HttpMethodValue.POST
                        ? MockServerHttpRequest.post(path)
                        : MockServerHttpRequest.get(path);

        return MockServerWebExchange.from(
                request.remoteAddress(
                                new InetSocketAddress(
                                        "203.0.113.10",
                                        54321
                                )
                        )
                        .build()
        );
    }

    private enum HttpMethodValue {
        GET,
        POST
    }
}
