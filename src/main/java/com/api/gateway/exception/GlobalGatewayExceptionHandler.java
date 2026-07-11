package com.api.gateway.exception;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalGatewayExceptionHandler implements WebExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalGatewayExceptionHandler.class);

    @Override
    public Mono<Void> handle(
            ServerWebExchange exchange,
            Throwable exception
    ) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }

        HttpStatusCode status = resolveStatus(exception);
        String message = resolveMessage(exception, status);

        if (status.is5xxServerError()) {
            log.error(
                    "Unhandled gateway exception method={} path={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    exception
            );
        }

        byte[] body = (
                "{\"statusCode\":" + status.value()
                        + ",\"message\":\"" + escapeJson(message)
                        + "\",\"data\":null}"
        ).getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(
                MediaType.APPLICATION_JSON
        );

        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private HttpStatusCode resolveStatus(Throwable exception) {
        if (exception instanceof ResponseStatusException responseStatus) {
            return responseStatus.getStatusCode();
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveMessage(
            Throwable exception,
            HttpStatusCode status
    ) {
        if (exception instanceof ResponseStatusException responseStatus
                && !status.is5xxServerError()
                && responseStatus.getReason() != null
                && !responseStatus.getReason().isBlank()) {
            return responseStatus.getReason();
        }

        if (status.is5xxServerError()) {
            return "An unexpected error occurred";
        }

        HttpStatus resolved = HttpStatus.resolve(status.value());

        return resolved != null
                ? resolved.getReasonPhrase()
                : "Request failed";
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
