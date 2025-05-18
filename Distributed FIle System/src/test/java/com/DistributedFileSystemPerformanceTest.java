package com;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DistributedFileSystemPerformanceTest {
    private DistributedFileSystem dfs;
    private static final String TEST_FILE = "performance_test.txt";
    private static final String DOWNLOADED_FILE = "downloaded_performance_test.txt";
    private static final int FILE_SIZE = 1024 * 1024; // 1MB
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
    public void testUploadPerformance() throws IOException {
        // Create test file with random data
        byte[] data = new byte[FILE_SIZE];
        for (int i = 0; i < FILE_SIZE; i++) {
            data[i] = (byte) (Math.random() * 256);
        }
        Files.write(Path.of(TEST_FILE), data);

        // Measure upload time
        long startTime = System.nanoTime();
        dfs.uploadFile(TEST_FILE);
        long endTime = System.nanoTime();
        
        // Calculate upload speed (MB/s)
        double uploadTimeSeconds = TimeUnit.NANOSECONDS.toSeconds(endTime - startTime);
        double uploadSpeed = (FILE_SIZE / (1024.0 * 1024.0)) / uploadTimeSeconds;
        
        // Verify reasonable upload speed (at least 1 MB/s)
        assertTrue(uploadSpeed >= 1.0, 
            "Upload speed should be at least 1 MB/s, but was " + uploadSpeed + " MB/s");
    }

    @Test
    public void testDownloadPerformance() throws IOException {
        // Create and upload test file
        byte[] data = new byte[FILE_SIZE];
        for (int i = 0; i < FILE_SIZE; i++) {
            data[i] = (byte) (Math.random() * 256);
        }
        Files.write(Path.of(TEST_FILE), data);
        dfs.uploadFile(TEST_FILE);

        // Measure download time
        long startTime = System.nanoTime();
        dfs.downloadFile(TEST_FILE, DOWNLOADED_FILE);
        long endTime = System.nanoTime();
        
        // Calculate download speed (MB/s)
        double downloadTimeSeconds = TimeUnit.NANOSECONDS.toSeconds(endTime - startTime);
        double downloadSpeed = (FILE_SIZE / (1024.0 * 1024.0)) / downloadTimeSeconds;
        
        // Verify reasonable download speed (at least 1 MB/s)
        assertTrue(downloadSpeed >= 1.0, 
            "Download speed should be at least 1 MB/s, but was " + downloadSpeed + " MB/s");
    }
} 