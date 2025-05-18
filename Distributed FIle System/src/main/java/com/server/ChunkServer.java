package com.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

public class ChunkServer {
    private static final Logger logger = Logger.getLogger(ChunkServer.class.getName());
    private final String serverId;
    private final String address;
    private final int port;
    private final ConcurrentHashMap<String, byte[]> chunks;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private final Map<String, ChunkInfo> chunkRegistry;
    private final String storagePath;
    private final ScheduledExecutorService heartbeatExecutor;
    private long lastHeartbeat;
    private boolean isActive;
    private static final int HEARTBEAT_INTERVAL = 1000; // 1 second

    public ChunkServer(String serverId, String address, int port) {
        this.serverId = serverId;
        this.address = address;
        this.port = port;
        this.chunks = new ConcurrentHashMap<>();
        this.running = false;
        this.chunkRegistry = new ConcurrentHashMap<>();
        this.storagePath = "chunks/" + serverId;
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1);
        this.lastHeartbeat = System.currentTimeMillis();
        this.isActive = true;
        
        try {
            Files.createDirectories(Paths.get(storagePath));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            logger.info("Chunk Server " + serverId + " started on port " + port);
            
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
            logger.severe("Failed to start chunk server: " + e.getMessage());
        }
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
        logger.info("Chunk Server " + serverId + " stopped");
    }

    public String getServerId() {
        return serverId;
    }

    public int getPort() {
        return port;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public void updateHeartbeat() {
        lastHeartbeat = System.currentTimeMillis();
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void storeChunk(String filename, String chunkId, byte[] data) throws IOException {
        String chunkPath = getChunkPath(filename, chunkId);
        Files.write(Paths.get(chunkPath), data);
        chunkRegistry.put(chunkId, new ChunkInfo(filename, chunkId, data.length));
    }

    public byte[] getChunk(String filename, String chunkId) throws IOException {
        String chunkPath = getChunkPath(filename, chunkId);
        if (Files.exists(Paths.get(chunkPath))) {
            return Files.readAllBytes(Paths.get(chunkPath));
        }
        return null;
    }

    private String getChunkPath(String filename, String chunkId) {
        return storagePath + "/" + filename + "_" + chunkId;
    }

    private void handleClient(Socket clientSocket) {
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {
            
            String request = in.readUTF();
            String[] parts = request.split(":");
            
            switch (parts[0]) {
                case "STORE_CHUNK":
                    handleStoreChunk(parts[1], parts[2], in, out);
                    break;
                case "GET_CHUNK":
                    handleGetChunk(parts[1], parts[2], out);
                    break;
                default:
                    out.writeUTF("ERROR: Unknown request type");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleStoreChunk(String filename, String chunkId, DataInputStream in, DataOutputStream out) throws IOException {
        int chunkSize = in.readInt();
        byte[] chunkData = new byte[chunkSize];
        in.readFully(chunkData);
        
        try {
            storeChunk(filename, chunkId, chunkData);
            out.writeUTF("OK");
        } catch (IOException e) {
            out.writeUTF("ERROR: Failed to store chunk");
            e.printStackTrace();
        }
    }

    private void handleGetChunk(String filename, String chunkId, DataOutputStream out) throws IOException {
        try {
            byte[] chunkData = getChunk(filename, chunkId);
            if (chunkData != null) {
                out.writeInt(chunkData.length);
                out.write(chunkData);
            } else {
                out.writeUTF("ERROR: Chunk not found");
            }
        } catch (IOException e) {
            out.writeUTF("ERROR: Failed to read chunk");
            e.printStackTrace();
        }
    }

    private void sendHeartbeat() {
        try (Socket socket = new Socket(serverId, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeUTF("HEARTBEAT:" + serverId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getAddress() {
        return address;
    }

    public String getId() {
        return serverId;
    }

    public static class ChunkInfo {
        private final String filename;
        private final String chunkId;
        private final long size;

        public ChunkInfo(String filename, String chunkId, long size) {
            this.filename = filename;
            this.chunkId = chunkId;
            this.size = size;
        }

        public String getFilename() {
            return filename;
        }

        public String getChunkId() {
            return chunkId;
        }

        public long getSize() {
            return size;
        }
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java ChunkServer <id> <address> <port>");
            return;
        }
        
        String id = args[0];
        String address = args[1];
        int port = Integer.parseInt(args[2]);
        
        ChunkServer server = new ChunkServer(id, address, port);
        server.start();
    }
} 