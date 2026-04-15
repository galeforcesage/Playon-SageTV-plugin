package com.galeforcesage.playon.sagetv;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages plugin configuration with encrypted credential storage.
 * <p>
 * Configuration is stored in PlayOnPlugin.properties alongside Sage.properties.
 * Passwords/tokens are encrypted using AES with a machine-derived key.
 */
public class ConfigManager {

    private static final Logger LOG = Logger.getLogger(ConfigManager.class.getName());
    private static final String CONFIG_FILE = "PlayOnPlugin.properties";

    // Defaults per PRD
    private static final long DEFAULT_SYNC_INTERVAL_MINUTES = 120; // 2 hours (Channels DVR match)
    private static final String DEFAULT_DOWNLOAD_SUBDIR = "PlayOn";

    private final Properties props = new Properties();
    private final Path configPath;
    private final SecretKey encryptionKey;

    public ConfigManager() {
        String sageDir = System.getProperty("user.dir", ".");
        this.configPath = Paths.get(sageDir, CONFIG_FILE);
        this.encryptionKey = deriveEncryptionKey();
        load();
    }

    ConfigManager(Path configPath) {
        this.configPath = configPath;
        this.encryptionKey = deriveEncryptionKey();
        load();
    }

    private void load() {
        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
                LOG.info("Loaded PlayOn config from " + configPath);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to load config", e);
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream out = Files.newOutputStream(configPath)) {
                props.store(out, "PlayOn SageTV Plugin Configuration");
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to save config", e);
        }
    }

    public void resetToDefaults() {
        props.clear();
        props.setProperty("cloud.email", "");
        props.setProperty("cloud.password.encrypted", "");
        props.setProperty("sync.interval.minutes", String.valueOf(DEFAULT_SYNC_INTERVAL_MINUTES));
        props.setProperty("download.dir", getDefaultDownloadDirectory());
        props.setProperty("remove.from.cloud", "false");
        props.setProperty("logging.debug", "false");
        save();
    }

    // ==================== Credential Storage (Encrypted) ====================

    public String getCloudEmail() {
        return props.getProperty("cloud.email", "");
    }

    public void setCloudEmail(String email) {
        props.setProperty("cloud.email", email != null ? email : "");
    }

    /**
     * Get the decrypted password.
     */
    public String getCloudPassword() {
        String encrypted = props.getProperty("cloud.password.encrypted", "");
        if (encrypted.isEmpty()) return "";
        try {
            return decrypt(encrypted);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to decrypt password", e);
            return "";
        }
    }

    /**
     * Store the password in encrypted form.
     */
    public void setCloudPassword(String password) {
        if (password == null || password.isEmpty()) {
            props.setProperty("cloud.password.encrypted", "");
            return;
        }
        try {
            props.setProperty("cloud.password.encrypted", encrypt(password));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to encrypt password", e);
        }
    }

    // ==================== Sync Settings ====================

    public long getSyncIntervalMinutes() {
        try {
            long val = Long.parseLong(props.getProperty("sync.interval.minutes",
                    String.valueOf(DEFAULT_SYNC_INTERVAL_MINUTES)));
            return Math.max(30, Math.min(val, 1440)); // clamp to 30min–24hr
        } catch (NumberFormatException e) {
            return DEFAULT_SYNC_INTERVAL_MINUTES;
        }
    }

    public void setSyncIntervalMinutes(long minutes) {
        props.setProperty("sync.interval.minutes",
                String.valueOf(Math.max(30, Math.min(minutes, 1440))));
    }

    public String getDownloadDirectory() {
        return props.getProperty("download.dir", getDefaultDownloadDirectory());
    }

    public void setDownloadDirectory(String dir) {
        props.setProperty("download.dir", dir != null ? dir : getDefaultDownloadDirectory());
    }

    public boolean isRemoveFromCloudAfterDownload() {
        return Boolean.parseBoolean(props.getProperty("remove.from.cloud", "false"));
    }

    public void setRemoveFromCloudAfterDownload(boolean remove) {
        props.setProperty("remove.from.cloud", String.valueOf(remove));
    }

    public boolean isDebugLogging() {
        return Boolean.parseBoolean(props.getProperty("logging.debug", "false"));
    }

    public void setDebugLogging(boolean debug) {
        props.setProperty("logging.debug", String.valueOf(debug));
    }

    // ==================== Status (read-only, transient) ====================

    /** Whether authentication has been established. Set by plugin, not persisted. */
    private String authStatus = "Not connected";

    public String getAuthStatus() { return authStatus; }
    public void setAuthStatus(String status) { this.authStatus = status; }

    // ==================== Tracking file ====================

    public Path getTrackingFile() {
        return configPath.getParent().resolve("PlayOnDownloaded.txt");
    }

    // ==================== Helpers ====================

    private String getDefaultDownloadDirectory() {
        String sageDir = System.getProperty("user.dir", ".");
        return Paths.get(sageDir, DEFAULT_DOWNLOAD_SUBDIR).toString();
    }

    public String getSyncIntervalDisplay() {
        long minutes = getSyncIntervalMinutes();
        if (minutes >= 60) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " minutes";
    }

    public String[] getSyncIntervalOptions() {
        return new String[]{"30 minutes", "1 hour", "2 hours", "6 hours", "12 hours", "24 hours"};
    }

    public long parseSyncIntervalOption(String option) {
        if ("30 minutes".equals(option)) return 30;
        if ("1 hour".equals(option)) return 60;
        if ("2 hours".equals(option)) return 120;
        if ("6 hours".equals(option)) return 360;
        if ("12 hours".equals(option)) return 720;
        if ("24 hours".equals(option)) return 1440;
        return DEFAULT_SYNC_INTERVAL_MINUTES;
    }

    // ==================== Encryption ====================

    private SecretKey deriveEncryptionKey() {
        try {
            // Derive a machine-specific key from hostname + user
            String salt = System.getProperty("user.name", "sagetv") +
                    "@" + java.net.InetAddress.getLocalHost().getHostName();
            KeySpec spec = new PBEKeySpec("PlayOnSageTVPlugin".toCharArray(),
                    salt.getBytes(), 65536, 128);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to derive encryption key, using fallback", e);
            return new SecretKeySpec("PlayOnSageTVPlug!".getBytes(), 0, 16, "AES");
        }
    }

    private String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes()));
    }

    private String decrypt(String ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey);
        return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)));
    }
}
