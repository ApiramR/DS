package com.timesync;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TimeSynchronizationTest {
    private TimeSyncService timeSyncService;
    private static final List<String> TEST_TIME_SERVERS = Arrays.asList(
        "time1.google.com",
        "time2.google.com",
        "time3.google.com"
    );

    @BeforeEach
    public void setUp() {
        timeSyncService = new TimeSyncService(TEST_TIME_SERVERS);
    }

    @AfterEach
    public void tearDown() {
        timeSyncService.stop();
    }

    @Test
    public void testTimeSynchronization() throws InterruptedException {
        // Start time synchronization
        timeSyncService.start();
        
        // Wait for synchronization
        TimeUnit.SECONDS.sleep(2);

        // Verify time is synchronized
        long localTime = System.currentTimeMillis();
        long syncedTime = timeSyncService.getCurrentTime();
        
        // Allow for some network delay (100ms)
        assertTrue(Math.abs(localTime - syncedTime) < 100, 
            "Time difference should be less than 100ms");
    }
} 