package com.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class MetadataServer {
    private static final Logger logger = Logger.getLogger(MetadataServer.class.getName());
    private final int port;
    private final Map<String, ChunkServer> chunkServers;
    private final Map<String, FileMetadata> fileMetadata;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Map<String, Set<String>> chunkLocations; // Maps chunkId to serverIds
    private static final int HEARTBEAT_INTERVAL = 5000; // 5 seconds
    private static final int HEARTBEAT_TIMEOUT = 15000; // 15 seconds
    private static final int REPLICATION_FACTOR = 3; // Number of replicas per chunk
    private volatile boolean running;
    private ServerSocket serverSocket;

    public MetadataServer(int port) {
        this.port = port;
        this.chunkServers = new ConcurrentHashMap<>();
        this.fileMetadata = new ConcurrentHashMap<>();
        this.chunkLocations = new ConcurrentHashMap<>();
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1);
        this.running = false;
    }

    public void start() {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            logger.info("Metadata Server started on port " + port);
            
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleClient(clientSocket)).start();
                } catch (IOException e) {
                    if (running) {
                        logger.warning("Error accepting connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.severe("Failed to start metadata server: " + e.getMessage());
        }
        
        // Start heartbeat checker and failure recovery
        heartbeatExecutor.scheduleAtFixedRate(this::checkHeartbeats, 0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.warning("Error closing server socket: " + e.getMessage());
            }
        }
        logger.info("Metadata Server stopped");
    }

    private void handleClient(Socket clientSocket) {
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {
            
            String request = in.readUTF();
            String[] parts = request.split(":");
            
            switch (parts[0]) {
                case "REGISTER":
                    handleRegister(parts, out);
                    break;
                case "HEARTBEAT":
                    handleHeartbeat(parts, out);
                    break;
                case "UPLOAD":
                    handleUpload(parts, out);
                    break;
                case "DOWNLOAD":
                    handleDownload(parts, out);
                    break;
                case "CHUNK_STORED":
                    handleChunkStored(parts, out);
                    break;
                default:
                    out.writeUTF("ERROR: Unknown request type");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRegister(String[] parts, DataOutputStream out) throws IOException {
        String serverId = parts[1];
        String address = parts[2];
        int port = Integer.parseInt(parts[3]);
        
        ChunkServer server = new ChunkServer(serverId, address, port);
        chunkServers.put(serverId, server);
        out.writeUTF("OK");
    }

    private void handleHeartbeat(String[] parts, DataOutputStream out) throws IOException {
        String serverId = parts[1];
        ChunkServer server = chunkServers.get(serverId);
        
        if (server != null) {
            server.updateHeartbeat();
            out.writeUTF("OK");
        } else {
            out.writeUTF("ERROR: Server not registered");
        }
    }

    private void handleUpload(String[] parts, DataOutputStream out) throws IOException {
        String filename = parts[1];
        long fileSize = Long.parseLong(parts[2]);
        int chunkSize = Integer.parseInt(parts[3]);
        String fileHash = parts[4];
        long totalChunks = Long.parseLong(parts[5]);
        
        // Select chunk servers for replication
        List<ChunkServer> selectedServers = selectChunkServers(totalChunks);
        if (selectedServers.isEmpty()) {
            out.writeUTF("ERROR: No available chunk servers");
            return;
        }
        
        // Store file metadata
        fileMetadata.put(filename, new FileMetadata(filename, fileSize, totalChunks, fileHash));
        
        // Send selected servers to client
        StringBuilder response = new StringBuilder("OK:");
        for (ChunkServer server : selectedServers) {
            response.append(server.getAddress()).append(":").append(server.getPort()).append(",");
        }
        response.setLength(response.length() - 1); // Remove trailing comma
        out.writeUTF(response.toString());
    }

    private void handleDownload(String[] parts, DataOutputStream out) throws IOException {
        String filename = parts[1];
        FileMetadata metadata = fileMetadata.get(filename);
        
        if (metadata == null) {
            out.writeUTF("ERROR: File not found");
            return;
        }
        
        // Get available chunk servers
        List<ChunkServer> availableServers = new ArrayList<>();
        for (ChunkServer server : chunkServers.values()) {
            if (server.isActive()) {
                availableServers.add(server);
            }
        }
        
        if (availableServers.isEmpty()) {
            out.writeUTF("ERROR: No available chunk servers");
            return;
        }
        
        // Send server list to client
        StringBuilder response = new StringBuilder("OK:");
        for (ChunkServer server : availableServers) {
            response.append(server.getAddress()).append(":").append(server.getPort()).append(",");
        }
        response.setLength(response.length() - 1); // Remove trailing comma
        out.writeUTF(response.toString());
    }

    private void handleChunkStored(String[] parts, DataOutputStream out) throws IOException {
        String serverId = parts[1];
        String filename = parts[2];
        String chunkId = parts[3];
        
        // Update chunk locations
        chunkLocations.computeIfAbsent(chunkId, k -> ConcurrentHashMap.newKeySet()).add(serverId);
        out.writeUTF("OK");
    }

    private List<ChunkServer> selectChunkServers(long numChunks) {
        List<ChunkServer> availableServers = new ArrayList<>();
        for (ChunkServer server : chunkServers.values()) {
            if (server.isActive()) {
                availableServers.add(server);
            }
        }
        
        if (availableServers.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Simple round-robin selection
        List<ChunkServer> selected = new ArrayList<>();
        int serverIndex = 0;
        for (int i = 0; i < numChunks * REPLICATION_FACTOR; i++) {
            selected.add(availableServers.get(serverIndex));
            serverIndex = (serverIndex + 1) % availableServers.size();
        }
        
        return selected;
    }

    private void checkHeartbeats() {
        long currentTime = System.currentTimeMillis();
        for (ChunkServer server : chunkServers.values()) {
            if (currentTime - server.getLastHeartbeat() > HEARTBEAT_TIMEOUT) {
                handleServerFailure(server.getId());
            }
        }
    }

    private void handleServerFailure(String serverId) {
        ChunkServer failedServer = chunkServers.get(serverId);
        if (failedServer != null) {
            failedServer.setActive(false);
            logger.info("Server " + serverId + " marked as inactive");
            
            // Find affected chunks
            for (Map.Entry<String, Set<String>> entry : chunkLocations.entrySet()) {
                if (entry.getValue().contains(serverId)) {
                    String chunkId = entry.getKey();
                    entry.getValue().remove(serverId);
                    
                    // If replication factor is below threshold, trigger replication
                    if (entry.getValue().size() < REPLICATION_FACTOR) {
                        replicateChunk(chunkId);
                    }
                }
            }
        }
    }

    private void replicateChunk(String chunkId) {
        // Find a healthy server to replicate to
        ChunkServer targetServer = null;
        for (ChunkServer server : chunkServers.values()) {
            if (server.isActive() && !chunkLocations.get(chunkId).contains(server.getId())) {
                targetServer = server;
                break;
            }
        }
        
        if (targetServer != null) {
            // Find a source server that has the chunk
            String sourceServerId = chunkLocations.get(chunkId).iterator().next();
            ChunkServer sourceServer = chunkServers.get(sourceServerId);
            
            if (sourceServer != null) {
                // Trigger replication
                try (Socket socket = new Socket(sourceServer.getAddress(), sourceServer.getPort());
                     DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                    
                    out.writeUTF("REPLICATE_CHUNK:" + chunkId + ":" + targetServer.getAddress() + ":" + targetServer.getPort());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static class FileMetadata {
        private final String filename;
        private final long fileSize;
        private final long totalChunks;
        private final String fileHash;

        public FileMetadata(String filename, long fileSize, long totalChunks, String fileHash) {
            this.filename = filename;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.fileHash = fileHash;
        }

        public String getFilename() {
            return filename;
        }

        public long getFileSize() {
            return fileSize;
        }

        public long getTotalChunks() {
            return totalChunks;
        }

        public String getFileHash() {
            return fileHash;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java MetadataServer <port>");
            return;
        }
        
        int port = Integer.parseInt(args[0]);
        MetadataServer server = new MetadataServer(port);
        server.start();
    }
} 