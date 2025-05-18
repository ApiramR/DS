package com;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DistributedFileSystemEndToEndTest {
    private DistributedFileSystem dfs;
    private static final String TEST_FILE = "testfile.txt";
    private static final String DOWNLOADED_FILE = "downloaded_testfile.txt";
    private static final AtomicInteger portCounter = new AtomicInteger(7000);

    @BeforeEach
    public void setUp() {
        List<String> chunkServerIds = Arrays.asList("server1", "server2", "server3");
        int metadataPort = portCounter.getAndAdd(1);
        dfs = new DistributedFileSystem("metadata.json", chunkServerIds, metadataPort, 10000, 10000);
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Clean up test files
        Files.deleteIfExists(Path.of(TEST_FILE));
        Files.deleteIfExists(Path.of(DOWNLOADED_FILE));
    }

    @Test
    public void testFileUploadAndDownload() throws IOException {
        // Create test file
        String content = "This is a test file content.";
        Files.write(Path.of(TEST_FILE), content.getBytes());

        // Upload file
        dfs.uploadFile(TEST_FILE);

        // Download file
        dfs.downloadFile(TEST_FILE, DOWNLOADED_FILE);

        // Verify file is downloaded correctly
        File downloadedFile = new File(DOWNLOADED_FILE);
        assertTrue(downloadedFile.exists(), "Downloaded file should exist");
        assertTrue(downloadedFile.length() > 0, "Downloaded file should not be empty");
        
        // Verify content
        String downloadedContent = new String(Files.readAllBytes(Path.of(DOWNLOADED_FILE)));
        assertTrue(downloadedContent.equals(content), "Downloaded content should match original content");
    }
} 