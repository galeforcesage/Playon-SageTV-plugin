package com.galeforcesage.playon.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galeforcesage.playon.api.models.PlayOnAccount;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.api.models.PlayOnService;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the PlayOn Cloud API.
 * <p>
 * Implements the API surface documented from the cs.app.playon Python package:
 * login, available(), queue(), download(), services(), account(), notifications(),
 * features(), and mark-as-downloaded.
 * <p>
 * All API calls use HTTPS with JWT authorization.
 * Uses HttpURLConnection for Java 8 compatibility.
 */
public class PlayOnApiClient {

    private static final Logger LOG = Logger.getLogger(PlayOnApiClient.class.getName());
    private static final String DEFAULT_API_BASE = "https://api.playonrecorder.com/v3";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int DOWNLOAD_TIMEOUT_MS = 4 * 60 * 60 * 1000; // 4 hours
    private static final String USER_AGENT = "PlayOnSageTVPlugin/1.0";
    private static final int BUFFER_SIZE = 8192;

    private final ObjectMapper mapper;
    private final PlayOnAuth auth;
    private final String apiBase;

    // Stored credentials for automatic re-authentication
    private String storedEmail;
    private String storedPassword;

    public PlayOnApiClient() {
        this(DEFAULT_API_BASE);
    }

    public PlayOnApiClient(String apiBase) {
        this.apiBase = apiBase;
        this.mapper = new ObjectMapper();
        this.auth = new PlayOnAuth(mapper, apiBase);
    }

    // ==================== Authentication ====================

    public boolean login(String email, String password) {
        this.storedEmail = email;
        this.storedPassword = password;
        return auth.login(email, password);
    }

    public boolean ensureAuthenticated() {
        if (auth.isAuthenticated() && !auth.isTokenExpiring()) {
            return true;
        }
        if (storedEmail != null && storedPassword != null) {
            LOG.info("Re-authenticating with PlayOn Cloud (token expired or expiring)");
            return auth.login(storedEmail, storedPassword);
        }
        return false;
    }

    public boolean isAuthenticated() {
        return auth.isAuthenticated();
    }

    public String getAuthenticatedEmail() {
        return auth.getAuthenticatedEmail();
    }

    public void logout() {
        auth.logout();
        storedEmail = null;
        storedPassword = null;
    }

    // ==================== Recordings API ====================

    public List<PlayOnRecording> available() {
        return fetchRecordingList("/recordings/available");
    }

    public List<PlayOnRecording> recordings() {
        return available();
    }

    public List<PlayOnRecording> queue() {
        return fetchRecordingList("/recordings/queue");
    }

    private List<PlayOnRecording> fetchRecordingList(String endpoint) {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            String responseBody = authenticatedGet(apiBase + endpoint);
            if (responseBody != null) {
                JsonNode root = mapper.readTree(responseBody);
                JsonNode items;
                if (root.isArray()) {
                    items = root;
                } else if (root.has("recordings")) {
                    items = root.get("recordings");
                } else if (root.has("items")) {
                    items = root.get("items");
                } else {
                    LOG.warning("Unexpected response format from " + endpoint);
                    return Collections.emptyList();
                }

                List<PlayOnRecording> recordings = mapper.readValue(
                        items.traverse(), new TypeReference<List<PlayOnRecording>>() {});
                LOG.info("Retrieved " + recordings.size() + " recordings from " + endpoint);
                return recordings;
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to fetch " + endpoint, e);
        }

        return Collections.emptyList();
    }

    // ==================== Download ====================

    public boolean download(long downloadId, Path outputPath) {
        return download(downloadId, null, outputPath);
    }

