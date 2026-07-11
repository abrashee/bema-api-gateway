package com.api.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=1"
        }
)
class SecurityHeadersTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void addsApiSecurityHeaders() {
        webTestClient.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(
                        "X-Content-Type-Options",
                        "nosniff"
                )
                .expectHeader().valueEquals(
                        "X-Frame-Options",
                        "DENY"
                )
                .expectHeader().valueEquals(
                        "Referrer-Policy",
                        "no-referrer"
                )
                .expectHeader().valueEquals(
                        "Permissions-Policy",
                        "camera=(), microphone=(), geolocation=()"
                )
                .expectHeader().valueEquals(
                        HttpHeaders.CACHE_CONTROL,
                        "no-cache, no-store, max-age=0, must-revalidate"
                );
    }
}
