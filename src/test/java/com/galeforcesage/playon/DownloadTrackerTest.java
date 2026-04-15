package com.galeforcesage.playon.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DownloadTrackerTest {

    @TempDir
    Path tempDir;

    @Test
    void tracksNewDownloadId() {
        DownloadTracker tracker = new DownloadTracker(tempDir.resolve("tracker.txt"));
        assertFalse(tracker.isDownloaded(123L));
        tracker.markDownloaded(123L);
        assertTrue(tracker.isDownloaded(123L));
    }

    @Test
    void persistsAcrossInstances() {
        Path trackerFile = tempDir.resolve("tracker.txt");
        DownloadTracker t1 = new DownloadTracker(trackerFile);
        t1.markDownloaded(1L);
        t1.markDownloaded(2L);

        DownloadTracker t2 = new DownloadTracker(trackerFile);
        assertTrue(t2.isDownloaded(1L));
        assertTrue(t2.isDownloaded(2L));
        assertFalse(t2.isDownloaded(3L));
    }

    @Test
    void handlesEmptyFile() {
        DownloadTracker tracker = new DownloadTracker(tempDir.resolve("empty.txt"));
        assertFalse(tracker.isDownloaded(999L));
        assertEquals(0, tracker.getCount());
    }

    @Test
    void noDuplicateIds() {
        DownloadTracker tracker = new DownloadTracker(tempDir.resolve("dupes.txt"));
        tracker.markDownloaded(42L);
        tracker.markDownloaded(42L);
        tracker.markDownloaded(42L);

        assertEquals(1, tracker.getCount());
    }

    @Test
    void getDownloadedIdsReturnsUnmodifiableSet() {
        DownloadTracker tracker = new DownloadTracker(tempDir.resolve("ids.txt"));
        tracker.markDownloaded(1L);
        tracker.markDownloaded(2L);

        Set<String> ids = tracker.getDownloadedIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("1"));
        assertTrue(ids.contains("2"));
        assertThrows(UnsupportedOperationException.class, () -> ids.add("3"));
    }
}
