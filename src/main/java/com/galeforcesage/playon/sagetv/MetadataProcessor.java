package com.galeforcesage.playon.sagetv;

import com.galeforcesage.playon.api.models.PlayOnRecording;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps PlayOn recording metadata to SageTV's metadata model and writes
 * sidecar .properties files for library import.
 * <p>
 * When SageTV scans the import directory, these sidecar files provide
 * rich metadata (title, series, episode info, description, source service)
 * so recordings are properly cataloged in the library.
 * <p>
 * Field mapping:
 * <ul>
 *   <li>playon.Series → ShowTitle / Title</li>
 *   <li>playon.Name → EpisodeName</li>
 *   <li>playon.ProviderID → ExternalID / Category</li>
 *   <li>season/episode → SeasonNumber / EpisodeNumber</li>
 *   <li>description → Description</li>
 *   <li>resolution → Misc (informational)</li>
 * </ul>
 */
public class MetadataProcessor {

    private static final Logger LOG = Logger.getLogger(MetadataProcessor.class.getName());

    /**
     * Write a .properties metadata sidecar file alongside the downloaded video.
     * Also writes a source indicator like "PlayOn (Netflix)".
     */
    public void processRecording(PlayOnRecording recording, Path videoFile) {
        Path metaFile = toMetadataPath(videoFile);

        Properties meta = new Properties();

        // Show/series identification
        if (recording.isEpisode() && recording.getSeries() != null) {
            meta.setProperty("Title", recording.getSeries());
            meta.setProperty("ShowTitle", recording.getSeries());
            if (recording.getName() != null) {
                meta.setProperty("EpisodeName", recording.getName());
            }
            if (recording.getSeason() > 0) {
                meta.setProperty("SeasonNumber", String.valueOf(recording.getSeason()));
            }
            if (recording.getEpisode() > 0) {
                meta.setProperty("EpisodeNumber", String.valueOf(recording.getEpisode()));
            }
        } else {
            String title = recording.getNiceName() != null && !recording.getNiceName().isEmpty()
                    ? recording.getNiceName()
                    : recording.getName() != null ? recording.getName() : "Unknown";
            meta.setProperty("Title", title);
        }

        // Description
        if (recording.getDescription() != null && !recording.getDescription().isEmpty()) {
            meta.setProperty("Description", recording.getDescription());
        }

        // Source service — "PlayOn (Netflix)" format
        String provider = recording.getProviderId();
        if (provider != null && !provider.isEmpty()) {
            meta.setProperty("Category", "PlayOn");
            meta.setProperty("SubCategory", provider);
            meta.setProperty("ExternalID", "PlayOn-" + recording.getId());
            meta.setProperty("Source", "PlayOn (" + provider + ")");
        } else {
            meta.setProperty("Category", "PlayOn");
            meta.setProperty("ExternalID", "PlayOn-" + recording.getId());
            meta.setProperty("Source", "PlayOn Cloud");
        }

        // Resolution (informational)
        if (recording.getResolution() != null && !recording.getResolution().isEmpty()) {
            meta.setProperty("Resolution", recording.getResolution());
        }

        // Duration
        if (recording.getDurationSeconds() > 0) {
            meta.setProperty("Duration", String.valueOf(recording.getDurationSeconds() * 1000));
        }

        // File size from PlayOn
        if (recording.getHumanSize() != null && !recording.getHumanSize().isEmpty()) {
            meta.setProperty("PlayOnFileSize", recording.getHumanSize());
        }

        // PlayOn recording ID for cross-reference
        meta.setProperty("PlayOnID", String.valueOf(recording.getId()));

        try (OutputStream out = Files.newOutputStream(metaFile)) {
            meta.store(out, "PlayOn SageTV Plugin - Recording Metadata");
            LOG.info("Wrote metadata: " + metaFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write metadata file: " + metaFile, e);
        }
    }

    /**
     * Convert a video file path to its metadata sidecar path.
     * e.g., ShowName_S01E01.mp4 → ShowName_S01E01.properties
     */
    static Path toMetadataPath(Path videoFile) {
        String name = videoFile.getFileName().toString();
        String metaName = name.replaceFirst("\\.[^.]+$", ".properties");
        return videoFile.resolveSibling(metaName);
    }
}
