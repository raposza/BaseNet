// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access for browser applications.
 *
 * A single-page application signing in with the authorization code flow calls
 * the discovery document, the token endpoint and the UserInfo endpoint from its
 * own origin, and reads the answers only when this service allows that origin.
 * Keycloak answers the same calls for the origins registered on a client. This
 * mint registers no clients, so every origin is allowed.
 *
 * No credentials are allowed, which is what lets the answer be `*`: none of
 * those calls carries a cookie.
 *
 * Author Claude/bentzn
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

}
