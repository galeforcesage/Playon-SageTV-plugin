package com.galeforcesage.playon.api.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayOnRecordingTest {

    private PlayOnRecording makeRecording(String series, String name, String providerId,
                                          int season, int episode) {
        PlayOnRecording rec = new PlayOnRecording();
        rec.setSeries(series);
        rec.setName(name);
        rec.setProviderId(providerId);
        rec.setSeason(season);
        rec.setEpisode(episode);
        rec.setStatus("Done");
        return rec;
    }

    @Test
    void filenameForSeriesWithSeasonAndEpisode() {
        PlayOnRecording rec = makeRecording("Breaking Bad", "Pilot", "Netflix", 1, 1);
        String filename = rec.toFilename();
        assertEquals("Breaking Bad_S01E01_Pilot.mp4", filename);
    }

    @Test
    void filenameForMovieNoSeasonEpisode() {
        PlayOnRecording rec = makeRecording("", "Inception", "Netflix", 0, 0);
        String filename = rec.toFilename();
        assertEquals("Inception.mp4", filename);
    }

    @Test
    void filenameStripsInvalidChars() {
        PlayOnRecording rec = makeRecording("The Good: Place", "What/We:Owe", "Netflix", 1, 1);
        String filename = rec.toFilename();
        // Colons, slashes replaced with underscores
        assertEquals("The Good_ Place_S01E01_What_We_Owe.mp4", filename);
    }

    @Test
    void subdirectoryPathForEpisode() {
        PlayOnRecording rec = makeRecording("Breaking Bad", "Pilot", "Netflix", 1, 1);
        assertEquals("Netflix/Breaking Bad", rec.toSubdirectoryPath());
    }

    @Test
    void subdirectoryPathForMovie() {
        PlayOnRecording rec = makeRecording("", "Inception", "Netflix", 0, 0);
        assertEquals("Netflix/Movies", rec.toSubdirectoryPath());
    }

    @Test
    void isCompletedStatus() {
        PlayOnRecording rec = makeRecording("Test", "Test", "Netflix", 1, 1);
        rec.setStatus("Done");
        assertTrue(rec.isCompleted());

        rec.setStatus("completed");
        assertTrue(rec.isCompleted());

        rec.setStatus("Recording");
        assertFalse(rec.isCompleted());
    }

    @Test
    void isRecordingStatus() {
        PlayOnRecording rec = makeRecording("Test", "Test", "Netflix", 1, 1);
        rec.setStatus("recording");
        assertTrue(rec.isRecording());

        rec.setStatus("Done");
        assertFalse(rec.isRecording());
    }

    @Test
    void isQueuedStatus() {
        PlayOnRecording rec = makeRecording("Test", "Test", "Netflix", 1, 1);
        rec.setStatus("queued");
        assertTrue(rec.isQueued());

        rec.setStatus("pending");
        assertTrue(rec.isQueued());

        rec.setStatus("Done");
        assertFalse(rec.isQueued());
    }

    @Test
    void isEpisodeDetection() {
        PlayOnRecording rec = makeRecording("Breaking Bad", "Pilot", "Netflix", 1, 1);
        assertTrue(rec.isEpisode());

        PlayOnRecording movie = makeRecording("", "Inception", "Netflix", 0, 0);
        assertFalse(movie.isEpisode());
    }

    @Test
    void seasonEpisodePadding() {
        PlayOnRecording rec = makeRecording("Show", "Episode", "Service", 2, 15);
        String filename = rec.toFilename();
        assertEquals("Show_S02E15_Episode.mp4", filename);
    }

    @Test
    void idIsLong() {
        PlayOnRecording rec = new PlayOnRecording();
        rec.setId(123456L);
        assertEquals(123456L, rec.getId());
    }
}
