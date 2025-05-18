package com.consensus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class RaftConsensus {
    private final String id;
    private final int port;
    private final List<String> peers;
    private final Map<String, Integer> nextIndex;
    private final Map<String, Integer> matchIndex;
    private final Map<String, Integer> lastLogTerm;
    private final AtomicInteger currentTerm;
    private final AtomicLong lastHeartbeat;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ScheduledExecutorService electionExecutor;
    private volatile String votedFor;
    private volatile String currentLeader;
    private volatile boolean isLeader;
    private static final int HEARTBEAT_INTERVAL = 100; // Reduced from 150ms
    private static final int ELECTION_TIMEOUT_MIN = 200; // Reduced from 300ms
    private static final int ELECTION_TIMEOUT_MAX = 400; // Reduced from 600ms
    private static final int SOCKET_TIMEOUT = 500; // Reduced from 1000ms
    private static final int CONNECTION_TIMEOUT = 500; // Reduced from 1000ms
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 50; // Reduced from 100ms
    private static final int STARTUP_DELAY_MS = 100; // Reduced from 200ms
    private static final int LEADER_FAILURE_TIMEOUT = 300; // Reduced from 500ms
    private static final int SERVER_SOCKET_TIMEOUT = 50; // Reduced from 100ms
    private static final int ELECTION_CHECK_INTERVAL = 25; // Reduced from 50ms
    private static final int SOCKET_REUSE_DELAY = 500; // Reduced from 1000ms
    private static final int MIN_ELECTION_INTERVAL = 500; // Reduced from 1000ms
    private static final int SPLIT_VOTE_TIMEOUT = 200; // Timeout for split vote resolution
    private static final Random random = new Random();
    private final Object stateLock = new Object();
    private volatile boolean running = true;
    private static final Logger logger = Logger.getLogger(RaftConsensus.class.getName());
    private final Map<String, Socket> peerSockets = new ConcurrentHashMap<>();
    private final Map<String, Object> socketLocks = new ConcurrentHashMap<>();
    private final Object electionLock = new Object();
    private volatile boolean electionInProgress = false;
    private volatile long lastElectionTime = 0;
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(1);
    private final AtomicInteger electionAttempts = new AtomicInteger(0);

    public RaftConsensus(String id, int port, List<String> peers) {
        this.id = id;
        this.port = port;
        this.peers = peers;
        this.nextIndex = new ConcurrentHashMap<>();
        this.matchIndex = new ConcurrentHashMap<>();
        this.lastLogTerm = new ConcurrentHashMap<>();
        this.currentTerm = new AtomicInteger(0);
        this.lastHeartbeat = new AtomicLong(System.currentTimeMillis());
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1);
        this.electionExecutor = Executors.newScheduledThreadPool(1);
        this.votedFor = null;
        this.currentLeader = null;
        this.isLeader = false;
        
        // Initialize nextIndex and matchIndex for all peers
        for (String peer : peers) {
            nextIndex.put(peer, 1);
            matchIndex.put(peer, 0);
            lastLogTerm.put(peer, 0);
        }
    }

    public void start() {
        running = true;
        // Start server socket first and wait for it to be ready
        new Thread(() -> {
            try {
                startServer();
            } catch (IOException e) {
                logger.severe("Failed to start server socket: " + e.getMessage());
            }
        }).start();
        
        // Wait for server socket to be ready
        try {
            Thread.sleep(STARTUP_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Start election timer
        startElectionTimer();
        logger.info("Raft node " + id + " started on port " + port);
    }

    private void startServer() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            serverSocket.setSoTimeout(SERVER_SOCKET_TIMEOUT);
            logger.info("Raft node " + id + " listening on port " + port);
            
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(SOCKET_TIMEOUT);
                    new Thread(() -> handleClient(clientSocket)).start();
                } catch (IOException e) {
                    if (running) {
                        // Only log if it's not a timeout
                        if (!(e instanceof java.net.SocketTimeoutException)) {
                            logger.warning("Error accepting connection: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {
            
            String request = in.readUTF();
            String[] parts = request.split(":");
            
            switch (parts[0]) {
                case "REQUEST_VOTE":
                    handleRequestVote(parts, out);
                    break;
                case "APPEND_ENTRIES":
                    handleAppendEntries(parts, in, out);
                    break;
                default:
                    out.writeUTF("ERROR: Unknown request type");
            }
        } catch (IOException e) {
            logger.warning("Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.warning("Error closing client socket: " + e.getMessage());
            }
        }
    }

    private void handleRequestVote(String[] parts, DataOutputStream out) throws IOException {
        int term = Integer.parseInt(parts[1]);
        String candidateId = parts[2];
        int lastLogIndex = Integer.parseInt(parts[3]);
        int candidateLastLogTerm = Integer.parseInt(parts[4]);

        boolean voteGranted = false;
        
        synchronized (stateLock) {
            if (term > currentTerm.get()) {
                currentTerm.set(term);
                votedFor = null;
                isLeader = false;
                currentLeader = null;
                logger.info("Node " + id + " updated term to " + term);
            }

            if (term == currentTerm.get() && 
                (votedFor == null || votedFor.equals(candidateId)) &&
                lastLogIndex >= matchIndex.getOrDefault(id, 0) &&
                candidateLastLogTerm >= lastLogTerm.getOrDefault(id, 0)) {
                votedFor = candidateId;
                voteGranted = true;
                lastHeartbeat.set(System.currentTimeMillis());
                logger.info("Node " + id + " granted vote to " + candidateId + " for term " + term);
            }
        }

        out.writeUTF("VOTE_RESPONSE:" + currentTerm.get() + ":" + voteGranted);
    }

    private void handleAppendEntries(String[] parts, DataInputStream in, DataOutputStream out) throws IOException {
        int term = Integer.parseInt(parts[1]);
        String leaderId = parts[2];
        int prevLogIndex = Integer.parseInt(parts[3]);
        int prevLogTerm = Integer.parseInt(parts[4]);
        int leaderCommit = Integer.parseInt(parts[5]);

        boolean success = false;
        
        synchronized (stateLock) {
            if (term >= currentTerm.get()) {
                currentTerm.set(term);
                currentLeader = leaderId;
                isLeader = false;
                votedFor = null;  // Reset votedFor when receiving valid leader message
                lastHeartbeat.set(System.currentTimeMillis());
                
                if (prevLogIndex <= matchIndex.getOrDefault(id, 0) &&
                    prevLogTerm == lastLogTerm.getOrDefault(id, 0)) {
                    success = true;
                    matchIndex.put(id, Math.max(matchIndex.getOrDefault(id, 0), leaderCommit));
                }
            }
        }

        out.writeUTF("APPEND_RESPONSE:" + currentTerm.get() + ":" + success);
    }

    private void startElectionTimer() {
        electionExecutor.scheduleAtFixedRate(() -> {
            if (!running) return;
            
            long now = System.currentTimeMillis();
            long timeout = getRandomElectionTimeout();
            
            // Check for leader failure
            if (isLeader && now - lastHeartbeat.get() > LEADER_FAILURE_TIMEOUT) {
                synchronized (stateLock) {
                    isLeader = false;
                    currentLeader = null;
                    votedFor = null;
                    logger.info("Node " + id + " detected leader failure");
                }
            }
            
            // Start election if no leader and timeout
            if (!isLeader && now - lastHeartbeat.get() > timeout && !electionInProgress && 
                now - lastElectionTime > MIN_ELECTION_INTERVAL) {
                logger.info("Node " + id + " starting election for term " + (currentTerm.get() + 1));
                startElection();
            }
        }, 0, ELECTION_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void startElection() {
        synchronized (electionLock) {
            if (electionInProgress) return;
            electionInProgress = true;
            lastElectionTime = System.currentTimeMillis();
        }

        try {
            synchronized (stateLock) {
                if (!running) return;
                
                // Only start election if we haven't received a heartbeat recently
                if (System.currentTimeMillis() - lastHeartbeat.get() <= ELECTION_TIMEOUT_MIN) {
                    return;
                }
                
                int newTerm = currentTerm.incrementAndGet();
                votedFor = id;
                isLeader = false;
                currentLeader = null;
                logger.info("Node " + id + " became candidate for term " + newTerm);
            }
            
            AtomicInteger votes = new AtomicInteger(1); // Vote for self
            CountDownLatch latch = new CountDownLatch(peers.size());
            AtomicInteger responses = new AtomicInteger(0);
            AtomicInteger term = new AtomicInteger(currentTerm.get());
            
            // Request votes from all peers
            for (String peer : peers) {
                new Thread(() -> {
                    try {
                        if (requestVote(peer)) {
                            votes.incrementAndGet();
                            logger.info("Node " + id + " received vote from " + peer);
                        }
                    } catch (Exception e) {
                        logger.warning("Error requesting vote from " + peer + ": " + e.getMessage());
                    } finally {
                        latch.countDown();
                        responses.incrementAndGet();
                    }
                }).start();
            }

            try {
                // Wait for all responses or timeout
                boolean success = latch.await(ELECTION_TIMEOUT_MIN, TimeUnit.MILLISECONDS);
                
                synchronized (stateLock) {
                    // Check if we still have enough votes and are still in the same term
                    if (success && votes.get() > (peers.size() + 1) / 2 && 
                        term.get() == currentTerm.get() &&
                        !isLeader) {
                        becomeLeader();
                    } else if (success && votes.get() == (peers.size() + 1) / 2) {
                        // Split vote detected
                        handleSplitVote(term.get());
                    } else {
                        // Reset election state if we didn't become leader
                        if (!isLeader) {
                            votedFor = null;
                            currentLeader = null;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("Election interrupted: " + e.getMessage());
            }
        } finally {
            synchronized (electionLock) {
                electionInProgress = false;
            }
        }
    }

    private boolean requestVote(String peer) {
        String message = String.format("REQUEST_VOTE:%d:%s:%d:%d",
            currentTerm.get(), id,
            matchIndex.getOrDefault(id, 0),
            lastLogTerm.getOrDefault(id, 0));

        return sendMessage(peer, message, true);
    }

    private Socket getOrCreateSocket(String peer) {
        return peerSockets.computeIfAbsent(peer, p -> {
            try {
                String[] hostPort = p.split(":");
                if (hostPort.length != 2) {
                    logger.warning("Invalid peer format: " + p);
                    return null;
                }
                
                String host = hostPort[0];
                int peerPort = Integer.parseInt(hostPort[1]);
                
                if (peerPort <= 0 || peerPort > 65535) {
                    logger.warning("Invalid port number for peer " + p);
                    return null;
                }
                
                Socket socket = new Socket();
                socket.setSoTimeout(SOCKET_TIMEOUT);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.setReuseAddress(true);
                socket.setSoLinger(true, 0);
                socket.setReceiveBufferSize(8192);
                socket.setSendBufferSize(8192);
                
                int retries = 0;
                while (retries < MAX_RETRY_ATTEMPTS) {
                    try {
                        socket.connect(new InetSocketAddress(host, peerPort), CONNECTION_TIMEOUT);
                        if (socket.isConnected()) {
                            return socket;
                        }
                    } catch (IOException e) {
                        logger.warning("Connection attempt " + (retries + 1) + " failed for peer " + p + ": " + e.getMessage());
                        retries++;
                        if (retries < MAX_RETRY_ATTEMPTS) {
                            try {
                                retryExecutor.schedule(() -> {}, RETRY_DELAY_MS * (retries + 1), TimeUnit.MILLISECONDS).get();
                            } catch (InterruptedException | ExecutionException ie) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        }
                    }
                }
                return null;
            } catch (IOException | IllegalArgumentException e) {
                logger.warning("Failed to create socket for peer " + p + ": " + e.getMessage());
                return null;
            }
        });
    }

    private Socket getValidSocket(String peer) {
        Socket socket = peerSockets.get(peer);
        if (socket != null && !socket.isClosed() && socket.isConnected()) {
            try {
                socket.getOutputStream().write(0);
                return socket;
            } catch (IOException e) {
                closePeerSocket(peer);
            }
        }
        
        // Try to reconnect with retries
        for (int i = 0; i < MAX_RETRY_ATTEMPTS; i++) {
            closePeerSocket(peer);
            socket = getOrCreateSocket(peer);
            if (socket != null && socket.isConnected()) {
                return socket;
            }
            try {
                retryExecutor.schedule(() -> {}, RETRY_DELAY_MS * (i + 1), TimeUnit.MILLISECONDS).get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private void closePeerSocket(String peer) {
        Socket socket = peerSockets.remove(peer);
        if (socket != null) {
            try {
                if (!socket.isClosed()) {
                    socket.shutdownInput();
                    socket.shutdownOutput();
                }
                socket.close();
                // Wait before reusing the socket
                Thread.sleep(SOCKET_REUSE_DELAY);
            } catch (IOException | InterruptedException e) {
                logger.warning("Error closing socket for peer " + peer + ": " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private boolean sendMessage(String peer, String message, boolean expectResponse) {
        Object lock = socketLocks.computeIfAbsent(peer, k -> new Object());
        synchronized (lock) {
            Socket socket = getValidSocket(peer);
            if (socket == null) {
                return false;
            }

            try {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(message);
                out.flush();

                if (expectResponse) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    String response = in.readUTF();
                    return handleResponse(response);
                }
                return true;
            } catch (IOException e) {
                logger.warning("Error sending message to peer " + peer + ": " + e.getMessage());
                closePeerSocket(peer);
                return false;
            }
        }
    }

    private boolean handleResponse(String response) {
        String[] parts = response.split(":");
        if (parts.length < 2) {
            return false;
        }

        switch (parts[0]) {
            case "VOTE_RESPONSE":
                return handleVoteResponse(parts);
            case "APPEND_RESPONSE":
                return handleAppendResponse(parts);
            default:
                return false;
        }
    }

    private boolean handleVoteResponse(String[] parts) {
        try {
            int term = Integer.parseInt(parts[1]);
            boolean voteGranted = Boolean.parseBoolean(parts[2]);
            
            synchronized (stateLock) {
                if (term > currentTerm.get()) {
                    currentTerm.set(term);
                    votedFor = null;
                    isLeader = false;
                    currentLeader = null;
                    return false;
                }
            }
            return voteGranted;
        } catch (NumberFormatException e) {
            logger.warning("Invalid vote response format: " + Arrays.toString(parts));
            return false;
        }
    }

    private boolean handleAppendResponse(String[] parts) {
        try {
            int term = Integer.parseInt(parts[1]);
            boolean success = Boolean.parseBoolean(parts[2]);
            
            synchronized (stateLock) {
                if (term > currentTerm.get()) {
                    currentTerm.set(term);
                    isLeader = false;
                    currentLeader = null;
                    return false;
                }
            }
            return success;
        } catch (NumberFormatException e) {
            logger.warning("Invalid append response format: " + Arrays.toString(parts));
            return false;
        }
    }

    private void becomeLeader() {
        synchronized (stateLock) {
            if (!running || isLeader) return;  // Prevent multiple leaders
            
            isLeader = true;
            currentLeader = id;
            votedFor = id;  // Ensure we vote for ourselves as leader
            logger.info("Node " + id + " became leader for term " + currentTerm.get());
            
            // Initialize leader state
            for (String peer : peers) {
                nextIndex.put(peer, matchIndex.getOrDefault(id, 0) + 1);
                matchIndex.put(peer, 0);
            }
        }
        
        // Start sending heartbeats immediately
        sendHeartbeats();
        // Then schedule regular heartbeats
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeats() {
        if (!isLeader) return;
        
        for (String peer : peers) {
            new Thread(() -> {
                try {
                    String message = String.format("APPEND_ENTRIES:%d:%s:%d:%d:%d",
                        currentTerm.get(), id,
                        nextIndex.get(peer) - 1,
                        currentTerm.get(),
                        matchIndex.getOrDefault(id, 0));

                    if (sendMessage(peer, message, true)) {
                        synchronized (stateLock) {
                            if (isLeader) {  // Double check we're still leader
                                matchIndex.put(peer, nextIndex.get(peer));
                                nextIndex.put(peer, nextIndex.get(peer) + 1);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Error sending heartbeat to " + peer + ": " + e.getMessage());
                }
            }).start();
        }
    }

    private int getRandomElectionTimeout() {
        return ELECTION_TIMEOUT_MIN + random.nextInt(ELECTION_TIMEOUT_MAX - ELECTION_TIMEOUT_MIN);
    }

    public boolean isLeader() {
        return isLeader;
    }

    public String getCurrentLeader() {
        return currentLeader;
    }

    public int getCurrentTerm() {
        return currentTerm.get();
    }

    public void stop() {
        running = false;
        isLeader = false;
        currentLeader = null;
        
        // Close all peer sockets
        for (String peer : peers) {
            closePeerSocket(peer);
        }
        
        // Shutdown executors
        heartbeatExecutor.shutdown();
        electionExecutor.shutdown();
        retryExecutor.shutdown();
        
        try {
            if (!heartbeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
            if (!electionExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                electionExecutor.shutdownNow();
            }
            if (!retryExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            electionExecutor.shutdownNow();
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("Raft node " + id + " stopped");
    }

    private void handleSplitVote(int term) {
        // Increment election attempts
        int attempts = electionAttempts.incrementAndGet();
        
        // Use node ID as tiebreaker - higher ID wins in case of split vote
        boolean isHighestId = true;
        for (String peer : peers) {
            if (peer.compareTo(id) > 0) {
                isHighestId = false;
                break;
            }
        }

        if (isHighestId && term == currentTerm.get()) {
            logger.info("Node " + id + " won split vote based on ID");
            becomeLeader();
        } else {
            // Wait for a random time before next election attempt
            try {
                Thread.sleep(SPLIT_VOTE_TIMEOUT + random.nextInt(SPLIT_VOTE_TIMEOUT));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Reset election state
            synchronized (stateLock) {
                if (!isLeader) {
                    votedFor = null;
                    currentLeader = null;
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java RaftConsensus <id> <port> <peer1> [peer2] [peer3] ...");
            return;
        }
        
        String id = args[0];
        int port = Integer.parseInt(args[1]);
        List<String> peers = Arrays.asList(args).subList(2, args.length);
        
        RaftConsensus node = new RaftConsensus(id, port, peers);
        node.start();
    }
} 