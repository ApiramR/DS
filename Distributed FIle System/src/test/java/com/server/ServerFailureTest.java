package com.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServerFailureTest {
    private MetadataServer metadataServer;
    private ChunkServer server1;
    private ChunkServer server2;
    private ChunkServer server3;
    private static final AtomicInteger portCounter = new AtomicInteger(7000);

    @BeforeEach
    public void setUp() {
        int metadataPort = portCounter.getAndAdd(1);
        metadataServer = new MetadataServer(metadataPort);
        metadataServer.start();
        
        // Use different base ports for chunk servers to avoid conflicts
        int chunkBasePort = portCounter.getAndAdd(10);
        server1 = new ChunkServer("server1", "localhost", chunkBasePort);
        server2 = new ChunkServer("server2", "localhost", chunkBasePort + 1);
        server3 = new ChunkServer("server3", "localhost", chunkBasePort + 2);
    }

    @AfterEach
    public void tearDown() {
        // Cleanup will be handled by the servers themselves
    }

    @Test
    public void testServerFailureDetection() throws InterruptedException {
        // Start all servers
        new Thread(() -> server1.start()).start();
        new Thread(() -> server2.start()).start();
        new Thread(() -> server3.start()).start();
        
        // Wait for initial heartbeats
        TimeUnit.SECONDS.sleep(6);
        
        // Verify all servers are active
        assertTrue(server1.isActive(), "Server1 should be active");
        assertTrue(server2.isActive(), "Server2 should be active");
        assertTrue(server3.isActive(), "Server3 should be active");
        
        // Simulate server1 failure
        server1.stop();
        
        // Wait for failure detection
        TimeUnit.SECONDS.sleep(6);
        
        // Verify server1 is marked as inactive
        assertFalse(server1.isActive(), "Server1 should be inactive");
        assertTrue(server2.isActive(), "Server2 should still be active");
        assertTrue(server3.isActive(), "Server3 should still be active");
    }
} 