package com.galeforcesage.playon.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galeforcesage.playon.api.models.PlayOnAccount;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.api.models.PlayOnService;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;

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
    private static final String CDS_BASE = "https://cds.playonrecorder.com/api/v6";
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

    // Cached results for non-blocking UI access
    private volatile PlayOnAccount cachedAccount;
    private volatile List<PlayOnService> cachedServices;
    private volatile long accountCacheTime;
    private volatile long servicesCacheTime;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes

    public PlayOnApiClient() {
        this(DEFAULT_API_BASE);
    }

    public PlayOnApiClient(String apiBase) {
        this.apiBase = apiBase;
        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
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
        return fetchRecordingList("/library");
    }

    public List<PlayOnRecording> recordings() {
        return available();
    }

    public List<PlayOnRecording> queue() {
        return fetchRecordingList("/queue");
    }

    private List<PlayOnRecording> fetchRecordingList(String endpoint) {
        if (!ensureAuthenticated()) return Collections.emptyList();

        try {
            String responseBody = authenticatedGet(apiBase + endpoint);
            if (responseBody != null) {
                JsonNode root = mapper.readTree(responseBody);
                LOG.info("Response from " + endpoint + " keys: " + getFieldNames(root));

                // Check for API-level errors: {success: false, error_code, error_message}
                if (root.has("success") && !root.get("success").asBoolean(true)) {
                    String errCode = root.has("error_code") ? root.get("error_code").asText() : "unknown";
                    String errMsg = root.has("error_message") ? root.get("error_message").asText() : "unknown";
                    LOG.warning("API error from " + endpoint + ": code=" + errCode + " msg=" + errMsg);
                    return Collections.emptyList();
                }
                if (root.has("error_code") || root.has("error_message")) {
                    String errCode = root.has("error_code") ? root.get("error_code").asText() : "unknown";
                    String errMsg = root.has("error_message") ? root.get("error_message").asText() : "unknown";
                    LOG.warning("API error from " + endpoint + ": code=" + errCode + " msg=" + errMsg);
                    return Collections.emptyList();
                }

                // Unwrap {success, data} envelope if present
                if (root.has("data") && !root.get("data").isNull()) {
                    root = root.get("data");
                    LOG.info("Unwrapped data envelope, type: " +
                            (root.isArray() ? "array[" + root.size() + "]" : "object keys: " + getFieldNames(root)));
                }

                JsonNode items;
                if (root.isArray()) {
                    items = root;
                } else if (root.has("recordings")) {
                    items = root.get("recordings");
                } else if (root.has("items")) {
                    items = root.get("items");
                } else if (root.has("entries")) {
                    items = root.get("entries");
                } else if (root.has("results")) {
                    items = root.get("results");
                } else {
                    LOG.warning("Unexpected response format from " + endpoint);
                    return Collections.emptyList();
                }

                // Log first entry fields so we can see actual API field names
                if (items.size() > 0) {
                    LOG.info("First entry from " + endpoint + " keys: " + getFieldNames(items.get(0)));
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
                    apiBase + "/library/" + downloadId + "/download");
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
        // Return cached result if fresh
        if (cachedServices != null && (System.currentTimeMillis() - servicesCacheTime) < CACHE_TTL_MS) {
            return cachedServices;
        }
        if (!ensureAuthenticated()) return Collections.emptyList();

        // Services come from the CDS (content delivery) server, not the API server
        try {
            String responseBody = authenticatedGet(CDS_BASE + "/content");
            if (responseBody != null) {
                JsonNode root = mapper.readTree(responseBody);
                LOG.info("CDS /content response type: " +
                        (root.isArray() ? "array[" + root.size() + "]" : "object keys: " + getFieldNames(root)));
                // The CDS might return raw array or {success, data} envelope
                if (root.has("data") && !root.get("data").isNull()) {
                    root = root.get("data");
                }
                JsonNode items = root.isArray() ? root :
                        root.has("entries") ? root.get("entries") : root;
                if (items.isArray() && items.size() > 0) {
                    LOG.info("First CDS content entry keys: " + getFieldNames(items.get(0)));
                    cachedServices = mapper.readValue(items.traverse(),
                            new TypeReference<List<PlayOnService>>() {});
                    LOG.info("Loaded " + cachedServices.size() + " services from CDS");
                    servicesCacheTime = System.currentTimeMillis();
                    return cachedServices;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to fetch CDS /content", e);
        }
        cachedServices = Collections.emptyList();
        servicesCacheTime = System.currentTimeMillis();
        return cachedServices;
    }

    // ==================== Account ====================

    public PlayOnAccount account() {
        // Return cached result if fresh
        if (cachedAccount != null && (System.currentTimeMillis() - accountCacheTime) < CACHE_TTL_MS) {
            return cachedAccount;
        }
        if (!ensureAuthenticated()) return null;

        // Try /account first, then fall back to login data
        try {
            String responseBody = authenticatedGet(apiBase + "/account");
            if (responseBody != null) {
                JsonNode root = mapper.readTree(responseBody);
                LOG.info("Account response keys: " + getFieldNames(root));
                // Unwrap data envelope
                if (root.has("data") && root.get("data").isObject()) {
                    root = root.get("data");
                    LOG.info("Account data keys: " + getFieldNames(root));
                    // Log nested objects to discover structure
                    if (root.has("Subscription") && root.get("Subscription").isObject()) {
                        LOG.info("Account Subscription keys: " + getFieldNames(root.get("Subscription")));
                    }
                    if (root.has("Account") && root.get("Account").isObject()) {
                        LOG.info("Account.Account keys: " + getFieldNames(root.get("Account")));
                    }
                    if (root.has("Globals") && root.get("Globals").isObject()) {
                        LOG.info("Account.Globals keys: " + getFieldNames(root.get("Globals")));
                    }
                    if (root.has("ProductTypes") && root.get("ProductTypes").isArray()) {
                        LOG.info("Account.ProductTypes: " + root.get("ProductTypes").toString());
                    }
                }
                cachedAccount = mapper.readValue(root.traverse(), PlayOnAccount.class);
                accountCacheTime = System.currentTimeMillis();
                return cachedAccount;
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to fetch /account", e);
        }

        // Fall back to login data (we know login returns email, name, id)
        PlayOnAccount fallback = new PlayOnAccount();
        fallback.setEmail(auth.getAuthenticatedEmail());
        fallback.setPlan("Unknown");
        cachedAccount = fallback;
        accountCacheTime = System.currentTimeMillis();
        return cachedAccount;
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
        // Use Bearer JWT for API calls (the 'token' field from login, starts with eyJ...)
        String jwt = auth.getJwt();
        if (jwt != null) {
            HttpURLConnection conn = openConnection(url);
            conn.setRequestProperty("Authorization", "Bearer " + jwt);
            int status = conn.getResponseCode();
            if (status == 200) {
                String body = readResponseBody(conn);
                conn.disconnect();
                return body;
            } else if (status == 401) {
                LOG.warning("Auth expired (Bearer JWT), will re-auth next cycle");
                auth.logout();
                conn.disconnect();
            } else {
                String errorBody = readErrorBody(conn);
                LOG.warning("Request failed: HTTP " + status + " for " + url +
                        (errorBody != null ? " body=" + errorBody.substring(0, Math.min(errorBody.length(), 500)) : ""));
                conn.disconnect();
            }
        }
        return null;
    }

    private String readErrorBody(HttpURLConnection conn) {
        try (InputStream err = conn.getErrorStream()) {
            if (err == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            copyStream(err, baos);
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
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

    private String getFieldNames(JsonNode node) {
        if (node.isArray()) return "array[" + node.size() + "]";
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
