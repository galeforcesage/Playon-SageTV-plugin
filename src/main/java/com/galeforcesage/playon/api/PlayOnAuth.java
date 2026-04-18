package com.galeforcesage.playon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages PlayOn Cloud authentication (JWT tokens).
 * <p>
 * The PlayOn API uses email/password login returning a JWT token with a
 * login_expiry (UNIX timestamp). This class handles login, token storage,
 * expiry tracking, and automatic re-authentication.
 * Uses HttpURLConnection for Java 8 compatibility.
 */
public class PlayOnAuth {

    private static final Logger LOG = Logger.getLogger(PlayOnAuth.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final ObjectMapper mapper;
    private final String apiBase;

    private String jwt;
    private String authToken;
    private long loginExpiry; // UNIX timestamp (seconds)
    private String authenticatedEmail;

    public PlayOnAuth(ObjectMapper mapper, String apiBase) {
        this.mapper = mapper;
        this.apiBase = apiBase;
    }

    /**
     * Authenticate with PlayOn Cloud using email and password.
     *
     * @return true if authentication was successful
     */
    public boolean login(String email, String password) {
        HttpURLConnection conn = null;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("email", email);
            body.put("password", password);
            String jsonBody = mapper.writeValueAsString(body);

            conn = (HttpURLConnection) new URL(apiBase + "/login").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "PlayOnSageTVPlugin/1.0");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                String responseBody = readResponse(conn);
                JsonNode json = mapper.readTree(responseBody);
                LOG.info("PlayOn login response (keys): " + getResponseKeys(responseBody));

                // PlayOn API wraps response in {success, data} envelope
                JsonNode data = json;
                if (json.has("data") && json.get("data").isObject()) {
                    data = json.get("data");
                    LOG.info("PlayOn login data keys: " + fieldList(data));
                }

                // Extract both token types — PlayOn returns both 'token' and 'auth_token'
                String tokenVal = extractString(data, "token");
                String authTokenVal = extractString(data, "auth_token");
                // Prefer auth_token for API calls; fall back to token
                this.authToken = authTokenVal != null ? authTokenVal : tokenVal;
                this.jwt = tokenVal != null ? tokenVal : authTokenVal;
                // Also try other field names if both were null
                if (this.authToken == null) this.authToken = extractString(data, "jwt");
                if (this.authToken == null) this.authToken = extractString(data, "access_token");
                if (this.authToken == null) this.authToken = extractString(data, "session_token");
                if (this.jwt == null) this.jwt = this.authToken;
                LOG.info("Token extracted (auth_token=" + (authTokenVal != null ? authTokenVal.substring(0, Math.min(8, authTokenVal.length())) + "..." : "null") +
                        ", token=" + (tokenVal != null ? tokenVal.substring(0, Math.min(8, tokenVal.length())) + "..." : "null") + ")");

                // Extract login_expiry — check data envelope first, then top level
                if (data.has("login_expiry")) {
                    this.loginExpiry = data.get("login_expiry").asLong();
                } else if (json.has("login_expiry")) {
                    this.loginExpiry = json.get("login_expiry").asLong();
                } else if (data.has("expires_in")) {
                    this.loginExpiry = (System.currentTimeMillis() / 1000) +
                            data.get("expires_in").asLong();
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
                LOG.warning("PlayOn Cloud login failed: HTTP " + status);
                return false;
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "PlayOn Cloud login failed", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public boolean isAuthenticated() {
        return jwt != null && (System.currentTimeMillis() / 1000) < loginExpiry;
    }

    public boolean isTokenExpiring() {
        return jwt != null && (loginExpiry - System.currentTimeMillis() / 1000) < 300;
    }

    public String getJwt() {
        return jwt;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getAuthenticatedEmail() {
        return authenticatedEmail;
    }

    public long getLoginExpiry() {
        return loginExpiry;
    }

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

    private String readResponse(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toString("UTF-8");
        }
    }

    private String getResponseKeys(String responseBody) {
        try {
            JsonNode json = mapper.readTree(responseBody);
            return fieldList(json);
        } catch (Exception e) {
            return "(parse error)";
        }
    }

    private String fieldList(JsonNode node) {
        StringBuilder sb = new StringBuilder("[");
        java.util.Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(names.next());
        }
        sb.append("]");
        return sb.toString();
    }
}
