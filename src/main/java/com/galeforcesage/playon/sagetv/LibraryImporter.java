package com.galeforcesage.playon.sagetv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles registration of the PlayOn download directory with SageTV's
 * library import system.
 * <p>
 * For v1, this ensures the download directory exists and is ready for
 * SageTV's automatic import scanner. Users should add the directory
 * to SageTV's Video Import paths via Setup → Video Library.
 * <p>
 * Future versions may use sagex-api to register the directory programmatically.
 */
public class LibraryImporter {

    private static final Logger LOG = Logger.getLogger(LibraryImporter.class.getName());

    /**
     * Ensure the download directory exists and is ready for SageTV import.
     *
     * @param downloadDir the configured download directory path
     * @return true if the directory is ready
     */
    public boolean ensureImportDirectory(String downloadDir) {
        try {
            Path dir = Path.of(downloadDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                LOG.info("Created PlayOn download directory: " + dir);
            }

            if (!Files.isDirectory(dir)) {
                LOG.severe("Download path is not a directory: " + downloadDir);
                return false;
            }

            if (!Files.isWritable(dir)) {
                LOG.severe("Download directory is not writable: " + downloadDir);
                return false;
            }

            LOG.info("PlayOn download directory ready: " + dir.toAbsolutePath());
            LOG.info("NOTE: Ensure this directory is added to SageTV's Video Import paths " +
                    "(Setup → Video Library → Video Directories)");
            return true;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to prepare download directory: " + downloadDir, e);
            return false;
        }
    }
}
