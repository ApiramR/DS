package com.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Server {
    private final File metadataFile;
    private final ObjectMapper objectMapper;
    private final Map<String, List<String>> chunkLocations;
    private final ExecutorService executorService;

    public Server(String metadataFilePath) {
        this.metadataFile = new File(metadataFilePath);
        this.objectMapper = new ObjectMapper();
        this.chunkLocations = new HashMap<>();
        this.executorService = Executors.newCachedThreadPool();
        loadMetadata();
    }

    private void loadMetadata() {
        if (metadataFile.exists()) {
            try {
                chunkLocations.putAll(objectMapper.readValue(metadataFile, Map.class));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void saveMetadata() {
        executorService.submit(() -> {
            try {
                objectMapper.writeValue(metadataFile, chunkLocations);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void addChunkLocation(String chunkName, String serverId) {
        chunkLocations.computeIfAbsent(chunkName, k -> new ArrayList<>()).add(serverId);
        saveMetadata();
    }

    public List<String> getChunkLocations(String chunkName) {
        return chunkLocations.getOrDefault(chunkName, Collections.emptyList());
    }
}
