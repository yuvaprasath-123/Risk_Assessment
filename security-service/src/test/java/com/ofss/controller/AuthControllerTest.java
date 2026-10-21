package com.ofss.controller;

import com.ofss.dto.LoginRequest;
import com.ofss.security.AuthUtil;
import com.ofss.service.AuthService;
import com.ofss.service.UserService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import org.springframework.http.MediaType;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.ofss.dto.LoginResponse;
import com.ofss.dto.UserResponse;

import com.ofss.entity.UserStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(AuthController.class)
@Import(AuthControllerTest.TestSecurityConfiguration.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthUtil authUtil;

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfiguration {

    	@Bean
        SecurityFilterChain securityFilterChain(
                HttpSecurity http
        ) throws Exception {

            http
                    .csrf(
                            csrf ->
                                    csrf.disable()
                    )

                    .exceptionHandling(
                            exception ->
                                    exception
                                            .authenticationEntryPoint(
                                                    (request,
                                                     response,
                                                     authException) -> {

                                                        response.setStatus(
                                                                401
                                                        );
                                                    }
                                            )
                    )

                    .authorizeHttpRequests(
                            auth ->
                                    auth
                                            .requestMatchers(
                                                    "/api/v1/auth/login"
                                            )
                                            .permitAll()

                                            .anyRequest()
                                            .authenticated()
                    )

                    .httpBasic(
                            httpBasic ->
                                    httpBasic.disable()
                    )

                    .formLogin(
                            formLogin ->
                                    formLogin.disable()
                    );

            return http.build();
        }
    }

    @Test
    void blankPasswordShouldReturnBadRequest()
            throws Exception {

        LoginRequest request =
                new LoginRequest(
                        "requester1",
                        ""
                );

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper
                                                .writeValueAsString(request)
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }

    @Test
    void blankUsernameShouldReturnBadRequest()
            throws Exception {

        LoginRequest request =
                new LoginRequest(
                        "",
                        "password123"
                );

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper
                                                .writeValueAsString(request)
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }
    
    @Test
    void meShouldReturnCurrentUserWhenAuthenticated()

    
            throws Exception {

        when(
                authUtil.getCurrentUserId()
        ).thenReturn(
                1L
        );

        UserResponse response =
                new UserResponse(
                        1L,
                        "requester1",
                        "Requester One",
                        "requester1@test.com",
                        UserStatus.ACTIVE,
                        List.of(
                                "PAYMENT_REQUESTER"
                        )
                );

        when(
                userService.getUser(
                        1L
                )
        ).thenReturn(
                response
        );

        mockMvc.perform(
                        get(
                                "/api/v1/auth/me"
                        )
                                .with(
                                        user(
                                                "requester1"
                                        )
                                                .roles(
                                                        "PAYMENT_REQUESTER"
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.userId"
                        ).value(
                                1
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.username"
                        ).value(
                                "requester1"
                        )
                );
    }
    
    @Test
    void meShouldReturnUnauthorizedWhenNotAuthenticated()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/v1/auth/me"
                        )
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }
    
    @Test
    void validLoginShouldReturnOk()
            throws Exception {

        LoginRequest request =
                new LoginRequest(
                        "requester1",
                        "password123"
                );

        LoginResponse response =
                new LoginResponse(
                        "test-token",
                        "Bearer",
                        1L,
                        "requester1",
                        "Requester One",
                        List.of(
                                "PAYMENT_REQUESTER"
                        )
                );

        when(
                authService.login(
                        any(LoginRequest.class)
                )
        ).thenReturn(
                response
        );

        mockMvc.perform(
                        post(
                                "/api/v1/auth/login"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper
                                                .writeValueAsString(
                                                        request
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.accessToken"
                        ).value(
                                "test-token"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.tokenType"
                        ).value(
                                "Bearer"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.userId"
                        ).value(
                                1
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.username"
                        ).value(
                                "requester1"
                        )
                );
    }
}