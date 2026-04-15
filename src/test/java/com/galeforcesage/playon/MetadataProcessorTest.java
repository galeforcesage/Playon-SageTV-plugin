package com.galeforcesage.playon.sagetv;

import com.galeforcesage.playon.api.models.PlayOnRecording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class MetadataProcessorTest {

    @TempDir
    Path tempDir;

    private PlayOnRecording makeRecording() {
        PlayOnRecording rec = new PlayOnRecording();
        rec.setId(123L);
        rec.setSeries("Breaking Bad");
        rec.setName("Pilot");
        rec.setProviderId("Netflix");
        rec.setSeason(1);
        rec.setEpisode(1);
        rec.setStatus("Done");
        rec.setHumanSize("1.2 GB");
        return rec;
    }

    @Test
    void writesPropertiesSidecar() throws Exception {
        MetadataProcessor mp = new MetadataProcessor();
        PlayOnRecording rec = makeRecording();
        Path videoFile = tempDir.resolve("test.mp4");
        java.nio.file.Files.createFile(videoFile);

        mp.processRecording(rec, videoFile);

        // Sidecar replaces extension: test.mp4 -> test.properties
        Path propsFile = tempDir.resolve("test.properties");
        assertTrue(java.nio.file.Files.exists(propsFile));

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile.toFile())) {
            props.load(fis);
        }

        assertEquals("Breaking Bad", props.getProperty("Title"));
        assertEquals("Breaking Bad", props.getProperty("ShowTitle"));
        assertEquals("Pilot", props.getProperty("EpisodeName"));
        assertEquals("1", props.getProperty("SeasonNumber"));
        assertEquals("1", props.getProperty("EpisodeNumber"));
        assertEquals("PlayOn", props.getProperty("Category"));
        assertEquals("PlayOn (Netflix)", props.getProperty("Source"));
        assertEquals("PlayOn-123", props.getProperty("ExternalID"));
    }

    @Test
    void movieMetadataNoShowTitle() throws Exception {
        MetadataProcessor mp = new MetadataProcessor();
        PlayOnRecording rec = new PlayOnRecording();
        rec.setId(456L);
        rec.setName("Inception");
        rec.setSeries("");
        rec.setProviderId("Amazon Prime");
        rec.setSeason(0);
        rec.setEpisode(0);
        rec.setStatus("Done");

        Path videoFile = tempDir.resolve("movie.mp4");
        java.nio.file.Files.createFile(videoFile);

        mp.processRecording(rec, videoFile);

        Path propsFile = tempDir.resolve("movie.properties");
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile.toFile())) {
            props.load(fis);
        }

        assertEquals("Inception", props.getProperty("Title"));
        assertNull(props.getProperty("ShowTitle"));
        assertNull(props.getProperty("EpisodeName"));
        assertEquals("PlayOn (Amazon Prime)", props.getProperty("Source"));
    }

    @Test
    void metadataPathStripsExtension() {
        Path videoFile = Paths.get("/some/dir/ShowName_S01E01.mp4");
        Path metaFile = MetadataProcessor.toMetadataPath(videoFile);
        assertEquals("ShowName_S01E01.properties", metaFile.getFileName().toString());
    }
}
