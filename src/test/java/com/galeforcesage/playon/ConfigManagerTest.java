package com.galeforcesage.playon.sagetv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultValues() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        config.resetToDefaults();

        assertEquals("", config.getCloudEmail());
        assertEquals("", config.getCloudPassword());
        assertEquals(120L, config.getSyncIntervalMinutes()); // 2 hours default per PRD
        assertFalse(config.isRemoveFromCloudAfterDownload());
        assertFalse(config.isDebugLogging());
    }

    @Test
    void syncIntervalClampedTo30MinMinimum() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        config.setSyncIntervalMinutes(10); // below 30 min minimum
        assertEquals(30L, config.getSyncIntervalMinutes());
    }

    @Test
    void syncIntervalClampedTo24HrMaximum() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        config.setSyncIntervalMinutes(2880); // above 1440 max
        assertEquals(1440L, config.getSyncIntervalMinutes());
    }

    @Test
    void passwordEncryptedAndDecrypted() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        config.setCloudPassword("mySecretPassword123!");
        config.save();

        ConfigManager reloaded = new ConfigManager(tempDir.resolve("test.properties"));
        assertEquals("mySecretPassword123!", reloaded.getCloudPassword());
    }

    @Test
    void emptyPasswordReturnsEmpty() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        config.setCloudPassword("");
        assertEquals("", config.getCloudPassword());
    }

    @Test
    void configPersistsAcrossReload() {
        Path configFile = tempDir.resolve("persist.properties");

        ConfigManager config1 = new ConfigManager(configFile);
        config1.setCloudEmail("user@example.com");
        config1.setSyncIntervalMinutes(360);
        config1.setRemoveFromCloudAfterDownload(true);
        config1.save();

        ConfigManager config2 = new ConfigManager(configFile);
        assertEquals("user@example.com", config2.getCloudEmail());
        assertEquals(360L, config2.getSyncIntervalMinutes());
        assertTrue(config2.isRemoveFromCloudAfterDownload());
    }

    @Test
    void syncIntervalOptionsParseCorrectly() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        assertEquals(30L, config.parseSyncIntervalOption("30 minutes"));
        assertEquals(60L, config.parseSyncIntervalOption("1 hour"));
        assertEquals(120L, config.parseSyncIntervalOption("2 hours"));
        assertEquals(360L, config.parseSyncIntervalOption("6 hours"));
        assertEquals(720L, config.parseSyncIntervalOption("12 hours"));
        assertEquals(1440L, config.parseSyncIntervalOption("24 hours"));
        assertEquals(120L, config.parseSyncIntervalOption("garbage")); // default
    }

    @Test
    void syncIntervalDisplayText() {
        ConfigManager config = new ConfigManager(tempDir.resolve("test.properties"));
        config.setSyncIntervalMinutes(30);
        assertEquals("30 minutes", config.getSyncIntervalDisplay());

        config.setSyncIntervalMinutes(120);
        assertEquals("2 hours", config.getSyncIntervalDisplay());

        config.setSyncIntervalMinutes(60);
        assertEquals("1 hour", config.getSyncIntervalDisplay());
    }
}
