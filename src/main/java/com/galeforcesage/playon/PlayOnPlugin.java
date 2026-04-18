package com.galeforcesage.playon;

import sage.SageTVPlugin;
import sage.SageTVPluginRegistry;
import com.galeforcesage.playon.api.PlayOnApiClient;
import com.galeforcesage.playon.api.models.PlayOnAccount;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.sagetv.ConfigManager;
import com.galeforcesage.playon.sagetv.LibraryImporter;
import com.galeforcesage.playon.sagetv.MetadataProcessor;
import com.galeforcesage.playon.sync.DownloadTracker;
import com.galeforcesage.playon.sync.SyncScheduler;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * PlayOn Integration Plugin for SageTV V9.x.
 * <p>
 * Replicates and expands upon the PlayOn integration model used by Channels DVR:
 * automatically downloads completed PlayOn Cloud recordings and imports them into
 * SageTV's library with rich metadata.
 * <p>
 * Plugin entry point — implements SageTVPlugin for SageTV's plugin manager.
 * Constructor accepts (SageTVPluginRegistry) or (SageTVPluginRegistry, boolean).
 */
public class PlayOnPlugin implements SageTVPlugin {

    private static final Logger LOG = Logger.getLogger(PlayOnPlugin.class.getName());

    private final SageTVPluginRegistry registry;
    private final ConfigManager config;
    private final PlayOnApiClient apiClient;
    private final LibraryImporter libraryImporter;
    private MetadataProcessor metadataProcessor;
    private DownloadTracker downloadTracker;
    private SyncScheduler syncScheduler;
    private volatile boolean running;

    public PlayOnPlugin(SageTVPluginRegistry registry) {
        this(registry, false);
    }

    public PlayOnPlugin(SageTVPluginRegistry registry, boolean resetConfig) {
        this.registry = registry;
        this.config = new ConfigManager();
        this.apiClient = new PlayOnApiClient();
        this.libraryImporter = new LibraryImporter();
        if (resetConfig) {
            this.config.resetToDefaults();
        }
    }

    @Override
    public void start() {
        LOG.info("PlayOn SageTV Plugin starting...");
        running = true;

        // Ensure download directory is ready
        if (!libraryImporter.ensureImportDirectory(config.getDownloadDirectory())) {
            LOG.severe("Failed to initialize download directory. Plugin will not sync.");
            config.setAuthStatus("Error: Download directory not accessible");
            return;
        }

        // Initialize tracker and metadata processor
        downloadTracker = new DownloadTracker(config.getTrackingFile());
        metadataProcessor = new MetadataProcessor();

        // Authenticate if credentials are configured
        String email = config.getCloudEmail();
        String password = config.getCloudPassword();

        if (!email.isEmpty() && !password.isEmpty()) {
            if (apiClient.login(email, password)) {
                config.setAuthStatus("Connected as " + email);
                LOG.info("Authenticated with PlayOn Cloud");

                // Start sync scheduler
                syncScheduler = new SyncScheduler(apiClient, config,
                        downloadTracker, metadataProcessor);
                syncScheduler.start();
            } else {
                config.setAuthStatus("Authentication failed — check credentials");
                LOG.warning("PlayOn Cloud authentication failed");
            }
        } else {
            config.setAuthStatus("Not configured — enter PlayOn Cloud credentials");
            LOG.info("PlayOn Cloud credentials not configured. " +
                    "Configure via plugin settings to enable sync.");
        }

        // Subscribe to SageTV events
        if (registry != null) {
            registry.eventSubscribe(this, "SystemMessagePosted");
        }

        LOG.info("PlayOn SageTV Plugin started");
    }

