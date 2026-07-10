package com.api.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);
    private static final Pattern VALID_CORRELATION_ID =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final Tracer tracer;

    public LoggingFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolveCorrelationId(
                exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER)
        );

        ServerWebExchange correlatedExchange = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(CORRELATION_ID_HEADER);
                    headers.add(CORRELATION_ID_HEADER, correlationId);
                }))
                .build();

        correlatedExchange.getResponse().beforeCommit(() -> {
            correlatedExchange.getResponse()
                    .getHeaders()
                    .set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(correlatedExchange)
                .doOnSubscribe(ignored -> logRequest(exchange, correlationId));
    }

    private void logRequest(
            ServerWebExchange exchange,
            String correlationId
    ) {
        Span span = tracer.currentSpan();

        try {
            if (span != null) {
                MDC.put("traceId", span.context().traceId());
                MDC.put("spanId", span.context().spanId());
            }

            MDC.put("correlationId", correlationId);

            log.info(
                    "Incoming request method={} path={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value()
            );
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
            MDC.remove("correlationId");
        }
    }

    private String resolveCorrelationId(String suppliedCorrelationId) {
        if (suppliedCorrelationId != null
                && VALID_CORRELATION_ID.matcher(suppliedCorrelationId).matches()) {
            return suppliedCorrelationId;
        }

        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
