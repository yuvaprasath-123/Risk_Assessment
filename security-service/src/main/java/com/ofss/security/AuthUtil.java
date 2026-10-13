package com.ofss.security;

import com.ofss.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;

import java.util.Date;
import java.util.List;

@Component
public class AuthUtil {

    @Value("${jwt.secret-key}")
    private String jwtSecretKey;

    @Value("${jwt.expiration-minutes}")
    private long expirationMinutes;

    private SecretKey getSecretKey() {

        return Keys.hmacShaKeyFor(
                jwtSecretKey.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    public String generateJwtToken(
            User user,
            List<String> roles
    ) {

        long expirationMillis =
                expirationMinutes
                        * 60
                        * 1000;

        return Jwts.builder()

                .subject(user.getUsername())

                .claim(
                        "userId",
                        String.valueOf(
                                user.getId()
                        )
                )

                .claim(
                        "roles",
                        roles
                )

                .issuedAt(new Date())

                .expiration(
                        new Date(
                                System.currentTimeMillis()
                                        + expirationMillis
                        )
                )

                .signWith(
                        getSecretKey()
                )

                .compact();
    }

    @SuppressWarnings("unchecked")
    public JwtUserPrinciple verifyJwtToken(
            String token
    ) {

        Claims claims =
                Jwts.parser()
                        .verifyWith(
                                getSecretKey()
                        )
                        .build()
                        .parseSignedClaims(
                                token
                        )
                        .getPayload();

        Long userId =
                Long.parseLong(
                        claims.get(
                                "userId",
                                String.class
                        )
                );

        String username =
                claims.getSubject();

        List<String> roles =
                claims.get(
                        "roles",
                        List.class
                );

        if (roles == null) {
            roles = List.of();
        }

        List<GrantedAuthority> authorities =
                roles.stream()
                        .map(role ->
                                new SimpleGrantedAuthority(
                                        "ROLE_" + role
                                )
                        )
                        .map(
                                GrantedAuthority.class::cast
                        )
                        .toList();

        return new JwtUserPrinciple(
                userId,
                username,
                authorities
        );
    }

    public Long getCurrentUserId() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (
                authentication == null
                        ||
                        !(authentication.getPrincipal()
                                instanceof JwtUserPrinciple principal)
        ) {

            throw new AuthenticationCredentialsNotFoundException(
                    "No authenticated user found"
            );
        }

        return principal.userId();
    }
}