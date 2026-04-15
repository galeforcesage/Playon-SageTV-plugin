package com.galeforcesage.playon.api.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents PlayOn Cloud account information (credits, plan, etc.).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayOnAccount {

    @JsonProperty("email")
    private String email;

    @JsonProperty("plan")
    private String plan;

    @JsonProperty("credits")
    private int credits;

    @JsonProperty("storage_used")
    private String storageUsed;

    @JsonProperty("storage_limit")
    private String storageLimit;

    public PlayOnAccount() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public int getCredits() { return credits; }
    public void setCredits(int credits) { this.credits = credits; }

    public String getStorageUsed() { return storageUsed; }
    public void setStorageUsed(String storageUsed) { this.storageUsed = storageUsed; }

    public String getStorageLimit() { return storageLimit; }
    public void setStorageLimit(String storageLimit) { this.storageLimit = storageLimit; }

    @Override
    public String toString() {
        return "PlayOnAccount{email='" + email + "', plan='" + plan +
                "', credits=" + credits + "}";
    }
}
