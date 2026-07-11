package com.api.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CORS_ALLOWED_ORIGIN=https://app.example.test",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=1"
        }
)
class CorsConfigurationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void allowsConfiguredFrontendPreflight() {
        webTestClient.options()
                .uri("/api/auth/login")
                .header(
                        HttpHeaders.ORIGIN,
                        "https://app.example.test"
                )
                .header(
                        HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                        HttpMethod.POST.name()
                )
                .header(
                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                        "authorization,content-type"
                )
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://app.example.test"
                )
                .expectHeader().value(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        value -> assertThat(value).contains("POST")
                )
                .expectHeader().value(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value.toLowerCase())
                                .contains("authorization")
                                .contains("content-type")
                )
                .expectHeader().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS
                );
    }

    @Test
    void rejectsUnconfiguredFrontendOrigin() {
        webTestClient.options()
                .uri("/api/auth/login")
                .header(
                        HttpHeaders.ORIGIN,
                        "https://attacker.example.test"
                )
                .header(
                        HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                        HttpMethod.POST.name()
                )
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
                );
    }
}