    @Override
    public void stop() {
        LOG.info("PlayOn SageTV Plugin stopping...");
        running = false;

        if (syncScheduler != null) {
            syncScheduler.stop();
            syncScheduler = null;
        }

        if (registry != null) {
            registry.eventUnsubscribe(this, "SystemMessagePosted");
        }

        LOG.info("PlayOn SageTV Plugin stopped");
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public void sageEvent(String eventName, Map eventVars) {
        if (!running) return;
        LOG.fine("SageTV event: " + eventName);
    }

    // ==================== Configuration Settings ====================

    private static final String CFG_EMAIL = "PlayOnCloudEmail";
    private static final String CFG_PASSWORD = "PlayOnCloudPassword";
    private static final String CFG_SYNC_INTERVAL = "PlayOnSyncInterval";
    private static final String CFG_DOWNLOAD_DIR = "PlayOnDownloadDir";
    private static final String CFG_REMOVE_FROM_CLOUD = "PlayOnRemoveFromCloud";
    private static final String CFG_DEBUG_LOGGING = "PlayOnDebugLogging";
    private static final String CFG_SYNC_NOW = "PlayOnSyncNow";
    private static final String CFG_STATUS = "PlayOnStatus";
    private static final String CFG_ACCOUNT_INFO = "PlayOnAccountInfo";
    private static final String CFG_QUEUED = "PlayOnQueued";
    private static final String CFG_RELOGIN = "PlayOnReLogin";
    private static final String CFG_LOGOUT = "PlayOnLogout";

    @Override
    public String[] getConfigSettings() {
        return new String[]{
            CFG_EMAIL,
            CFG_PASSWORD,
            CFG_STATUS,
            CFG_RELOGIN,
            CFG_LOGOUT,
            CFG_SYNC_INTERVAL,
            CFG_DOWNLOAD_DIR,
            CFG_REMOVE_FROM_CLOUD,
            CFG_SYNC_NOW,
            CFG_ACCOUNT_INFO,
            CFG_QUEUED,
            CFG_DEBUG_LOGGING
        };
    }

    @Override
    public String getConfigValue(String setting) {
        if (CFG_EMAIL.equals(setting)) return config.getCloudEmail();
        if (CFG_PASSWORD.equals(setting)) return config.getCloudPassword().isEmpty() ? "" : "••••••••";
        if (CFG_SYNC_INTERVAL.equals(setting)) return config.getSyncIntervalDisplay();
        if (CFG_DOWNLOAD_DIR.equals(setting)) return config.getDownloadDirectory();
        if (CFG_REMOVE_FROM_CLOUD.equals(setting)) return String.valueOf(config.isRemoveFromCloudAfterDownload());
        if (CFG_DEBUG_LOGGING.equals(setting)) return String.valueOf(config.isDebugLogging());
        if (CFG_STATUS.equals(setting)) return buildStatusText();
        if (CFG_ACCOUNT_INFO.equals(setting)) return buildAccountInfoText();
        if (CFG_QUEUED.equals(setting)) return buildQueuedText();
        if (CFG_SYNC_NOW.equals(setting)) return "Sync Now";
        if (CFG_RELOGIN.equals(setting)) return "Re-Login";
        if (CFG_LOGOUT.equals(setting)) return "Logout";
        return "";
    }

    @Override
    public String[] getConfigValues(String setting) {
        return new String[]{getConfigValue(setting)};
    }

    @Override
    public int getConfigType(String setting) {
        if (CFG_EMAIL.equals(setting)) return CONFIG_TEXT;
        if (CFG_PASSWORD.equals(setting)) return CONFIG_PASSWORD;
        if (CFG_SYNC_INTERVAL.equals(setting)) return CONFIG_CHOICE;
        if (CFG_DOWNLOAD_DIR.equals(setting)) return CONFIG_DIRECTORY;
        if (CFG_REMOVE_FROM_CLOUD.equals(setting) || CFG_DEBUG_LOGGING.equals(setting)) return CONFIG_BOOL;
        if (CFG_SYNC_NOW.equals(setting) || CFG_RELOGIN.equals(setting) || CFG_LOGOUT.equals(setting)) return CONFIG_BUTTON;
        if (CFG_STATUS.equals(setting) || CFG_ACCOUNT_INFO.equals(setting) ||
                CFG_QUEUED.equals(setting)) return CONFIG_BUTTON;
        return CONFIG_TEXT;
    }

    @Override
    public void setConfigValue(String setting, String value) {
        if (CFG_EMAIL.equals(setting)) {
            config.setCloudEmail(value);
            config.save();
        } else if (CFG_PASSWORD.equals(setting)) {
            config.setCloudPassword(value);
            config.save();
        } else if (CFG_SYNC_INTERVAL.equals(setting)) {
            config.setSyncIntervalMinutes(config.parseSyncIntervalOption(value));
            config.save();
            // Restart scheduler with new interval
            if (syncScheduler != null) {
                syncScheduler.stop();
                syncScheduler.start();
            }
        } else if (CFG_DOWNLOAD_DIR.equals(setting)) {
            config.setDownloadDirectory(value);
            config.save();
            libraryImporter.ensureImportDirectory(value);
        } else if (CFG_REMOVE_FROM_CLOUD.equals(setting)) {
            config.setRemoveFromCloudAfterDownload(Boolean.parseBoolean(value));
            config.save();
        } else if (CFG_DEBUG_LOGGING.equals(setting)) {
            config.setDebugLogging(Boolean.parseBoolean(value));
            config.save();
        } else if (CFG_SYNC_NOW.equals(setting)) {
            if (syncScheduler != null) {
                syncScheduler.syncNow();
            }
        } else if (CFG_RELOGIN.equals(setting)) {
            performLogin();
        } else if (CFG_LOGOUT.equals(setting)) {
            apiClient.logout();
            config.setAuthStatus("Not connected");
            if (syncScheduler != null) {
                syncScheduler.stop();
                syncScheduler = null;
            }
        }
    }

    @Override
    public void setConfigValues(String setting, String[] values) {
        if (values != null && values.length > 0) {
            setConfigValue(setting, values[0]);
        }
    }

    @Override
    public String[] getConfigOptions(String setting) {
        if (CFG_SYNC_INTERVAL.equals(setting)) {
            return config.getSyncIntervalOptions();
        }
        return null;
    }

    @Override
    public String getConfigHelpText(String setting) {
        if (CFG_EMAIL.equals(setting)) return "Email address for your PlayOn Cloud account.";
        if (CFG_PASSWORD.equals(setting)) return "Password for your PlayOn Cloud account. Stored encrypted.";
        if (CFG_SYNC_INTERVAL.equals(setting)) return "How often to check for new cloud recordings (default: 2 hours, matching Channels DVR).";
        if (CFG_DOWNLOAD_DIR.equals(setting)) return "Directory where recordings are saved. Must be in SageTV's Video Import paths.";
        if (CFG_REMOVE_FROM_CLOUD.equals(setting)) return "Mark recordings as downloaded in PlayOn Cloud after successful download.";
        if (CFG_DEBUG_LOGGING.equals(setting)) return "Enable verbose debug logging for troubleshooting.";
        if (CFG_SYNC_NOW.equals(setting)) return "Trigger an immediate check for new recordings.";
        if (CFG_STATUS.equals(setting)) return "Current plugin status and sync information.";
        if (CFG_ACCOUNT_INFO.equals(setting)) return buildAccountInfoHelp();
        if (CFG_QUEUED.equals(setting)) return buildQueuedHelp();
        if (CFG_RELOGIN.equals(setting)) return "Re-authenticate with PlayOn Cloud using saved credentials.";
        if (CFG_LOGOUT.equals(setting)) return "Disconnect from PlayOn Cloud and stop syncing.";
        return "";
    }

    @Override
    public String getConfigLabel(String setting) {
        if (CFG_EMAIL.equals(setting)) return "Cloud Email";
        if (CFG_PASSWORD.equals(setting)) return "Cloud Password";
        if (CFG_SYNC_INTERVAL.equals(setting)) return "Sync Interval";
        if (CFG_DOWNLOAD_DIR.equals(setting)) return "Download Directory";
        if (CFG_REMOVE_FROM_CLOUD.equals(setting)) return "Remove from Cloud After Download";
        if (CFG_DEBUG_LOGGING.equals(setting)) return "Debug Logging";
        if (CFG_SYNC_NOW.equals(setting)) return "Sync Now";
        if (CFG_STATUS.equals(setting)) return "Status";
        if (CFG_ACCOUNT_INFO.equals(setting)) return "Account Info";
        if (CFG_QUEUED.equals(setting)) return "Queued Recordings";
        if (CFG_RELOGIN.equals(setting)) return "Re-Login";
        if (CFG_LOGOUT.equals(setting)) return "Logout";
        return setting;
    }

    @Override
    public void resetConfig() {
        config.resetToDefaults();
    }

    // ==================== Private Helpers ====================

    private void performLogin() {
        String email = config.getCloudEmail();
        String password = config.getCloudPassword();
        if (email.isEmpty() || password.isEmpty()) {
            config.setAuthStatus("Error: Email and password required");
            return;
        }

        if (apiClient.login(email, password)) {
            config.setAuthStatus("Connected as " + email);

            // Start or restart sync scheduler
            if (syncScheduler != null) {
                syncScheduler.stop();
            }
            if (downloadTracker == null) {
                downloadTracker = new DownloadTracker(config.getTrackingFile());
            }
            if (metadataProcessor == null) {
                metadataProcessor = new MetadataProcessor();
            }
            syncScheduler = new SyncScheduler(apiClient, config,
                    downloadTracker, metadataProcessor);
            syncScheduler.start();
        } else {
            config.setAuthStatus("Authentication failed — check credentials");
        }
    }

    private String buildStatusText() {
        StringBuilder sb = new StringBuilder();
        sb.append(config.getAuthStatus());
        if (syncScheduler != null && syncScheduler.isRunning()) {
            sb.append("\nLast sync: ").append(
                    syncScheduler.getLastSyncTime() != null ? syncScheduler.getLastSyncTime() : "never");
            if (syncScheduler.getLastSyncResult() != null) {
                sb.append(" — ").append(syncScheduler.getLastSyncResult());
            }
            sb.append("\nNext sync: ").append(
                    syncScheduler.getNextSyncTime() != null ? syncScheduler.getNextSyncTime() : "pending");
            sb.append("\nTotal downloaded: ").append(syncScheduler.getTotalDownloaded());
        }
        return sb.toString();
    }

    private String buildAccountInfoText() {
        if (!apiClient.isAuthenticated()) return "Not connected";
        try {
            PlayOnAccount account = apiClient.account();
            if (account != null) {
                return "Credits: " + account.getCredits();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to get account info", e);
        }
        return "Unable to retrieve";
    }

    private String buildAccountInfoHelp() {
        if (!apiClient.isAuthenticated()) return "Not connected";
        try {
            PlayOnAccount account = apiClient.account();
            if (account != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("Plan: ").append(account.getPlan() != null ? account.getPlan() : "N/A");
                sb.append(" | Credits: ").append(account.getCredits());
                if (account.getStorageUsed() != null) {
                    sb.append(" | Storage: ").append(account.getStorageUsed());
                }
                if (account.getEmail() != null) {
                    sb.append(" | ").append(account.getEmail());
                }
                return sb.toString();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to get account info", e);
        }
        return "PlayOn Cloud account details (plan, credits).";
    }

    private String buildQueuedText() {
        if (syncScheduler == null) return "Not available";
        List<PlayOnRecording> queued = syncScheduler.getLastQueuedRecordings();
        if (queued.isEmpty()) return "No recordings queued";
        return queued.size() + " recording" + (queued.size() != 1 ? "s" : "");
    }

    private String buildQueuedHelp() {
        if (syncScheduler == null) return "Not available";
        List<PlayOnRecording> queued = syncScheduler.getLastQueuedRecordings();
        if (queued.isEmpty()) return "No recordings currently queued or in-progress.";
        return queued.stream()
                .map(r -> r.getName() + " (" + r.getStatusDisplay() + ")")
                .collect(Collectors.joining(", "));
    }
}
