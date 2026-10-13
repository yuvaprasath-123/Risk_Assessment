package com.ofss.configuration.security;

import com.ofss.configuration.error.ErrorResponseWriter;
import com.ofss.configuration.version.ConfigurationVersionController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    UserDetailsService localAccountsDisabled() {
        // Suppress Boot's generated development user; authentication is JWT-only.
        return username -> { throw new UsernameNotFoundException("Local accounts are not supported"); };
    }

    @Bean
    SecurityFilterChain configurationSecurity(HttpSecurity http, JwtVerifier verifier,
            ErrorResponseWriter errors) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, ConfigurationVersionController.PATH)
                        .hasRole("FINANCE_MANAGER")
                        .requestMatchers(HttpMethod.POST, ConfigurationVersionController.PATH)
                        .hasRole("FINANCE_MANAGER")
                        .requestMatchers(HttpMethod.GET, ConfigurationVersionController.PATH + "/*",
                                ConfigurationVersionController.PATH + "/*/risk-rules")
                        .hasRole("FINANCE_MANAGER")
                        .requestMatchers(HttpMethod.PUT, ConfigurationVersionController.PATH + "/*/risk-rules/*")
                        .hasRole("FINANCE_MANAGER")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errors.unauthorized(
                                request, response, false))
                        .accessDeniedHandler((request, response, exception) -> errors.write(
                                request, response, 403, "FORBIDDEN", "Access is denied")))
                .addFilterBefore(new JwtAuthenticationFilter(verifier, errors),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
