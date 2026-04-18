package com.galeforcesage.playon.api.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a streaming service linked to the PlayOn Cloud account.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayOnService {

    @JsonProperty("ID")
    private String id;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("icon_url")
    private String iconUrl;

    public PlayOnService() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }

    @Override
    public String toString() {
        return "PlayOnService{id='" + id + "', name='" + name + "', enabled=" + enabled + "}";
    }
}
