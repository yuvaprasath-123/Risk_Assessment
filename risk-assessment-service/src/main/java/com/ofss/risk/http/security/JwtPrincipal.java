package com.ofss.risk.http.security;

public record JwtPrincipal(long userId, String username) {
}
