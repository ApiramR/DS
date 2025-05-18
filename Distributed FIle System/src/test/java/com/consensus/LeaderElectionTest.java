package com.consensus;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LeaderElectionTest {
    private RaftConsensus node1;
    private RaftConsensus node2;
    private RaftConsensus node3;
    private static final AtomicInteger portCounter = new AtomicInteger(10000);
    private static final int ELECTION_TIMEOUT = 2000; // 2 seconds
    private static final int VERIFICATION_TIMEOUT = 5000; // 5 seconds

    @BeforeEach
    public void setUp() {
        // Use a larger gap between port ranges to avoid conflicts
        int basePort = portCounter.getAndAdd(100);
        
        // Create three Raft nodes with all peers in the cluster
        node1 = new RaftConsensus("node1", basePort, Arrays.asList(
            "localhost:" + (basePort + 1),
            "localhost:" + (basePort + 2)
        ));
        node2 = new RaftConsensus("node2", basePort + 1, Arrays.asList(
            "localhost:" + basePort,
            "localhost:" + (basePort + 2)
        ));
        node3 = new RaftConsensus("node3", basePort + 2, Arrays.asList(
            "localhost:" + basePort,
            "localhost:" + (basePort + 1)
        ));
    }

    @AfterEach
    public void tearDown() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        if (node3 != null) node3.stop();
    }

    @Test
    public void testInitialLeaderElection() throws InterruptedException {
        // Start all nodes and wait for them to be ready
        node1.start();
        Thread.sleep(100); // Give node1 time to start listening
        node2.start();
        Thread.sleep(100); // Give node2 time to start listening
        node3.start();
        Thread.sleep(100); // Give node3 time to start listening

        // Wait for election with timeout
        long startTime = System.currentTimeMillis();
        boolean hasLeader = false;
        while (System.currentTimeMillis() - startTime < VERIFICATION_TIMEOUT) {
            hasLeader = node1.isLeader() || node2.isLeader() || node3.isLeader();
            if (hasLeader) break;
            Thread.sleep(100);
        }
        assertTrue(hasLeader, "One node should be elected as leader");

        // Verify that only one node is leader
        int leaderCount = (node1.isLeader() ? 1 : 0) + 
                         (node2.isLeader() ? 1 : 0) + 
                         (node3.isLeader() ? 1 : 0);
        assertTrue(leaderCount == 1, "Only one node should be leader");
    }

    @Test
    public void testLeaderFailureAndReelection() throws InterruptedException {
        // Start all nodes and wait for them to be ready
        node1.start();
        Thread.sleep(100); // Give node1 time to start listening
        node2.start();
        Thread.sleep(100); // Give node2 time to start listening
        node3.start();
        Thread.sleep(100); // Give node3 time to start listening

        // Wait for initial election
        long startTime = System.currentTimeMillis();
        RaftConsensus currentLeader = null;
        while (System.currentTimeMillis() - startTime < VERIFICATION_TIMEOUT) {
            if (node1.isLeader()) currentLeader = node1;
            else if (node2.isLeader()) currentLeader = node2;
            else if (node3.isLeader()) currentLeader = node3;
            if (currentLeader != null) break;
            Thread.sleep(100);
        }
        assertTrue(currentLeader != null, "Should have a leader");

        // Simulate leader failure by stopping it
        currentLeader.stop();
        Thread.sleep(100); // Give time for the stop to take effect

        // Wait for new election
        startTime = System.currentTimeMillis();
        boolean hasNewLeader = false;
        while (System.currentTimeMillis() - startTime < VERIFICATION_TIMEOUT) {
            hasNewLeader = (node1.isLeader() && node1 != currentLeader) ||
                          (node2.isLeader() && node2 != currentLeader) ||
                          (node3.isLeader() && node3 != currentLeader);
            if (hasNewLeader) break;
            Thread.sleep(100);
        }
        assertTrue(hasNewLeader, "A new leader should be elected after failure");

        // Verify that only one node is leader
        int leaderCount = (node1.isLeader() ? 1 : 0) + 
                         (node2.isLeader() ? 1 : 0) + 
                         (node3.isLeader() ? 1 : 0);
        assertTrue(leaderCount == 1, "Only one node should be leader");
    }

    @Test
    public void testTermIncrement() throws InterruptedException {
        // Start all nodes and wait for them to be ready
        node1.start();
        Thread.sleep(100); // Give node1 time to start listening
        node2.start();
        Thread.sleep(100); // Give node2 time to start listening
        node3.start();
        Thread.sleep(100); // Give node3 time to start listening

        // Wait for initial election
        long startTime = System.currentTimeMillis();
        RaftConsensus currentLeader = null;
        while (System.currentTimeMillis() - startTime < VERIFICATION_TIMEOUT) {
            if (node1.isLeader()) currentLeader = node1;
            else if (node2.isLeader()) currentLeader = node2;
            else if (node3.isLeader()) currentLeader = node3;
            if (currentLeader != null) break;
            Thread.sleep(100);
        }
        assertTrue(currentLeader != null, "Should have a leader");

        // Get initial term
        int initialTerm = currentLeader.getCurrentTerm();

        // Simulate leader failure
        currentLeader.stop();
        Thread.sleep(100); // Give time for the stop to take effect

        // Wait for new election and term increment
        startTime = System.currentTimeMillis();
        boolean termIncreased = false;
        while (System.currentTimeMillis() - startTime < VERIFICATION_TIMEOUT) {
            termIncreased = node1.getCurrentTerm() > initialTerm || 
                          node2.getCurrentTerm() > initialTerm || 
                          node3.getCurrentTerm() > initialTerm;
            if (termIncreased) break;
            Thread.sleep(100);
        }
        assertTrue(termIncreased, "Term should increase after leader failure");
    }
} 