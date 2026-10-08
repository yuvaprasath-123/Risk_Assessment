package com.ofss.config;

import com.ofss.security.CustomAccessDeniedHandler;
import com.ofss.security.JwtAuthFilter;
import com.ofss.security.JwtAuthenticationEntryPoint;



import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.web.SecurityFilterChain;

import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration

public class SecurityConfiguration {


    
	 	private final JwtAuthFilter jwtAuthFilter;
	    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
	    private final CustomAccessDeniedHandler customAccessDeniedHandler;

	    public SecurityConfiguration(
	            JwtAuthFilter jwtAuthFilter,
	            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
	            CustomAccessDeniedHandler customAccessDeniedHandler) {

	        this.jwtAuthFilter = jwtAuthFilter;
	        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
	        this.customAccessDeniedHandler = customAccessDeniedHandler;
	    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    )
            throws Exception {

        http

                .csrf(
                        csrf ->
                                csrf.disable()
                )

                .sessionManagement(
                        session ->
                                session
                                        .sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS
                                        )
                )

                .exceptionHandling(
                        exception ->
                                exception
                                        .authenticationEntryPoint(
                                                jwtAuthenticationEntryPoint
                                        )
                                        .accessDeniedHandler(
                                                customAccessDeniedHandler
                                        )
                )

                .authorizeHttpRequests(
                        auth ->
                                auth

                                        .requestMatchers(
                                                "/api/v1/auth/login",
                                                "/api/v1/users"
                                        )
                                        .permitAll()

                                        .anyRequest()
                                        .authenticated()
                )

                .addFilterBefore(
                        jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {

        return new BCryptPasswordEncoder();
    }
}
