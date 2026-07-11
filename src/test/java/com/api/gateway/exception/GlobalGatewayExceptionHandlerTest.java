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
