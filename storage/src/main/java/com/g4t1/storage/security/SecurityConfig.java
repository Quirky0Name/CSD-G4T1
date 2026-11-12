package com.g4t1.storage.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${storage.jwt-secret}") String jwtSecret) {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        // Swagger's own pages only; every endpoint it calls still needs a token
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // the fake report's made-up PDFs, for a link opened in a new tab, which can't send a token
                        .requestMatchers("/public/fake-report/**").permitAll()
                        .anyRequest().hasRole("USER"))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthFilter(jwtSecret), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    // Spring's firewall turns away a ".." path with a 400 before any controller sees it. Under the public
    // route it gets the same 404 as any other name that isn't served (docs/CONTRACTS.md); elsewhere, the 400.
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        RequestRejectedHandler otherwise = new HttpStatusRequestRejectedHandler();
        return (request, response, rejected) -> {
            if (request.getRequestURI().startsWith("/public/fake-report/")) {
                response.sendError(HttpStatus.NOT_FOUND.value());
            } else {
                otherwise.handle(request, response, rejected);
            }
        };
    }
}
