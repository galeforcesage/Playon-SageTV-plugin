package com.galeforcesage.playon.api.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a recording from PlayOn Cloud.
 * <p>
 * Fields based on PlayOn API TagSet: playon.ID, playon.Series, playon.Name,
 * playon.ProviderID, playon.HumanSize, resolution, status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayOnRecording {

    @JsonProperty("ID")
    private long id;

    @JsonProperty("Series")
    private String series;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("ProviderID")
    private String providerId;

    @JsonProperty("HumanSize")
    private String humanSize;

    @JsonProperty("resolution")
    private String resolution;

    @JsonProperty("status")
    private String status;

    @JsonProperty("download_id")
    private long downloadId;

    @JsonProperty("season")
    private int season;

    @JsonProperty("episode")
    private int episode;

    @JsonProperty("description")
    private String description;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;

    @JsonProperty("duration")
    private long durationSeconds;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("expires_at")
    private String expiresAt;

    @JsonProperty("nice_name")
    private String niceName;

    public PlayOnRecording() {}

    // ==================== Status checks ====================

    /**
     * Maps numeric status codes to human-readable strings.
     * Known codes: 0=Queued, 1=Recording, 2=Completed, 3=Failed, 4=Expired
     */
    public String getStatusDisplay() {
        if (status == null) return "Unknown";
        switch (status) {
            case "0": return "Queued";
            case "1": return "Recording";
            case "2": return "Completed";
            case "3": return "Failed";
            case "4": return "Expired";
            default:
                // If it's already a word (e.g. "completed"), capitalize it
                if (status.length() > 0 && !Character.isDigit(status.charAt(0))) {
                    return status.substring(0, 1).toUpperCase() + status.substring(1).toLowerCase();
                }
                return status;
        }
    }

    public boolean isCompleted() {
        return "completed".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status)
                || "2".equals(status);
    }

    public boolean isRecording() {
        return "recording".equalsIgnoreCase(status) || "1".equals(status);
    }

    public boolean isQueued() {
        return "queued".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)
                || "0".equals(status);
    }

    public boolean isEpisode() {
        return series != null && !series.isEmpty() && episode > 0;
    }

    /**
     * Generates a SageTV-friendly filename.
     * Format: ShowName_S01E01_EpisodeName.mp4  or  MovieTitle.mp4
     */
    public String toFilename() {
        StringBuilder sb = new StringBuilder();
        if (isEpisode()) {
            sb.append(sanitize(series));
            sb.append("_S").append(String.format("%02d", season));
            sb.append("E").append(String.format("%02d", episode));
            if (name != null && !name.isEmpty()) {
                sb.append("_").append(sanitize(name));
            }
        } else {
            sb.append(sanitize(niceName != null && !niceName.isEmpty() ? niceName :
                    name != null ? name : "Unknown"));
        }
        sb.append(".mp4");
        return sb.toString();
    }

    /**
     * Returns the service-based subdirectory for organized storage.
     * Format: <ServiceName>/<ShowName>/ or <ServiceName>/Movies/
     */
    public String toSubdirectoryPath() {
        StringBuilder sb = new StringBuilder();
        // Service directory
        String service = providerId != null && !providerId.isEmpty() ? providerId : "Unknown";
        sb.append(sanitize(service));
        sb.append('/');
        // Show directory
        if (isEpisode() && series != null) {
            sb.append(sanitize(series));
        } else {
            sb.append("Movies");
        }
        return sb.toString();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
    }

    // ==================== Getters and Setters ====================

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getSeries() { return series; }
    public void setSeries(String series) { this.series = series; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public String getHumanSize() { return humanSize; }
    public void setHumanSize(String humanSize) { this.humanSize = humanSize; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getDownloadId() { return downloadId; }
    public void setDownloadId(long downloadId) { this.downloadId = downloadId; }

    public int getSeason() { return season; }
    public void setSeason(int season) { this.season = season; }

    public int getEpisode() { return episode; }
    public void setEpisode(int episode) { this.episode = episode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(long durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    public String getNiceName() { return niceName; }
    public void setNiceName(String niceName) { this.niceName = niceName; }

    @Override
    public String toString() {
        return "PlayOnRecording{id=" + id + ", series='" + series +
                "', name='" + name + "', provider='" + providerId +
                "', status='" + status + "'}";
    }
}
