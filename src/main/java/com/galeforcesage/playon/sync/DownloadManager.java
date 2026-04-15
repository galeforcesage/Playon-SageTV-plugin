package com.galeforcesage.playon.sync;

import com.galeforcesage.playon.api.PlayOnApiClient;
import com.galeforcesage.playon.api.models.PlayOnRecording;
import com.galeforcesage.playon.sagetv.ConfigManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages sequential file downloads from PlayOn Cloud with retry and resume support.
 * <p>
 * Per the PRD: "Only one download runs at a time by default." Downloads stream directly
 * to disk without loading the entire file into memory. Failed downloads are retried
 * with exponential backoff up to a configurable max attempts.
 */
public class DownloadManager {

    private static final Logger LOG = Logger.getLogger(DownloadManager.class.getName());
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 5_000;

    private final PlayOnApiClient apiClient;
    private final ConfigManager config;
    private final DownloadTracker tracker;
    private volatile boolean cancelled;

    public DownloadManager(PlayOnApiClient apiClient, ConfigManager config, DownloadTracker tracker) {
        this.apiClient = apiClient;
        this.config = config;
        this.tracker = tracker;
    }

    /**
     * Download a single recording with retry logic.
     *
     * @return the path to the downloaded file, or null on failure
     */
    public Path downloadRecording(PlayOnRecording recording) {
        if (cancelled) return null;

        Path downloadDir = Paths.get(config.getDownloadDirectory());
        Path subDir = downloadDir.resolve(recording.toSubdirectoryPath());
        Path targetFile = subDir.resolve(recording.toFilename());

        // Skip if already downloaded
        if (Files.exists(targetFile)) {
            LOG.info("Already exists locally: " + targetFile);
            tracker.markDownloaded(recording.getId());
            return targetFile;
        }

        // Check available disk space (recordings can be 500MB-several GB)
        try {
            Files.createDirectories(subDir);
            long usableSpace = Files.getFileStore(subDir).getUsableSpace();
            if (usableSpace < 1_073_741_824L) { // 1 GB minimum
                LOG.severe("Insufficient disk space: " + usableSpace / 1_048_576 + " MB available");
                return null;
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to check/create download directory: " + subDir, e);
            return null;
        }

        LOG.info("Downloading: " + recording + " → " + targetFile);

        // Retry with exponential backoff
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            if (cancelled) return null;

            try {
                boolean success = apiClient.download(recording.getDownloadId(), targetFile);
                if (success && Files.exists(targetFile) && Files.size(targetFile) > 0) {
                    LOG.info("Download complete: " + targetFile +
                            " (" + Files.size(targetFile) / 1_048_576 + " MB)");

                    // Download cover art if available
                    downloadCoverArt(recording, subDir);

                    return targetFile;
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Download attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS +
                        " failed for " + recording.getName(), e);
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                long delay = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1)); // exponential backoff
                LOG.info("Retrying in " + delay / 1000 + " seconds...");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        LOG.severe("Failed to download after " + MAX_RETRY_ATTEMPTS +
                " attempts: " + recording.getName());
        return null;
    }

    /**
     * Download cover art for a recording if a thumbnail URL is available.
     */
    private void downloadCoverArt(PlayOnRecording recording, Path directory) {
        String imageUrl = recording.getThumbnailUrl();
        if (imageUrl == null || imageUrl.isEmpty()) {
            // Try featured_image_url via API
            String showName = recording.isEpisode() ? recording.getSeries() : recording.getName();
            if (showName != null) {
                imageUrl = apiClient.featuredImageUrl(showName);
            }
        }

        if (imageUrl != null && !imageUrl.isEmpty()) {
            String artFilename = recording.isEpisode() && recording.getSeries() != null
                    ? recording.getSeries().replaceAll("[<>:\"/\\\\|?*]", "_") + ".jpg"
                    : recording.toFilename().replaceFirst("\\.mp4$", ".jpg");
            Path artPath = directory.resolve(artFilename);

            if (!Files.exists(artPath)) {
                apiClient.downloadImage(imageUrl, artPath);
            }
        }
    }

    /**
     * Cancel any in-progress download.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Reset cancellation state.
     */
    public void reset() {
        cancelled = false;
    }
}
