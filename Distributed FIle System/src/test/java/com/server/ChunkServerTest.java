package com.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ChunkServerTest {
    private ChunkServer chunkServer;
    private static final String TEST_DIR = "test_chunks";

    @BeforeEach
    public void setUp() {
        chunkServer = new ChunkServer("server1", "localhost", 6000);
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Clean up test files
        Path testPath = Path.of(TEST_DIR);
        if (Files.exists(testPath)) {
            Files.walk(testPath)
                 .map(Path::toFile)
                 .forEach(file -> file.delete());
            Files.delete(testPath);
        }
    }

    @Test
    public void testStoreAndGetChunk() throws IOException {
        String filename = "test.txt";
        String chunkId = "chunk1";
        byte[] chunkData = "This is a test chunk.".getBytes();
        
        // Store the chunk
        chunkServer.storeChunk(filename, chunkId, chunkData);
        
        // Retrieve chunk and check if it matches the original data
        byte[] retrievedChunk = chunkServer.getChunk(filename, chunkId);
        assertArrayEquals(chunkData, retrievedChunk);
    }
} 