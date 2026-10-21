package com.ofss.service;

import com.ofss.dto.LoginRequest;
import com.ofss.dto.LoginResponse;

import com.ofss.entity.User;
import com.ofss.entity.UserStatus;

import com.ofss.repository.UserRepository;

import com.ofss.security.AuthUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthUtil authUtil;

    private AuthService authService;

    @BeforeEach
    void setUp() {

        authService =
                new AuthService(
                        userRepository,
                        userService,
                        passwordEncoder,
                        authUtil
                );
    }

    @Test
    void login_shouldReturnTokenForValidCredentials() {

        User user =
                createActiveRequester();

        LoginRequest request =
                new LoginRequest(
                        "requester1",
                        "password123"
                );

        List<String> roles =
                List.of(
                        "PAYMENT_REQUESTER"
                );

        when(
                userRepository
                        .findByUsername(
                                "requester1"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        when(
                passwordEncoder.matches(
                        "password123",
                        user.getPasswordHash()
                )
        ).thenReturn(true);

        when(
                userService.getRoles(
                        1L
                )
        ).thenReturn(roles);

        when(
                authUtil.generateJwtToken(
                        user,
                        roles
                )
        ).thenReturn(
                "test-jwt-token"
        );

        LoginResponse response =
                authService.login(
                        request
                );

        assertNotNull(
                response
        );

        assertEquals(
                "test-jwt-token",
                response.accessToken()
        );

        assertEquals(
                "Bearer",
                response.tokenType()
        );

        assertEquals(
                1L,
                response.userId()
        );

        assertEquals(
                "requester1",
                response.username()
        );

        assertEquals(
                "Requester One",
                response.fullName()
        );

        assertEquals(
                roles,
                response.roles()
        );

        verify(
                userRepository
        ).findByUsername(
                "requester1"
        );

        verify(
                passwordEncoder
        ).matches(
                "password123",
                user.getPasswordHash()
        );

        verify(
                userService
        ).getRoles(
                1L
        );

        verify(
                authUtil
        ).generateJwtToken(
                user,
                roles
        );
    }

    @Test
    void login_shouldRejectUnknownUsername() {

        LoginRequest request =
                new LoginRequest(
                        "unknownuser",
                        "password123"
                );

        when(
                userRepository
                        .findByUsername(
                                "unknownuser"
                        )
        ).thenReturn(
                Optional.empty()
        );

        BadCredentialsException exception =
                assertThrows(
                        BadCredentialsException.class,
                        () ->
                                authService.login(
                                        request
                                )
                );

        assertEquals(
                "Invalid username or password",
                exception.getMessage()
        );

        verify(
                passwordEncoder,
                never()
        ).matches(
                anyString(),
                anyString()
        );

        verify(
                userService,
                never()
        ).getRoles(
                anyLong()
        );

        verify(
                authUtil,
                never()
        ).generateJwtToken(
                any(User.class),
                anyList()
        );
    }

    @Test
    void login_shouldRejectInactiveUser() {

        User user =
                createActiveRequester();

        user.setStatus(
                UserStatus.INACTIVE
        );

        LoginRequest request =
                new LoginRequest(
                        "requester1",
                        "password123"
                );

        when(
                userRepository
                        .findByUsername(
                                "requester1"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        DisabledException exception =
                assertThrows(
                        DisabledException.class,
                        () ->
                                authService.login(
                                        request
                                )
                );

        assertEquals(
                "User account is inactive",
                exception.getMessage()
        );

        verify(
                passwordEncoder,
                never()
        ).matches(
                anyString(),
                anyString()
        );

        verify(
                userService,
                never()
        ).getRoles(
                anyLong()
        );

        verify(
                authUtil,
                never()
        ).generateJwtToken(
                any(User.class),
                anyList()
        );
    }

    @Test
    void login_shouldRejectWrongPassword() {

        User user =
                createActiveRequester();

        LoginRequest request =
                new LoginRequest(
                        "requester1",
                        "wrongPassword"
                );

        when(
                userRepository
                        .findByUsername(
                                "requester1"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        when(
                passwordEncoder.matches(
                        "wrongPassword",
                        user.getPasswordHash()
                )
        ).thenReturn(false);

        BadCredentialsException exception =
                assertThrows(
                        BadCredentialsException.class,
                        () ->
                                authService.login(
                                        request
                                )
                );

        assertEquals(
                "Invalid username or password",
                exception.getMessage()
        );

        verify(
                userService,
                never()
        ).getRoles(
                anyLong()
        );

        verify(
                authUtil,
                never()
        ).generateJwtToken(
                any(User.class),
                anyList()
        );
    }

    @Test
    void login_shouldLoadRolesAndGenerateJwt() {

        User user =
                createActiveRequester();

        LoginRequest request =
                new LoginRequest(
                        "requester1",
                        "password123"
                );

        List<String> roles =
                List.of(
                        "PAYMENT_REQUESTER"
                );

        when(
                userRepository
                        .findByUsername(
                                "requester1"
                        )
        ).thenReturn(
                Optional.of(user)
        );

        when(
                passwordEncoder.matches(
                        "password123",
                        user.getPasswordHash()
                )
        ).thenReturn(true);

        when(
                userService.getRoles(
                        user.getId()
                )
        ).thenReturn(roles);

        when(
                authUtil.generateJwtToken(
                        user,
                        roles
                )
        ).thenReturn(
                "generated-token"
        );

        LoginResponse response =
                authService.login(
                        request
                );

        assertEquals(
                "generated-token",
                response.accessToken()
        );

        assertEquals(
                roles,
                response.roles()
        );

        verify(
                userService,
                times(1)
        ).getRoles(
                user.getId()
        );

        verify(
                authUtil,
                times(1)
        ).generateJwtToken(
                user,
                roles
        );
    }

    private User createActiveRequester() {

        User user =
                new User();

        user.setId(
                1L
        );

        user.setUsername(
                "requester1"
        );

        user.setFullName(
                "Requester One"
        );

        user.setPasswordHash(
                "$2a$10$encodedPassword"
        );

        user.setStatus(
                UserStatus.ACTIVE
        );

        return user;
    }
}