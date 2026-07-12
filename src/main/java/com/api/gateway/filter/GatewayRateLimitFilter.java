package com.api.gateway.filter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(GatewayRateLimitFilter.class);

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String ACTUATOR_PATH = "/actuator";
    private static final String KEY_PREFIX = "rate-limit:gateway:";

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

    public GatewayRateLimitFilter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${security.gateway-rate-limit.max-requests:300}")
            int maxRequests,
            @Value("${security.gateway-rate-limit.window-ms:60000}")
            long windowMs
    ) {
        if (maxRequests <= 0) {
            throw new IllegalStateException(
                    "Gateway rate-limit max requests must be positive"
            );
        }

        if (windowMs <= 0) {
            throw new IllegalStateException(
                    "Gateway rate-limit window must be positive"
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
        if (isExcluded(exchange)) {
            return chain.filter(exchange);
        }

        String clientAddress = resolveClientAddress(exchange);

        if (clientAddress == null) {
            log.warn(
                    "Gateway rate limiting skipped because client address "
                            + "could not be resolved method={} path={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value()
            );
            return chain.filter(exchange);
        }

        String key = KEY_PREFIX + clientAddress;

        Mono<Optional<Long>> requestCount =
                redisTemplate.execute(
                                INCREMENT_SCRIPT,
                                List.of(key),
                                List.of(Long.toString(windowMs))
                        )
                        .single()
                        .map(Optional::of)
                        .onErrorResume(exception -> {
                            log.error(
                                    "Gateway rate limiting unavailable; "
                                            + "request allowed method={} "
                                            + "path={} clientAddress={}",
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getPath().value(),
                                    clientAddress,
                                    exception
                            );
                            return Mono.just(Optional.empty());
                        });

        return requestCount.flatMap(optionalCount -> {
            if (optionalCount.isEmpty()) {
                return chain.filter(exchange);
            }

            long count = optionalCount.get();

            if (count <= maxRequests) {
                return chain.filter(exchange);
            }

            log.warn(
                    "security_audit event=GATEWAY_RATE_LIMIT "
                            + "outcome=DENIED clientAddress={} "
                            + "method={} path={} requestCount={} "
                            + "maxRequests={}",
                    clientAddress,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    count,
                    maxRequests
            );

            return reject(exchange);
        });
    }

    private boolean isExcluded(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();

        boolean loginRequest =
                HttpMethod.POST.equals(exchange.getRequest().getMethod())
                        && LOGIN_PATH.equals(path);

        boolean actuatorRequest =
                ACTUATOR_PATH.equals(path)
                        || path.startsWith(ACTUATOR_PATH + "/");

        return loginRequest || actuatorRequest;
    }

    private String resolveClientAddress(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress =
                exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null
                || remoteAddress.getAddress() == null) {
            return null;
        }

        return remoteAddress.getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        long retryAfterSeconds =
                Math.max(1L, (windowMs + 999L) / 1000L);

        byte[] body = """
                {"statusCode":429,"message":"Too many requests","data":null}
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
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
