package com;

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

public class DistributedFileSystemIntegrationTest {
    private DistributedFileSystem dfs;
    private static final String TEST_FILE = "integration_test.txt";
    private static final String DOWNLOADED_FILE = "downloaded_integration_test.txt";
    private static final String TEST_CONTENT = "This is a test file for integration testing.";
    private static final AtomicInteger portCounter = new AtomicInteger(7000);

    @BeforeEach
    public void setUp() {
        List<String> chunkServerIds = Arrays.asList("server1", "server2", "server3");
        int metadataPort = portCounter.getAndAdd(100);
        dfs = new DistributedFileSystem("metadata.json", chunkServerIds, metadataPort, 10000, 10000);
    }

    @AfterEach
    public void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(TEST_FILE));
        Files.deleteIfExists(Path.of(DOWNLOADED_FILE));
    }

    @Test
    public void testFileUploadDownloadAndDelete() throws IOException {
        // Create test file
        Files.write(Path.of(TEST_FILE), TEST_CONTENT.getBytes());

        // Upload file
        dfs.uploadFile(TEST_FILE);

        // Download file
        dfs.downloadFile(TEST_FILE, DOWNLOADED_FILE);

        // Verify file is downloaded correctly
        String downloadedContent = new String(Files.readAllBytes(Path.of(DOWNLOADED_FILE)));
        assertTrue(downloadedContent.equals(TEST_CONTENT), 
            "Downloaded content should match original content");

        // Delete file
        dfs.deleteFile(TEST_FILE);

        // Verify file is deleted
        assertTrue(!Files.exists(Path.of(TEST_FILE)), 
            "Original file should be deleted after deletion");
        assertTrue(!Files.exists(Path.of(DOWNLOADED_FILE)), 
            "Downloaded file should be deleted after deletion");
    }

    @Test
    public void testConcurrentFileOperations() throws IOException, InterruptedException {
        // Create test files
        String[] testFiles = new String[3];
        String[] downloadedFiles = new String[3];
        
        for (int i = 0; i < 3; i++) {
            testFiles[i] = "test_file_" + i + ".txt";
            downloadedFiles[i] = "downloaded_test_file_" + i + ".txt";
            Files.write(Path.of(testFiles[i]), ("Test content " + i).getBytes());
        }

        // Start concurrent uploads
        Thread[] uploadThreads = new Thread[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            uploadThreads[i] = new Thread(() -> {
                try {
                    dfs.uploadFile(testFiles[index]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            uploadThreads[i].start();
        }

        // Wait for uploads to complete
        for (Thread thread : uploadThreads) {
            thread.join();
        }

        // Verify all files are uploaded
        for (int i = 0; i < 3; i++) {
            dfs.downloadFile(testFiles[i], downloadedFiles[i]);
            String content = new String(Files.readAllBytes(Path.of(downloadedFiles[i])));
            assertTrue(content.equals("Test content " + i), 
                "File " + i + " content should match original content");
        }
    }
} 