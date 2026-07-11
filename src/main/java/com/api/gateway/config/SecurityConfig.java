package com.api.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER
                        ))
                        .permissionsPolicy(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=()"
                        ))
                )
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/**").permitAll()
                        .anyExchange().permitAll()
                )
                .build();
    }
}