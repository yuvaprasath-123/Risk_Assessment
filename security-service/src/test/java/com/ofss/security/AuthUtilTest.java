package com.ofss.security;

import com.ofss.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthUtilTest {

    private AuthUtil authUtil;

    @BeforeEach
    void setUp() {

        authUtil =
                new AuthUtil();

        ReflectionTestUtils.setField(
                authUtil,
                "jwtSecretKey",
                "this-is-a-very-long-secret-key-for-jwt-signing-123456789"
        );

        ReflectionTestUtils.setField(
                authUtil,
                "expirationMinutes",
                10L
        );

        SecurityContextHolder.clearContext();
    }

    @Test
    void generateAndVerifyToken_shouldContainUserIdAndUsername() {

        User user =
                new User();

        user.setId(1L);
        user.setUsername(
                "requester1"
        );

        List<String> roles =
                List.of(
                        "PAYMENT_REQUESTER"
                );

        String token =
                authUtil.generateJwtToken(
                        user,
                        roles
                );

        assertNotNull(
                token
        );

        JwtUserPrinciple principal =
                authUtil.verifyJwtToken(
                        token
                );

        assertEquals(
                1L,
                principal.userId()
        );

        assertEquals(
                "requester1",
                principal.username()
        );
    }

    @Test
    void generateAndVerifyToken_shouldContainRequesterRole() {

        User user =
                new User();

        user.setId(1L);
        user.setUsername(
                "requester1"
        );

        String token =
                authUtil.generateJwtToken(
                        user,
                        List.of(
                                "PAYMENT_REQUESTER"
                        )
                );

        JwtUserPrinciple principal =
                authUtil.verifyJwtToken(
                        token
                );

        List<String> authorities =
                principal
                        .authorities()
                        .stream()
                        .map(
                                GrantedAuthority
                                        ::getAuthority
                        )
                        .toList();

        assertTrue(
                authorities.contains(
                        "ROLE_PAYMENT_REQUESTER"
                )
        );
    }

    @Test
    void generateAndVerifyToken_shouldContainFinanceApproverRole() {

        User user =
                new User();

        user.setId(2L);
        user.setUsername(
                "approver1"
        );

        String token =
                authUtil.generateJwtToken(
                        user,
                        List.of(
                                "FINANCE_APPROVER"
                        )
                );

        JwtUserPrinciple principal =
                authUtil.verifyJwtToken(
                        token
                );

        List<String> authorities =
                principal
                        .authorities()
                        .stream()
                        .map(
                                GrantedAuthority
                                        ::getAuthority
                        )
                        .toList();

        assertTrue(
                authorities.contains(
                        "ROLE_FINANCE_APPROVER"
                )
        );
    }

    @Test
    void generateAndVerifyToken_shouldContainFinanceManagerRole() {

        User user =
                new User();

        user.setId(3L);
        user.setUsername(
                "manager1"
        );

        String token =
                authUtil.generateJwtToken(
                        user,
                        List.of(
                                "FINANCE_MANAGER"
                        )
                );

        JwtUserPrinciple principal =
                authUtil.verifyJwtToken(
                        token
                );

        List<String> authorities =
                principal
                        .authorities()
                        .stream()
                        .map(
                                GrantedAuthority
                                        ::getAuthority
                        )
                        .toList();

        assertTrue(
                authorities.contains(
                        "ROLE_FINANCE_MANAGER"
                )
        );
    }

    @Test
    void verifyJwtToken_shouldRejectInvalidToken() {

        assertThrows(
                Exception.class,
                () ->
                        authUtil.verifyJwtToken(
                                "invalid.jwt.token"
                        )
        );
    }

    @Test
    void getCurrentUserId_shouldReturnAuthenticatedUserId() {

        JwtUserPrinciple principal =
                new JwtUserPrinciple(
                        5L,
                        "requester5",
                        List.of()
                );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities()
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        authentication
                );

        Long currentUserId =
                authUtil.getCurrentUserId();

        assertEquals(
                5L,
                currentUserId
        );
    }

    @Test
    void getCurrentUserId_shouldRejectWhenUserIsNotAuthenticated() {

        SecurityContextHolder.clearContext();

        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                () ->
                        authUtil.getCurrentUserId()
        );
    }
}