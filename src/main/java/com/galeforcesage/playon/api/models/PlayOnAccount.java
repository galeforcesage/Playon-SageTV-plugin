package com.galeforcesage.playon.api.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents PlayOn Cloud account information (credits, plan, etc.).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayOnAccount {

    @JsonProperty("Email")
    private String email;

    @JsonProperty("Subscription")
    private Object subscription;

    @JsonProperty("TotalCredits")
    private int totalCredits;

    @JsonProperty("IndividualCredits")
    private int individualCredits;

    @JsonProperty("Credits")
    private Object creditsDetail;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("StorageDownloads")
    private int storageDownloads;

    @JsonProperty("NonStorageDownloads")
    private int nonStorageDownloads;

    // Legacy field aliases for backward compat
    @JsonProperty("credits")
    private int credits;

    @JsonProperty("plan")
    private String plan;

    @JsonProperty("storage_used")
    private String storageUsed;

    @JsonProperty("storage_limit")
    private String storageLimit;

    public PlayOnAccount() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPlan() {
        // Try Subscription first (from API), then legacy plan field
        if (subscription != null) {
            return String.valueOf(subscription);
        }
        return plan;
    }
    public void setPlan(String plan) { this.plan = plan; }

    public int getCredits() {
        // TotalCredits from API, fall back to legacy credits
        return totalCredits > 0 ? totalCredits : credits;
    }
    public void setCredits(int credits) { this.credits = credits; }

    public int getTotalCredits() { return totalCredits; }
    public int getIndividualCredits() { return individualCredits; }

    public String getStorageUsed() { return storageUsed; }
    public void setStorageUsed(String storageUsed) { this.storageUsed = storageUsed; }

    public String getStorageLimit() { return storageLimit; }
    public void setStorageLimit(String storageLimit) { this.storageLimit = storageLimit; }

    public String getName() { return name; }

    @Override
    public String toString() {
        return "PlayOnAccount{email='" + email + "', totalCredits=" + totalCredits +
                ", individualCredits=" + individualCredits + "}";
    }
}
