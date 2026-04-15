package com.galeforcesage.playon.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galeforcesage.playon.api.models.PlayOnAccount;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.api.models.PlayOnService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
 */
public class PlayOnApiClient {

    private static final Logger LOG = Logger.getLogger(PlayOnApiClient.class.getName());
    private static final String DEFAULT_API_BASE = "https://api.playonrecorder.com/v3";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(4);
    private static final String USER_AGENT = "PlayOnSageTVPlugin/1.0";

    private final HttpClient httpClient;
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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
        this.auth = new PlayOnAuth(httpClient, mapper, apiBase);
    }

    // ==================== Authentication ====================

    /**
     * Login with PlayOn Cloud credentials.
     * Stores credentials for automatic re-authentication on token expiry.
     */
    public boolean login(String email, String password) {
        this.storedEmail = email;
        this.storedPassword = password;
        return auth.login(email, password);
    }

    /**
     * Check if authenticated and re-login if token is expired or expiring.
     */
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

    /**
     * List completed (available) recordings.
     * Equivalent to Python API's available() method.
     */
    public List<PlayOnRecording> available() {
        return fetchRecordingList("/recordings/available");
    }

    /**
     * Alias for available() — list all available recordings.
     */
    public List<PlayOnRecording> recordings() {
        return available();
    }

    /**
     * List queued/in-progress recordings.
     * Equivalent to Python API's queue() method.
     */
    public List<PlayOnRecording> queue() {
        return fetchRecordingList("/recordings/queue");
    }

    private List<PlayOnRecording> fetchRecordingList(String endpoint) {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            HttpResponse<String> response = authenticatedGet(apiBase + endpoint);

            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
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
            } else if (response.statusCode() == 401) {
                LOG.warning("Auth expired on " + endpoint + ", will re-auth next cycle");
                auth.logout();
            } else {
                LOG.warning("Failed to fetch " + endpoint + ": HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.SEVERE, "Failed to fetch " + endpoint, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }

        return Collections.emptyList();
    }

    // ==================== Download ====================

    /**
     * Download a recording to the specified output path.
     * Streams directly to disk without buffering in memory.
     * Supports resume via HTTP Range headers.
     *
     * @param downloadId the recording's download_id
     * @param outputPath the target file path
     * @return true if download was successful
     */
    public boolean download(long downloadId, Path outputPath) {
        return download(downloadId, null, outputPath);
    }

    /**
     * Download a recording with optional filename override.
     */
    public boolean download(long downloadId, String filename, Path outputPath) {
        if (!ensureAuthenticated()) return false;

        Path tempFile = outputPath.resolveSibling(outputPath.getFileName() + ".part");

        try {
            // Check for existing partial download (resume support)
            long existingBytes = 0;
            if (Files.exists(tempFile)) {
                existingBytes = Files.size(tempFile);
                LOG.info("Resuming download from byte " + existingBytes);
            }

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/recordings/" + downloadId + "/download"))
                    .header("Authorization", "Bearer " + auth.getJwt())
                    .header("User-Agent", USER_AGENT)
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET();

            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=" + existingBytes + "-");
            }

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200 || response.statusCode() == 206) {
                try (InputStream in = response.body();
                     var out = Files.newOutputStream(tempFile,
                             existingBytes > 0 ?
                                     new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.APPEND} :
                                     new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE,
                                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING})) {
                    in.transferTo(out);
                }

                // Move temp file to final destination
                Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Downloaded: " + outputPath +
                        " (" + Files.size(outputPath) / 1_048_576 + " MB)");
                return true;
            } else if (response.statusCode() == 302 || response.statusCode() == 301) {
                // Handle redirect to actual download URL
                String redirectUrl = response.headers().firstValue("Location").orElse(null);
                if (redirectUrl != null) {
                    return downloadFromUrl(redirectUrl, tempFile, outputPath, existingBytes);
                }
            } else {
                LOG.warning("Download failed: HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.SEVERE, "Download failed for download_id " + downloadId, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }

        return false;
    }

    private boolean downloadFromUrl(String url, Path tempFile, Path outputPath, long existingBytes) {
        try {
            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET();

            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=" + existingBytes + "-");
            }

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200 || response.statusCode() == 206) {
                try (InputStream in = response.body();
                     var out = Files.newOutputStream(tempFile,
                             existingBytes > 0 ?
                                     new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.APPEND} :
                                     new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE,
                                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING})) {
                    in.transferTo(out);
                }
                Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.SEVERE, "Download from redirect URL failed", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return false;
    }

    // ==================== Mark as Downloaded ====================

    /**
     * Mark a recording as downloaded (no longer pending in cloud).
     */
    public boolean markAsDownloaded(long recordingId) {
        if (!ensureAuthenticated()) return false;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/recordings/" + recordingId + "/downloaded"))
                    .header("Authorization", "Bearer " + auth.getJwt())
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (success) {
                LOG.info("Marked recording " + recordingId + " as downloaded");
            }
            return success;
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.WARNING, "Failed to mark recording " + recordingId + " as downloaded", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Services ====================

    /**
     * List available streaming services linked to the account.
     */
    public List<PlayOnService> services() {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            HttpResponse<String> response = authenticatedGet(apiBase + "/services");
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode items = root.isArray() ? root :
                        root.has("services") ? root.get("services") : root;
                return mapper.readValue(items.traverse(),
                        new TypeReference<List<PlayOnService>>() {});
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.WARNING, "Failed to fetch services", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return Collections.emptyList();
    }

    // ==================== Account ====================

    /**
     * Get account information (plan, credits, storage).
     */
    public PlayOnAccount account() {
        if (!ensureAuthenticated()) return null;

        try {
            HttpResponse<String> response = authenticatedGet(apiBase + "/account");
            if (response.statusCode() == 200) {
                return mapper.readValue(response.body(), PlayOnAccount.class);
            }
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.WARNING, "Failed to fetch account info", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return null;
    }

    // ==================== Notifications ====================

    /**
     * Get notifications/alerts (failed recordings, auth issues, etc.).
     */
    public List<String> notifications() {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            HttpResponse<String> response = authenticatedGet(apiBase + "/notifications");
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
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

    /**
     * Get the image URL for a featured show (for cover art).
     */
    public String featuredImageUrl(String featureName) {
        if (!ensureAuthenticated()) return null;

        try {
            String encodedName = java.net.URLEncoder.encode(featureName, "UTF-8");
            HttpResponse<String> response = authenticatedGet(
                    apiBase + "/features/" + encodedName + "/image");
            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                if (json.has("url")) return json.get("url").asText();
                if (json.has("image_url")) return json.get("image_url").asText();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to fetch featured image for " + featureName, e);
        }
        return null;
    }

    /**
     * Download an image (cover art/thumbnail) to a local file.
     *
     * @return true if the image was downloaded
     */
    public boolean downloadImage(String imageUrl, Path outputPath) {
        if (imageUrl == null || imageUrl.isEmpty()) return false;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofFile(outputPath));
            return response.statusCode() == 200;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to download image: " + imageUrl, e);
            return false;
        }
    }

    // ==================== Helpers ====================

    private HttpResponse<String> authenticatedGet(String url)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getJwt())
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
