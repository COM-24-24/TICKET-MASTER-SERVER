package com.ticketmaster.ticketmaster.user.dto;

public class AuthResponse {
    private String token;
    private String refreshToken;
    private AuthUserResponse user;

    public AuthResponse() {}

    public AuthResponse(String token, String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
    }

    public AuthResponse(String token, String refreshToken, AuthUserResponse user) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.user = user;
    }

    public static AuthResponseBuilder builder() {
        return new AuthResponseBuilder();
    }

    public static class AuthResponseBuilder {
        private String token;
        private String refreshToken;
        private AuthUserResponse user;

        public AuthResponseBuilder token(String token) { this.token = token; return this; }
        public AuthResponseBuilder refreshToken(String refreshToken) { this.refreshToken = refreshToken; return this; }
        public AuthResponseBuilder user(AuthUserResponse user) { this.user = user; return this; }

        public AuthResponse build() {
            return new AuthResponse(token, refreshToken, user);
        }
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public AuthUserResponse getUser() { return user; }
    public void setUser(AuthUserResponse user) { this.user = user; }
}