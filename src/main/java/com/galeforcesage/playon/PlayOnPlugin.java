package com.galeforcesage.playon;

import sage.SageTVPlugin;
import sage.SageTVPluginRegistry;
import com.galeforcesage.playon.api.PlayOnApiClient;
import com.galeforcesage.playon.api.models.PlayOnAccount;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.api.models.PlayOnService;
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
    private static final String CFG_LINKED_SERVICES = "PlayOnLinkedServices";
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
            CFG_LINKED_SERVICES,
            CFG_QUEUED,
            CFG_DEBUG_LOGGING
        };
    }

    @Override
    public String getConfigValue(String setting) {
        return switch (setting) {
            case CFG_EMAIL -> config.getCloudEmail();
            case CFG_PASSWORD -> config.getCloudPassword().isEmpty() ? "" : "••••••••";
            case CFG_SYNC_INTERVAL -> config.getSyncIntervalDisplay();
            case CFG_DOWNLOAD_DIR -> config.getDownloadDirectory();
            case CFG_REMOVE_FROM_CLOUD -> String.valueOf(config.isRemoveFromCloudAfterDownload());
            case CFG_DEBUG_LOGGING -> String.valueOf(config.isDebugLogging());
            case CFG_STATUS -> buildStatusText();
            case CFG_ACCOUNT_INFO -> buildAccountInfoText();
            case CFG_LINKED_SERVICES -> buildServicesText();
            case CFG_QUEUED -> buildQueuedText();
            case CFG_SYNC_NOW -> "Sync Now";
            case CFG_RELOGIN -> "Re-Login";
            case CFG_LOGOUT -> "Logout";
            default -> "";
        };
    }

    @Override
    public String[] getConfigValues(String setting) {
        return new String[]{getConfigValue(setting)};
    }

    @Override
    public int getConfigType(String setting) {
        return switch (setting) {
            case CFG_EMAIL -> CONFIG_TEXT;
            case CFG_PASSWORD -> CONFIG_PASSWORD;
            case CFG_SYNC_INTERVAL -> CONFIG_CHOICE;
            case CFG_DOWNLOAD_DIR -> CONFIG_DIRECTORY;
            case CFG_REMOVE_FROM_CLOUD, CFG_DEBUG_LOGGING -> CONFIG_BOOL;
            case CFG_SYNC_NOW, CFG_RELOGIN, CFG_LOGOUT -> CONFIG_BUTTON;
            case CFG_STATUS, CFG_ACCOUNT_INFO, CFG_LINKED_SERVICES, CFG_QUEUED -> CONFIG_TEXT;
            default -> CONFIG_TEXT;
        };
    }

    @Override
    public void setConfigValue(String setting, String value) {
        switch (setting) {
            case CFG_EMAIL -> {
                config.setCloudEmail(value);
                config.save();
            }
            case CFG_PASSWORD -> {
                config.setCloudPassword(value);
                config.save();
            }
            case CFG_SYNC_INTERVAL -> {
                config.setSyncIntervalMinutes(config.parseSyncIntervalOption(value));
                config.save();
                // Restart scheduler with new interval
                if (syncScheduler != null) {
                    syncScheduler.stop();
                    syncScheduler.start();
                }
            }
            case CFG_DOWNLOAD_DIR -> {
                config.setDownloadDirectory(value);
                config.save();
                libraryImporter.ensureImportDirectory(value);
            }
            case CFG_REMOVE_FROM_CLOUD -> {
                config.setRemoveFromCloudAfterDownload(Boolean.parseBoolean(value));
                config.save();
            }
            case CFG_DEBUG_LOGGING -> {
                config.setDebugLogging(Boolean.parseBoolean(value));
                config.save();
            }
            case CFG_SYNC_NOW -> {
                if (syncScheduler != null) {
                    syncScheduler.syncNow();
                }
            }
            case CFG_RELOGIN -> {
                performLogin();
            }
            case CFG_LOGOUT -> {
                apiClient.logout();
                config.setAuthStatus("Not connected");
                if (syncScheduler != null) {
                    syncScheduler.stop();
                    syncScheduler = null;
                }
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
        return switch (setting) {
            case CFG_EMAIL -> "Email address for your PlayOn Cloud account.";
            case CFG_PASSWORD -> "Password for your PlayOn Cloud account. Stored encrypted.";
            case CFG_SYNC_INTERVAL -> "How often to check for new cloud recordings (default: 2 hours, matching Channels DVR).";
            case CFG_DOWNLOAD_DIR -> "Directory where recordings are saved. Must be in SageTV's Video Import paths.";
            case CFG_REMOVE_FROM_CLOUD -> "Mark recordings as downloaded in PlayOn Cloud after successful download.";
            case CFG_DEBUG_LOGGING -> "Enable verbose debug logging for troubleshooting.";
            case CFG_SYNC_NOW -> "Trigger an immediate check for new recordings.";
            case CFG_STATUS -> "Current plugin status and sync information.";
            case CFG_ACCOUNT_INFO -> "PlayOn Cloud account details (plan, credits).";
            case CFG_LINKED_SERVICES -> "Streaming services linked to your PlayOn account.";
            case CFG_QUEUED -> "Recordings currently queued or in-progress in PlayOn Cloud.";
            case CFG_RELOGIN -> "Re-authenticate with PlayOn Cloud using saved credentials.";
            case CFG_LOGOUT -> "Disconnect from PlayOn Cloud and stop syncing.";
            default -> "";
        };
    }

    @Override
    public String getConfigLabel(String setting) {
        return switch (setting) {
            case CFG_EMAIL -> "Cloud Email";
            case CFG_PASSWORD -> "Cloud Password";
            case CFG_SYNC_INTERVAL -> "Sync Interval";
            case CFG_DOWNLOAD_DIR -> "Download Directory";
            case CFG_REMOVE_FROM_CLOUD -> "Remove from Cloud After Download";
            case CFG_DEBUG_LOGGING -> "Debug Logging";
            case CFG_SYNC_NOW -> "Sync Now";
            case CFG_STATUS -> "Status";
            case CFG_ACCOUNT_INFO -> "Account Info";
            case CFG_LINKED_SERVICES -> "Linked Services";
            case CFG_QUEUED -> "Queued Recordings";
            case CFG_RELOGIN -> "Re-Login";
            case CFG_LOGOUT -> "Logout";
            default -> setting;
        };
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
                return "Plan: " + (account.getPlan() != null ? account.getPlan() : "N/A") +
                        " | Credits: " + account.getCredits() +
                        (account.getStorageUsed() != null ?
                                " | Storage: " + account.getStorageUsed() : "");
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to get account info", e);
        }
        return "Unable to retrieve account info";
    }

    private String buildServicesText() {
        if (!apiClient.isAuthenticated()) return "Not connected";
        try {
            List<PlayOnService> services = apiClient.services();
            if (!services.isEmpty()) {
                return services.stream()
                        .filter(PlayOnService::isEnabled)
                        .map(PlayOnService::getName)
                        .collect(Collectors.joining(", "));
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to get services", e);
        }
        return "No services linked";
    }

    private String buildQueuedText() {
        if (syncScheduler == null) return "Not available";
        List<PlayOnRecording> queued = syncScheduler.getLastQueuedRecordings();
        if (queued.isEmpty()) return "No recordings queued";
        return queued.stream()
                .map(r -> r.getName() + " (" + r.getStatus() + ")")
                .collect(Collectors.joining("\n"));
    }
}
