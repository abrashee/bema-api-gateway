package com.api.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import reactor.test.StepVerifier;

class GlobalGatewayExceptionHandlerTest {

    private final GlobalGatewayExceptionHandler handler =
            new GlobalGatewayExceptionHandler();

    @Test
    void returnsSafeResponseForUnexpectedExceptions() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build()
        );

        StepVerifier.create(
                handler.handle(
                        exchange,
                        new RuntimeException(
                                "internal database password leaked"
                        )
                )
        ).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        String body = exchange.getResponse()
                .getBody()
                .map(buffer -> StandardCharsets.UTF_8.decode(
                        buffer.asByteBuffer()
                ).toString())
                .blockFirst();

        assertThat(body)
                .isEqualTo(
                        "{\"statusCode\":500,"
                                + "\"message\":\"An unexpected error occurred\","
                                + "\"data\":null}"
                )
                .doesNotContain("database password");
    }

    @Test
    void preservesNotFoundStatusAndReason() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/missing-route").build()
        );

        StepVerifier.create(
                handler.handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Route not found"
                        )
                )
        ).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        String body = exchange.getResponse()
                .getBody()
                .map(buffer -> StandardCharsets.UTF_8.decode(
                        buffer.asByteBuffer()
                ).toString())
                .blockFirst();

        assertThat(body).isEqualTo(
                "{\"statusCode\":404,"
                        + "\"message\":\"Route not found\","
                        + "\"data\":null}"
        );
    }

    @Test
    void preservesMethodNotAllowedStatusAndReason() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.patch("/api/test").build()
        );

        StepVerifier.create(
                handler.handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.METHOD_NOT_ALLOWED,
                                "Method not allowed"
                        )
                )
        ).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        String body = exchange.getResponse()
                .getBody()
                .map(buffer -> StandardCharsets.UTF_8.decode(
                        buffer.asByteBuffer()
                ).toString())
                .blockFirst();

        assertThat(body).isEqualTo(
                "{\"statusCode\":405,"
                        + "\"message\":\"Method not allowed\","
                        + "\"data\":null}"
        );
    }

    @Test
    void preservesIntentionalClientErrorStatusAndReason() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test").build()
        );

        StepVerifier.create(
                handler.handle(
                        exchange,
                        new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Invalid gateway request"
                        )
                )
        ).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        String body = exchange.getResponse()
                .getBody()
                .map(buffer -> StandardCharsets.UTF_8.decode(
                        buffer.asByteBuffer()
                ).toString())
                .blockFirst();

        assertThat(body).isEqualTo(
                "{\"statusCode\":400,"
                        + "\"message\":\"Invalid gateway request\","
                        + "\"data\":null}"
        );
    }
}
