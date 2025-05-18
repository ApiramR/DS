package com.timesync;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TimeSyncService {
    private final AtomicLong timeOffset;
    private final ScheduledExecutorService syncExecutor;
    private final List<String> timeServers;
    private static final long SYNC_INTERVAL = 1000; // 1 second
    private static final int MAX_SYNC_ATTEMPTS = 3;
    private static final long SYNC_TIMEOUT = 1000; // 1 second timeout

    public TimeSyncService(List<String> timeServers) {
        this.timeOffset = new AtomicLong(0);
        this.syncExecutor = Executors.newScheduledThreadPool(1);
        this.timeServers = timeServers;
    }

    public void start() {
        syncExecutor.scheduleAtFixedRate(this::syncTime, 0, SYNC_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        syncExecutor.shutdown();
    }

    public long getCurrentTime() {
        return System.currentTimeMillis() + timeOffset.get();
    }

    private void syncTime() {
        for (String server : timeServers) {
            for (int attempt = 0; attempt < MAX_SYNC_ATTEMPTS; attempt++) {
                try {
                    long offset = calculateTimeOffset(server);
                    if (offset != Long.MAX_VALUE) {
                        timeOffset.set(offset);
                        return;
                    }
                } catch (Exception e) {
                    if (attempt == MAX_SYNC_ATTEMPTS - 1) {
                        System.out.println("Failed to sync with server " + server + " after " + MAX_SYNC_ATTEMPTS + " attempts: " + e.getMessage());
                    }
                }
            }
        }
    }

    private long calculateTimeOffset(String serverAddress) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverAddress, 123), (int) SYNC_TIMEOUT);
            
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Send time sync request
            long t1 = System.currentTimeMillis();
            out.writeLong(t1);

            // Receive server's time
            long t2 = in.readLong();
            long t3 = System.currentTimeMillis();

            // Calculate offset: (t2 - t1 + t2 - t3) / 2
            return (t2 - t1 + t2 - t3) / 2;
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    public static void main(String[] args) {
        List<String> timeServers = Arrays.asList(
            "time1.google.com",
            "time2.google.com",
            "time3.google.com"
        );
        
        TimeSyncService timeSync = new TimeSyncService(timeServers);
        timeSync.start();
    }
} 