    public boolean download(long downloadId, String filename, Path outputPath) {
        if (!ensureAuthenticated()) return false;

        Path tempFile = outputPath.resolveSibling(outputPath.getFileName() + ".part");

        try {
            long existingBytes = 0;
            if (Files.exists(tempFile)) {
                existingBytes = Files.size(tempFile);
                LOG.info("Resuming download from byte " + existingBytes);
            }

            HttpURLConnection conn = openConnection(
                    apiBase + "/recordings/" + downloadId + "/download");
            conn.setRequestProperty("Authorization", "Bearer " + auth.getJwt());
            conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
            }

            int status = conn.getResponseCode();

            // Handle redirects manually if needed
            if (status == 301 || status == 302) {
                String redirectUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (redirectUrl != null) {
                    return downloadFromUrl(redirectUrl, tempFile, outputPath, existingBytes);
                }
                return false;
            }

            if (status == 200 || status == 206) {
                OutputStream out;
                if (existingBytes > 0) {
                    out = Files.newOutputStream(tempFile, StandardOpenOption.APPEND);
                } else {
                    out = Files.newOutputStream(tempFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                try {
                    copyStream(conn.getInputStream(), out);
                } finally {
                    out.close();
                    conn.disconnect();
                }

                Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Downloaded: " + outputPath +
                        " (" + Files.size(outputPath) / 1_048_576 + " MB)");
                return true;
            } else {
                LOG.warning("Download failed: HTTP " + status);
                conn.disconnect();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Download failed for download_id " + downloadId, e);
        }

        return false;
    }

    private boolean downloadFromUrl(String url, Path tempFile, Path outputPath, long existingBytes) {
        try {
            HttpURLConnection conn = openConnection(url);
            conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);

            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
            }

            int status = conn.getResponseCode();
            if (status == 200 || status == 206) {
                OutputStream out;
                if (existingBytes > 0) {
                    out = Files.newOutputStream(tempFile, StandardOpenOption.APPEND);
                } else {
                    out = Files.newOutputStream(tempFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                try {
                    copyStream(conn.getInputStream(), out);
                } finally {
                    out.close();
                    conn.disconnect();
                }
                Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            conn.disconnect();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Download from redirect URL failed", e);
        }
        return false;
    }

    // ==================== Mark as Downloaded ====================

    public boolean markAsDownloaded(long recordingId) {
        if (!ensureAuthenticated()) return false;

        try {
            HttpURLConnection conn = openConnection(
                    apiBase + "/recordings/" + recordingId + "/downloaded");
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + auth.getJwt());
            conn.setRequestProperty("Content-Length", "0");

            int status = conn.getResponseCode();
            conn.disconnect();

            boolean success = status >= 200 && status < 300;
            if (success) {
                LOG.info("Marked recording " + recordingId + " as downloaded");
            }
            return success;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to mark recording " + recordingId + " as downloaded", e);
            return false;
        }
    }

    // ==================== Services ====================

    public List<PlayOnService> services() {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            String responseBody = authenticatedGet(apiBase + "/services");
            if (responseBody != null) {
                JsonNode root = mapper.readTree(responseBody);
                JsonNode items = root.isArray() ? root :
                        root.has("services") ? root.get("services") : root;
                return mapper.readValue(items.traverse(),
                        new TypeReference<List<PlayOnService>>() {});
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to fetch services", e);
        }
        return Collections.emptyList();
    }

    // ==================== Account ====================

    public PlayOnAccount account() {
        if (!ensureAuthenticated()) return null;

        try {
            String responseBody = authenticatedGet(apiBase + "/account");
            if (responseBody != null) {
                return mapper.readValue(responseBody, PlayOnAccount.class);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to fetch account info", e);
        }
        return null;
    }

    // ==================== Notifications ====================

    public List<String> notifications() {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            String responseBody = authenticatedGet(apiBase + "/notifications");
            if (responseBody != null) {
                JsonNode root = mapper.readTree(responseBody);
                JsonNode items = root.isArray() ? root :
                        root.has("notifications") ? root.get("notifications") : root;
                return mapper.readValue(items.traverse(),
                        new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to fetch notifications", e);
        }
        return Collections.emptyList();
    }

    // ==================== Featured Content ====================

    public String featuredImageUrl(String featureName) {
        if (!ensureAuthenticated()) return null;

        try {
            String encodedName = URLEncoder.encode(featureName, "UTF-8");
            String responseBody = authenticatedGet(
                    apiBase + "/features/" + encodedName + "/image");
            if (responseBody != null) {
                JsonNode json = mapper.readTree(responseBody);
                if (json.has("url")) return json.get("url").asText();
                if (json.has("image_url")) return json.get("image_url").asText();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to fetch featured image for " + featureName, e);
        }
        return null;
    }

    public boolean downloadImage(String imageUrl, Path outputPath) {
        if (imageUrl == null || imageUrl.isEmpty()) return false;

        try {
            HttpURLConnection conn = openConnection(imageUrl);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(30_000);

            int status = conn.getResponseCode();
            if (status == 200) {
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(outputPath)) {
                    copyStream(in, out);
                }
                conn.disconnect();
                return true;
            }
            conn.disconnect();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to download image: " + imageUrl, e);
        }
        return false;
    }

    // ==================== Helpers ====================

    /**
     * Perform an authenticated GET request and return the response body, or null on failure.
     */
    private String authenticatedGet(String url) throws IOException {
        HttpURLConnection conn = openConnection(url);
        conn.setRequestProperty("Authorization", "Bearer " + auth.getJwt());

        int status = conn.getResponseCode();
        if (status == 200) {
            String body = readResponseBody(conn);
            conn.disconnect();
            return body;
        } else if (status == 401) {
            LOG.warning("Auth expired, will re-auth next cycle");
            auth.logout();
            conn.disconnect();
        } else {
            LOG.warning("Request failed: HTTP " + status + " for " + url);
            conn.disconnect();
        }
        return null;
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private String readResponseBody(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            copyStream(in, baos);
            return baos.toString("UTF-8");
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }
}
