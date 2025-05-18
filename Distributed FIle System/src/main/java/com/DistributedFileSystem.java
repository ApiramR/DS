package com;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.consensus.RaftConsensus;
import com.server.ChunkServer;
import com.server.MetadataServer;

public class DistributedFileSystem {
    private final MetadataServer metadataServer;
    private final Map<String, ChunkServer> chunkServers;
    private final RaftConsensus consensus;

    public DistributedFileSystem(String metadataFilePath, List<String> chunkServerIds, int metadataPort, long heartbeatInterval, long heartbeatTimeout) {
        this.metadataServer = new MetadataServer(metadataPort);
        this.metadataServer.start();

        this.chunkServers = new HashMap<>();
        int basePort = 6000;
        for (String serverId : chunkServerIds) {
            int port = basePort + Integer.parseInt(serverId.replace("server", ""));
            ChunkServer chunkServer = new ChunkServer(serverId, "localhost", port);
            chunkServers.put(serverId, chunkServer);
        }

        // Initialize RaftConsensus with proper parameters
        String serverId = "server1";
        this.consensus = new RaftConsensus(serverId, metadataPort, new ArrayList<>(chunkServerIds));
    }

    // Update health check to use metadataServer
    private boolean isServerHealthy(String serverId) {
        ChunkServer server = chunkServers.get(serverId);
        return server != null && server.isActive();
    }

    // Upload a file
    public void uploadFile(String filepath) {
        if (!consensus.isLeader()) {
            System.out.println("Not the leader, cannot upload file");
            return;
        }
        File file = new File(filepath);
        if (!file.exists()) {
            System.out.println("File does not exist: " + filepath);
            return;
        }
    
        try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            int chunkNumber = 0;
        
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                String chunkId = "chunk" + chunkNumber;
                byte[] chunkData = Arrays.copyOf(buffer, bytesRead);
                int successfulReplications = 0;
    
                for (Map.Entry<String, ChunkServer> entry : chunkServers.entrySet()) {
                    String serverId = entry.getKey();
                    ChunkServer chunkServer = entry.getValue();
    
                    if (isServerHealthy(serverId)) {
                        try {
                            chunkServer.storeChunk(file.getName(), chunkId, chunkData);
                            successfulReplications++;
                        } catch (IOException e) {
                            System.err.println("Error storing chunk to server " + serverId);
                        }
                    } else {
                        System.out.println("Skipping failed server: " + serverId);
                    }
    
                    if (successfulReplications >= 2) {
                        break;
                    }
                }
    
                if (successfulReplications < 2) {
                    System.out.println("Error: Less than 2 replicas for chunk " + chunkId);
                    return;
                }
    
                chunkNumber++;
            }
    
            System.out.println("File upload completed!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    
    

    // Download a file
    public void downloadFile(String filename, String savePath) {
        if (!consensus.isLeader()) {
            System.out.println("Not the leader, cannot download file");
            return;
        }
        try {
            try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(savePath))) {
                byte[] buffer = new byte[4096];
                int chunksDownloaded = 0;
                int chunkNumber = 0;
                boolean moreChunks = true;
    
                while (moreChunks) {
                    String chunkId = "chunk" + chunkNumber;
                    boolean chunkFound = false;
    
                    for (ChunkServer server : chunkServers.values()) {
                        if (isServerHealthy(server.getId())) {
                            try {
                                byte[] chunkData = server.getChunk(filename, chunkId);
                                if (chunkData != null) {
                                    fileOut.write(chunkData);
                                    chunksDownloaded++;
                                    chunkFound = true;
                                    break;
                                }
                            } catch (IOException e) {
                                System.out.println("Error downloading chunk from server " + server.getId());
                            }
                        }
                    }
    
                    if (!chunkFound) {
                        moreChunks = false;
                    }
                    chunkNumber++;
                }
    
                if (chunksDownloaded == 0) {
                    System.out.println("Warning: No chunks were downloaded successfully.");
                }
    
                System.out.println("File download completed!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void deleteFile(String filename) {
        if (!consensus.isLeader()) {
            System.out.println("Not the leader, cannot delete file");
            return;
        }
        int chunkNumber = 0;
        boolean moreChunks = true;
        boolean allChunksDeleted = true;

        while (moreChunks) {
            String chunkId = "chunk" + chunkNumber;
            boolean chunkFound = false;

            for (ChunkServer server : chunkServers.values()) {
                if (isServerHealthy(server.getId())) {
                    try {
                        byte[] chunkData = server.getChunk(filename, chunkId);
                        if (chunkData != null) {
                            chunkFound = true;
                            // Delete the chunk from the server
                            server.storeChunk(filename, chunkId, new byte[0]);
                        }
                    } catch (IOException e) {
                        System.out.println("Error deleting chunk from server " + server.getId());
                        allChunksDeleted = false;
                    }
                }
            }

            if (!chunkFound) {
                moreChunks = false;
            }
            chunkNumber++;
        }

        // Delete both original and downloaded files
        File originalFile = new File(filename);
        File downloadedFile = new File("downloaded_" + filename);
        originalFile.delete();
        downloadedFile.delete();

        if (allChunksDeleted) {
            System.out.println("File deletion completed!");
        } else {
            System.out.println("Warning: Some chunks could not be deleted.");
        }
    }

    public void startChunkServers() {
        chunkServers.values().forEach(chunkServer -> {
            System.out.println("Starting chunk server " + chunkServer.getPort());
            new Thread(() -> chunkServer.start()).start();  // Run each in a separate thread
        });
    }
    public ChunkServer getChunkServer(String serverId) {
        return chunkServers.get(serverId);
    }

    public void start() {
        startChunkServers();
    }

    public static void main(String[] args) {
        List<String> chunkServerIds = Arrays.asList("server1", "server2", "server3");
        DistributedFileSystem dfs = new DistributedFileSystem("metadata.json", chunkServerIds, 7000, 5000, 10000);

        dfs.start();
        dfs.uploadFile("myfile.txt");
        dfs.downloadFile("myfile.txt", "downloaded_myfile.txt");
    }
}
