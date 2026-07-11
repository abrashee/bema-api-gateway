package com.api.gateway.filter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;

import reactor.core.publisher.Mono;

@Component
public class LoginRateLimitFilter implements GlobalFilter, Ordered {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String KEY_PREFIX = "rate-limit:login:";

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    return count
                    """,
                    Long.class
            );

    private final ReactiveStringRedisTemplate redisTemplate;
    private final int maxRequests;
    private final long windowMs;

    public LoginRateLimitFilter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${security.login-rate-limit.max-requests:10}")
            int maxRequests,
            @Value("${security.login-rate-limit.window-ms:60000}")
            long windowMs
    ) {
        if (maxRequests <= 0) {
            throw new IllegalStateException(
                    "Login rate-limit max requests must be positive"
            );
        }

        if (windowMs <= 0) {
            throw new IllegalStateException(
                    "Login rate-limit window must be positive"
            );
        }

        this.redisTemplate = redisTemplate;
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {
        if (!isLoginRequest(exchange)) {
            return chain.filter(exchange);
        }

        String key = KEY_PREFIX + resolveClientAddress(exchange);

        return redisTemplate.execute(
                        INCREMENT_SCRIPT,
                        List.of(key),
                        List.of(Long.toString(windowMs))
                )
                .single()
                .flatMap(count -> {
                    if (count <= maxRequests) {
                        return chain.filter(exchange);
                    }

                    return reject(exchange);
                });
    }

    private boolean isLoginRequest(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && LOGIN_PATH.equals(
                        exchange.getRequest().getPath().value()
                );
    }

    private String resolveClientAddress(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress =
                exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null
                || remoteAddress.getAddress() == null) {
            return "unknown";
        }

        return remoteAddress.getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        long retryAfterSeconds =
                Math.max(1L, (windowMs + 999L) / 1000L);

        byte[] body = """
                {"message":"Too many login requests","data":null}
                """.trim().getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(
                HttpStatus.TOO_MANY_REQUESTS
        );
        exchange.getResponse().getHeaders().setContentType(
                MediaType.APPLICATION_JSON
        );
        exchange.getResponse().getHeaders().set(
                "Retry-After",
                Long.toString(retryAfterSeconds)
        );

        return exchange.getResponse().writeWith(
                Mono.just(
                        exchange.getResponse()
                                .bufferFactory()
                                .wrap(body)
                )
        );
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
