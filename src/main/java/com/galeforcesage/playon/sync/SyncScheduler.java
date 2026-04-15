package com.galeforcesage.playon.sync;

import com.galeforcesage.playon.api.PlayOnApiClient;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.sagetv.ConfigManager;
import com.galeforcesage.playon.sagetv.MetadataProcessor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background scheduler that periodically polls PlayOn Cloud for new recordings
 * and downloads them sequentially.
 * <p>
 * Default polling interval is 2 hours (matching Channels DVR behavior).
 * Downloads are sequential (one at a time) to minimize impact on DVR recording.
 */
public class SyncScheduler {

    private static final Logger LOG = Logger.getLogger(SyncScheduler.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PlayOnApiClient apiClient;
    private final ConfigManager config;
    private final DownloadTracker tracker;
    private final DownloadManager downloadManager;
    private final MetadataProcessor metadataProcessor;

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    // Status tracking
    private String lastSyncTime;
    private String lastSyncResult;
    private String nextSyncTime;
    private int totalDownloaded;
    private List<PlayOnRecording> lastQueuedRecordings = List.of();

    public SyncScheduler(PlayOnApiClient apiClient, ConfigManager config,
                         DownloadTracker tracker, MetadataProcessor metadataProcessor) {
        this.apiClient = apiClient;
        this.config = config;
        this.tracker = tracker;
        this.downloadManager = new DownloadManager(apiClient, config, tracker);
        this.metadataProcessor = metadataProcessor;
    }

    /**
     * Start the scheduled sync loop.
     */
    public void start() {
        if (running) return;
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayOnSyncScheduler");
            t.setDaemon(true);
            return t;
        });

        long intervalMinutes = config.getSyncIntervalMinutes();
        scheduler.scheduleWithFixedDelay(
                this::syncCycle,
                0, // immediate first sync on start
                intervalMinutes,
                TimeUnit.MINUTES
        );

        LOG.info("PlayOn sync scheduler started (interval: " + intervalMinutes + " min)");
    }

    /**
     * Stop the scheduler. Let any in-progress download finish.
     */
    public void stop() {
        running = false;
        downloadManager.cancel();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("PlayOn sync scheduler stopped");
    }

    /**
     * Trigger an immediate sync (used by "Sync Now" button).
     */
    public void syncNow() {
        if (scheduler != null && running) {
            scheduler.execute(this::syncCycle);
        }
    }

    /**
     * One complete sync cycle: poll → download new → apply metadata.
     */
    private void syncCycle() {
        if (!running) return;

        lastSyncTime = LocalDateTime.now().format(TIME_FMT);
        LOG.info("Starting PlayOn sync cycle at " + lastSyncTime);
        int downloaded = 0;

        try {
            // Ensure authentication
            if (!apiClient.ensureAuthenticated()) {
                lastSyncResult = "Authentication failed";
                LOG.warning("Sync aborted: " + lastSyncResult);
                return;
            }

            // Fetch completed recordings
            List<PlayOnRecording> available = apiClient.available();

            // Also fetch queue for status display
            try {
                lastQueuedRecordings = apiClient.queue();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to fetch queue (non-critical)", e);
            }

            // Process each new recording sequentially
            for (PlayOnRecording recording : available) {
                if (!running) break;

                if (!recording.isCompleted()) continue;
                if (tracker.isDownloaded(recording.getId())) continue;

                // Download
                Path downloadedFile = downloadManager.downloadRecording(recording);
                if (downloadedFile != null) {
                    // Mark as downloaded in tracker
                    tracker.markDownloaded(recording.getId());
                    downloaded++;
                    totalDownloaded++;

                    // Apply metadata for SageTV library
                    try {
                        metadataProcessor.processRecording(recording, downloadedFile);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Metadata processing failed for " +
                                recording.getName() + " (file is still available)", e);
                    }

                    // Mark as downloaded in cloud (optional)
                    if (config.isRemoveFromCloudAfterDownload()) {
                        try {
                            apiClient.markAsDownloaded(recording.getId());
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "Failed to mark " + recording.getId() +
                                    " as downloaded in cloud", e);
                        }
                    }
                }
            }

            lastSyncResult = downloaded > 0
                    ? downloaded + " new recording(s) downloaded"
                    : "No new recordings";
            LOG.info("Sync complete: " + lastSyncResult);

        } catch (Exception e) {
            lastSyncResult = "Error: " + e.getMessage();
            LOG.log(Level.SEVERE, "Sync cycle failed", e);
        }

        // Calculate next sync time
        long intervalMin = config.getSyncIntervalMinutes();
        nextSyncTime = LocalDateTime.now().plusMinutes(intervalMin).format(TIME_FMT);
    }

    // ==================== Status getters for UI ====================

    public String getLastSyncTime() { return lastSyncTime; }
    public String getLastSyncResult() { return lastSyncResult; }
    public String getNextSyncTime() { return nextSyncTime; }
    public int getTotalDownloaded() { return totalDownloaded + tracker.getCount(); }
    public List<PlayOnRecording> getLastQueuedRecordings() { return lastQueuedRecordings; }
    public boolean isRunning() { return running; }
}
