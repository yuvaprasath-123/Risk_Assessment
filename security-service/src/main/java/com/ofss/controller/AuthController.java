package com.ofss.controller;

import com.ofss.dto.*;

import com.ofss.security.AuthUtil;

import com.ofss.service.AuthService;
import com.ofss.service.UserService;

import jakarta.validation.Valid;



import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")

public class AuthController {

    private final AuthService authService;

    private final UserService userService;

    private final AuthUtil authUtil;
    
    public AuthController(AuthService authService, UserService userService, AuthUtil authUtil) {
    	this.authService = authService;
    	this.userService = userService;
    	this.authUtil = authUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid
            @RequestBody
            LoginRequest request
    ) {

        return ResponseEntity.ok(
                authService.login(
                        request
                )
        );
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {

        Long userId =
                authUtil.getCurrentUserId();

        return ResponseEntity.ok(
                userService.getUser(
                        userId
                )
        );
    }
}