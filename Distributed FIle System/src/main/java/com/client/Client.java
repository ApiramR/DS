package com.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Client {
    private final String metadataServerHost;
    private final int metadataServerPort;
    private static final int CHUNK_SIZE = 4096; // 4KB
    private static final int MAX_CHUNK_SIZE = 1024 * 1024; // 1MB max chunk size

    public Client(String metadataServerHost, int metadataServerPort) {
        this.metadataServerHost = metadataServerHost;
        this.metadataServerPort = metadataServerPort;
    }

    public void uploadFile(String filepath) {
        File file = new File(filepath);
        if (!file.exists()) {
            System.out.println("File does not exist: " + filepath);
            return;
        }

        try (Socket socket = new Socket(metadataServerHost, metadataServerPort);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("Connected to Metadata Server!");

            // Calculate file hash and chunk information
            FileMetadata metadata = calculateFileMetadata(file);
            
            // Send upload request header with metadata
            out.writeUTF("UPLOAD:" + file.getName() + ":" + file.length() + ":" + 
                        CHUNK_SIZE + ":" + metadata.getFileHash() + ":" + 
                        metadata.getTotalChunks());

            // Wait for server ack with chunk server list
            String response = in.readLine();
            if (!response.startsWith("OK:")) {
                System.out.println("Error: " + response);
                return;
            }

            String[] chunkServers = response.substring(3).split(",");
            System.out.println("Received chunk server list: " + Arrays.toString(chunkServers));

            // Read file in chunks and distribute to chunk servers
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            int chunkNumber = 0;
            ExecutorService executor = Executors.newFixedThreadPool(chunkServers.length);
            List<Future<?>> uploadFutures = new ArrayList<>();

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                final int currentChunk = chunkNumber;
                final byte[] chunkData = Arrays.copyOf(buffer, bytesRead);
                final String chunkHash = calculateChunkHash(chunkData);
                
                // Send chunk to all chunk servers in parallel
                for (String chunkServer : chunkServers) {
                    String[] serverInfo = chunkServer.split(":");
                    uploadFutures.add(executor.submit(() -> 
                        sendChunkToServer(serverInfo[0], Integer.parseInt(serverInfo[1]), 
                                        file.getName(), String.valueOf(currentChunk), 
                                        chunkData, chunkHash)));
                }
                chunkNumber++;
            }

            // Wait for all uploads to complete
            for (Future<?> future : uploadFutures) {
                try {
                    future.get();
                } catch (Exception e) {
                    System.out.println("Error uploading chunk: " + e.getMessage());
                    return;
                }
            }

            executor.shutdown();
            System.out.println("File upload completed!");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private FileMetadata calculateFileMetadata(File file) throws IOException {
        try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            long totalChunks = 0;

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
                totalChunks++;
            }

            return new FileMetadata(
                file.getName(),
                file.length(),
                totalChunks,
                bytesToHex(digest.digest())
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    private String calculateChunkHash(byte[] chunkData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(chunkData);
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private void sendChunkToServer(String host, int port, String filename, 
                                 String chunkId, byte[] chunkData, String chunkHash) {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            out.writeUTF("STORE_CHUNK:" + filename + ":" + chunkId + ":" + chunkHash);
            out.writeInt(chunkData.length);
            out.write(chunkData);

            String response = in.readUTF();
            if (!response.equals("OK")) {
                throw new IOException("Failed to store chunk: " + response);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error sending chunk to server: " + e.getMessage(), e);
        }
    }

    public void downloadFile(String filename, String savePath) {
        try (Socket socket = new Socket(metadataServerHost, metadataServerPort);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            System.out.println("Connected to Metadata Server!");

            // Send download request
            out.writeUTF("DOWNLOAD:" + filename);

            // Get chunk server list
            String response = in.readUTF();
            if (!response.startsWith("OK:")) {
                System.out.println("Error: " + response);
                return;
            }

            String[] chunkServers = response.substring(3).split(",");
            System.out.println("Received chunk server list: " + Arrays.toString(chunkServers));

            // Download chunks from the first available server
            try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(savePath))) {
                int chunkNumber = 0;
                boolean moreChunks = true;
                Map<Integer, byte[]> downloadedChunks = new ConcurrentHashMap<>();
                ExecutorService executor = Executors.newFixedThreadPool(chunkServers.length);

                while (moreChunks) {
                    final int currentChunk = chunkNumber;
                    List<Future<?>> downloadFutures = new ArrayList<>();
                    for (String chunkServer : chunkServers) {
                        String[] serverInfo = chunkServer.split(":");
                        downloadFutures.add(executor.submit(() -> {
                            try {
                                byte[] chunkData = downloadChunkFromServer(
                                    serverInfo[0], 
                                    Integer.parseInt(serverInfo[1]), 
                                    filename, 
                                    String.valueOf(currentChunk)
                                );
                                if (chunkData != null) {
                                    downloadedChunks.put(currentChunk, chunkData);
                                }
                            } catch (IOException e) {
                                System.out.println("Failed to download chunk from " + chunkServer + ": " + e.getMessage());
                            }
                        }));
                    }

                    // Wait for all download attempts
                    for (Future<?> future : downloadFutures) {
                        try {
                            future.get();
                        } catch (Exception e) {
                            System.out.println("Error downloading chunk: " + e.getMessage());
                        }
                    }

                    if (downloadedChunks.containsKey(chunkNumber)) {
                        fileOut.write(downloadedChunks.get(chunkNumber));
                        chunkNumber++;
                    } else {
                        moreChunks = false;
                    }
                }

                executor.shutdown();
            }

            System.out.println("File download completed!");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private byte[] downloadChunkFromServer(String host, int port, String filename, String chunkId) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            out.writeUTF("GET_CHUNK:" + filename + ":" + chunkId);
            
            int chunkSize = in.readInt();
            if (chunkSize <= 0) {
                return null;
            }

            byte[] chunkData = new byte[chunkSize];
            in.readFully(chunkData);
            return chunkData;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    public static void main(String[] args) {
        Client client = new Client("localhost", 5000);

        // Upload a file
        client.uploadFile("myfile.txt");

        // Download the file
        client.downloadFile("myfile.txt", "downloaded_myfile.txt");
    }
}

class FileMetadata {
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
