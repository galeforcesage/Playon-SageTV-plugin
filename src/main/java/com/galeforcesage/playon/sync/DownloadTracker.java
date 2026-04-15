package com.galeforcesage.playon.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent tracking of downloaded recording IDs.
 * <p>
 * Maintains an in-memory set backed by a simple text file (one ID per line)
 * to prevent re-downloading the same recording across plugin restarts.
 */
public class DownloadTracker {

    private static final Logger LOG = Logger.getLogger(DownloadTracker.class.getName());

    private final Path trackingFile;
    private final Set<String> downloadedIds = ConcurrentHashMap.newKeySet();

    public DownloadTracker(Path trackingFile) {
        this.trackingFile = trackingFile;
        load();
    }

    /**
     * Check if a recording ID has already been downloaded.
     */
    public boolean isDownloaded(long recordingId) {
        return downloadedIds.contains(String.valueOf(recordingId));
    }

    /**
     * Mark a recording ID as downloaded and persist to disk.
     */
    public void markDownloaded(long recordingId) {
        if (downloadedIds.add(String.valueOf(recordingId))) {
            save();
        }
    }

    /**
     * Returns an unmodifiable view of all downloaded IDs.
     */
    public Set<String> getDownloadedIds() {
        return Collections.unmodifiableSet(downloadedIds);
    }

    /**
     * Number of recordings downloaded so far.
     */
    public int getCount() {
        return downloadedIds.size();
    }

    private void load() {
        if (Files.exists(trackingFile)) {
            try {
                var lines = Files.readAllLines(trackingFile);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        downloadedIds.add(trimmed);
                    }
                }
                LOG.info("Loaded " + downloadedIds.size() + " previously downloaded recording IDs");
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to load download tracker from " + trackingFile, e);
            }
        }
    }

    private void save() {
        try {
            Files.createDirectories(trackingFile.getParent());
            Files.write(trackingFile, downloadedIds);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save download tracker to " + trackingFile, e);
        }
    }
}
