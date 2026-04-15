package com.galeforcesage.playon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages PlayOn Cloud authentication (JWT tokens).
 * <p>
 * The PlayOn API uses email/password login returning a JWT token with a
 * login_expiry (UNIX timestamp). This class handles login, token storage,
 * expiry tracking, and automatic re-authentication.
 */
public class PlayOnAuth {

    private static final Logger LOG = Logger.getLogger(PlayOnAuth.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String apiBase;

    private String jwt;
    private String authToken;
    private long loginExpiry; // UNIX timestamp (seconds)
    private String authenticatedEmail;

    public PlayOnAuth(HttpClient httpClient, ObjectMapper mapper, String apiBase) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.apiBase = apiBase;
    }

    /**
     * Authenticate with PlayOn Cloud using email and password.
     *
     * @return true if authentication was successful
     */
    public boolean login(String email, String password) {
        try {
            var body = mapper.createObjectNode();
            body.put("email", email);
            body.put("password", password);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/login"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PlayOnSageTVPlugin/1.0")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());

                // Extract JWT and auth_token
                this.jwt = extractString(json, "jwt");
                this.authToken = extractString(json, "auth_token");
                if (this.jwt == null) {
                    this.jwt = this.authToken; // fallback
                }

                // Extract login_expiry (UNIX timestamp)
                if (json.has("login_expiry")) {
                    this.loginExpiry = json.get("login_expiry").asLong();
                } else if (json.has("expires_in")) {
                    this.loginExpiry = (System.currentTimeMillis() / 1000) +
                            json.get("expires_in").asLong();
                } else {
                    // Default: 1 hour from now
                    this.loginExpiry = (System.currentTimeMillis() / 1000) + 3600;
                }

                this.authenticatedEmail = email;
                LOG.info("Authenticated with PlayOn Cloud as " + email +
                        " (expires at " + loginExpiry + ")");
                return this.jwt != null;
            } else {
                LOG.warning("PlayOn Cloud login failed: HTTP " + response.statusCode());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.SEVERE, "PlayOn Cloud login failed", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Returns true if we have a valid, non-expired JWT.
     */
    public boolean isAuthenticated() {
        return jwt != null && (System.currentTimeMillis() / 1000) < loginExpiry;
    }

    /**
     * Returns true if the token is within 5 minutes of expiry.
     */
    public boolean isTokenExpiring() {
        return jwt != null && (loginExpiry - System.currentTimeMillis() / 1000) < 300;
    }

    /**
     * Get the JWT for Authorization headers.
     */
    public String getJwt() {
        return jwt;
    }

    /**
     * Get the authenticated email address.
     */
    public String getAuthenticatedEmail() {
        return authenticatedEmail;
    }

    /**
     * Get the token expiry as a UNIX timestamp (seconds).
     */
    public long getLoginExpiry() {
        return loginExpiry;
    }

    /**
     * Clear authentication state (logout).
     */
    public void logout() {
        this.jwt = null;
        this.authToken = null;
        this.loginExpiry = 0;
        this.authenticatedEmail = null;
        LOG.info("Logged out from PlayOn Cloud");
    }

    private String extractString(JsonNode json, String field) {
        if (json.has(field) && !json.get(field).isNull()) {
            return json.get(field).asText();
        }
        return null;
    }
}
