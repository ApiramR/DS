package com.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServerHealthCheckerTest {
    private MetadataServer metadataServer;
    private ChunkServer server1;
    private ChunkServer server2;
    private static final AtomicInteger portCounter = new AtomicInteger(9000);

    @BeforeEach
    public void setUp() {
        // Use a larger gap between port ranges to avoid conflicts
        int metadataPort = portCounter.getAndAdd(500);
        metadataServer = new MetadataServer(metadataPort);
        metadataServer.start();
        
        // Use different base ports for chunk servers to avoid conflicts
        int chunkBasePort = portCounter.getAndAdd(50);
        server1 = new ChunkServer("server1", "localhost", chunkBasePort);
        server2 = new ChunkServer("server2", "localhost", chunkBasePort + 1);
    }

    @AfterEach
    public void tearDown() {
        // Ensure proper cleanup
        if (server1 != null) {
            server1.stop();
        }
        if (server2 != null) {
            server2.stop();
        }
        // Metadata server will be cleaned up by JVM
    }

    @Test
    public void testServerHealthCheck() throws InterruptedException {
        // Start servers
        new Thread(() -> server1.start()).start();
        new Thread(() -> server2.start()).start();
        
        // Wait for initial heartbeats
        TimeUnit.SECONDS.sleep(6);
        
        // Verify servers are active
        assertTrue(server1.isActive(), "Server1 should be active");
        assertTrue(server2.isActive(), "Server2 should be active");
    }
} 