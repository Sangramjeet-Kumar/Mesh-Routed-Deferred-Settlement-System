package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * These tests describe the current demo contract, including known defects.
 * Changes to a known-defect assertion require an explicitly approved behavior change.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:phase1-characterization")
@AutoConfigureMockMvc
class PaymentFlowCharacterizationTest {
    @Autowired private DemoService demo;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private SettlementService settlement;
    @Autowired private IdempotencyService idempotency;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void resetState() {
        transactions.deleteAll();
        accounts.deleteAll();
        demo.seedAccounts();
        idempotency.clear();
        mesh.resetMesh();
    }

    @Test
    void insufficientFundsReturnsSettledButRecordsRejectedWithoutMovingMoney() throws Exception {
        MeshPacket packet = packet(instruction("6000.00"));
        mvc.perform(post("/api/bridge/ingest").contentType("application/json")
                        .content(json.writeValueAsString(packet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SETTLED"))
                .andExpect(jsonPath("$.packetHash").value(crypto.hashCiphertext(packet.getCiphertext())))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.transactionId").isNumber());
        assertEquals(Transaction.Status.REJECTED, transactions.findAll().get(0).getStatus());
        assertBalances("5000.00", "1000.00");
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "again", 0).outcome());
    }

    @Test
    void changingOuterMetadataDoesNotBypassCiphertextDeduplication() throws Exception {
        MeshPacket packet = packet(instruction("10.00"));
        assertEquals("SETTLED", bridge.ingest(packet, "first", 1).outcome());
        packet.setPacketId("rewritten");
        packet.setCreatedAt(0L);
        packet.setTtl(-10);
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "second", 9).outcome());
        assertBalances("4990.00", "1010.00");
        assertEquals(1, transactions.count());
    }

    @Test
    void replayAfterCacheClearFailsDatabaseUniquenessWithoutAnotherBalanceChange() throws Exception {
        MeshPacket packet = packet(instruction("10.00"));
        assertEquals("SETTLED", bridge.ingest(packet, "first", 1).outcome());
        idempotency.clear();
        var replay = bridge.ingest(packet, "second", 2);
        assertEquals("INVALID", replay.outcome());
        assertEquals("?", replay.packetHash());
        assertTrue(replay.reason().startsWith("internal_error: "));
        assertBalances("4990.00", "1010.00");
        assertEquals(1, transactions.count());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "third", 3).outcome());
    }

    @Test
    void ledgerInsertFailureRollsBackBalanceChangesAndRetainsClaim() throws Exception {
        MeshPacket packet = packet(instruction("10.00"));
        var result = bridge.ingest(packet, null, 0); // bridge_node_id is NOT NULL
        assertEquals("INVALID", result.outcome());
        assertTrue(result.reason().startsWith("internal_error: "));
        assertBalances("5000.00", "1000.00");
        assertEquals(0, transactions.count());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "valid-bridge", 0).outcome());
    }

    @ParameterizedTest
    @CsvSource({"0.00", "-1.00"})
    void nonPositiveAmountsReturnExistingErrorAndRetainClaim(String amount) throws Exception {
        MeshPacket packet = packet(instruction(amount));
        assertEquals("internal_error: Amount must be positive", bridge.ingest(packet, "bridge", 0).reason());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge", 0).outcome());
        assertBalances("5000.00", "1000.00");
        assertEquals(0, transactions.count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unknownAccountsProduceExistingErrors(boolean unknownSender) throws Exception {
        PaymentInstruction instruction = instruction("10.00");
        if (unknownSender) instruction.setSenderVpa("missing@demo");
        else instruction.setReceiverVpa("missing@demo");
        assertEquals("internal_error: Unknown " + (unknownSender ? "sender" : "receiver") + " VPA: missing@demo",
                bridge.ingest(packet(instruction), "bridge", 0).reason());
        assertBalances("5000.00", "1000.00");
        assertEquals(0, transactions.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"signedAt", "amount"})
    void nullInstructionFieldsProduceInternalErrorsAndRetainClaims(String field) throws Exception {
        PaymentInstruction instruction = instruction("10.00");
        if (field.equals("signedAt")) instruction.setSignedAt(null);
        else instruction.setAmount(null);
        MeshPacket packet = packet(instruction);
        var result = bridge.ingest(packet, "bridge", 0);
        assertEquals("INVALID", result.outcome());
        assertEquals("?", result.packetHash());
        assertTrue(result.reason().startsWith("internal_error: "));
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge", 0).outcome());
        assertBalances("5000.00", "1000.00");
        assertEquals(0, transactions.count());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not base64!", "YQ=="})
    void malformedCiphertextsNeverTouchLedger(String ciphertext) throws Exception {
        MeshPacket packet = new MeshPacket();
        packet.setCiphertext(ciphertext);
        var result = bridge.ingest(packet, "bridge", 0);
        assertEquals("INVALID", result.outcome());
        assertEquals(ciphertext == null ? 0 : 1, idempotency.size());
        if (ciphertext != null) {
            assertEquals("decryption_failed", result.reason());
            assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge", 0).outcome());
        }
        assertBalances("5000.00", "1000.00");
        assertEquals(0, transactions.count());
        idempotency.clear();
        mvc.perform(post("/api/bridge/ingest").contentType("application/json")
                        .content(json.writeValueAsString(packet)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("INVALID"));
    }

    @Test
    void equivalentBase64EncodingsCurrentlyAllowTheSameEncryptedPaymentToSettleTwice() throws Exception {
        PaymentInstruction instruction = instruction("10.00");
        MeshPacket packet = packet(instruction);
        // Vary JSON length to ensure the standard encoder emits padding.
        for (int i = 0; !packet.getCiphertext().endsWith("=") && i < 2; i++) {
            instruction.setNonce(instruction.getNonce() + "x");
            packet = packet(instruction);
        }
        String padded = packet.getCiphertext();
        assertTrue(padded.endsWith("="));
        String unpadded = padded.replace("=", "");
        assertArrayEquals(Base64.getDecoder().decode(padded), Base64.getDecoder().decode(unpadded));
        assertNotEquals(crypto.hashCiphertext(padded), crypto.hashCiphertext(unpadded));
        assertEquals("SETTLED", bridge.ingest(packet, "first", 0).outcome());
        packet.setCiphertext(unpadded);
        assertEquals("SETTLED", bridge.ingest(packet, "second", 0).outcome());
        assertBalances("4980.00", "1020.00");
        assertEquals(2, transactions.count());
    }

    @Test
    void reencryptedInstructionWithSameNonceAndUnverifiedPinStillSettlesTwice() throws Exception {
        PaymentInstruction instruction = instruction("10.00");
        instruction.setPinHash(null);
        MeshPacket first = packet(instruction);
        MeshPacket second = packet(instruction);
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
        assertEquals("SETTLED", bridge.ingest(first, "bridge", 0).outcome());
        assertEquals("SETTLED", bridge.ingest(second, "bridge", 0).outcome());
        assertBalances("4980.00", "1020.00");
        assertEquals(2, transactions.count());
    }

    @Test
    void selfTransferRecordsSettlementWithoutChangingBalance() throws Exception {
        PaymentInstruction instruction = instruction("10.00");
        instruction.setReceiverVpa("alice@demo");
        assertEquals("SETTLED", bridge.ingest(packet(instruction), "bridge", 0).outcome());
        assertBalances("5000.00", "1000.00");
        assertEquals(Transaction.Status.SETTLED, transactions.findAll().get(0).getStatus());
    }

    @Test
    void excessDecimalPlacesAreAcceptedAndRoundedIndependentlyByH2() throws Exception {
        assertEquals("SETTLED", bridge.ingest(packet(instruction("0.005")), "bridge", 0).outcome());
        assertBalances("5000.00", "1000.01");
        assertEquals(new BigDecimal("0.01"), transactions.findAll().get(0).getAmount());
    }

    @Test
    void concurrentDistinctSettlementsOnSameAccountsCommitOneAndRollBackTheConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch loaded = new CountDownLatch(2);
        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String hash = Integer.toString(i).repeat(64);
                results.add(pool.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            // Both transactions load the same versions before either can update.
                            accounts.findById("alice@demo").orElseThrow();
                            accounts.findById("bob@demo").orElseThrow();
                            loaded.countDown();
                            await(loaded);
                            settlement.settle(instruction("10.00"), hash, "bridge", 0);
                        });
                        return true;
                    } catch (OptimisticLockingFailureException expected) {
                        return false;
                    }
                }));
            }
            int committed = 0;
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) committed++;
            assertEquals(1, committed);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertBalances("4990.00", "1010.00");
        assertEquals(1, transactions.count());
    }

    @Test
    void httpDemoFlowKeepsRepeatedUploadsAndResetPreservesBalancesAndLedger() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\"></div>")));
        mvc.perform(post("/api/demo/send").contentType("application/json")
                        .content("{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":10,\"pin\":\"1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ttl").value(5))
                .andExpect(jsonPath("$.injectedAt").value("phone-alice"));
        mvc.perform(post("/api/mesh/gossip")).andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers").value(4));
        mvc.perform(post("/api/mesh/flush")).andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadsAttempted").value(1))
                .andExpect(jsonPath("$.results[0].outcome").value("SETTLED"));
        mvc.perform(post("/api/mesh/flush")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("DUPLICATE_DROPPED"))
                .andExpect(jsonPath("$.results[0].transactionId").value(-1));
        mvc.perform(post("/api/mesh/reset")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("mesh and idempotency cache cleared"));
        assertEquals(0, idempotency.size());
        assertTrue(mesh.getDevices().stream().allMatch(device -> device.packetCount() == 0));
        assertBalances("4990.00", "1010.00");
        mvc.perform(get("/api/transactions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hopCount").value(1));
        mvc.perform(get("/api/accounts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void bridgeHttpEndpointCurrentlyAcceptsInvalidOuterFieldsAndDefaultsHeaders() throws Exception {
        MeshPacket packet = packet(instruction("10.00"));
        packet.setPacketId(null);
        packet.setCreatedAt(null);
        packet.setTtl(-1);
        mvc.perform(post("/api/bridge/ingest").contentType("application/json")
                        .content(json.writeValueAsString(packet)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SETTLED"));
        Transaction tx = transactions.findAll().get(0);
        assertEquals("unknown", tx.getBridgeNodeId());
        assertEquals(0, tx.getHopCount());
    }

    @Test
    void customStartingTtlStillUsesFiveAsHopCountBaseline() throws Exception {
        mesh.inject("phone-alice", demo.createPacket("alice@demo", "bob@demo", BigDecimal.TEN, "1234", 8));
        mesh.gossipOnce();
        mvc.perform(post("/api/mesh/flush")).andExpect(status().isOk());
        assertEquals(-2, transactions.findAll().get(0).getHopCount());
    }

    private PaymentInstruction instruction(String amount) {
        return new PaymentInstruction("alice@demo", "bob@demo", new BigDecimal(amount),
                "not-verified", UUID.randomUUID().toString(), Instant.now().toEpochMilli());
    }

    private MeshPacket packet(PaymentInstruction instruction) throws Exception {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setTtl(5);
        packet.setCiphertext(crypto.encrypt(instruction, serverKey.getPublicKey()));
        return packet;
    }

    private void assertBalances(String alice, String bob) {
        assertEquals(new BigDecimal(alice), accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal(bob), accounts.findById("bob@demo").orElseThrow().getBalance());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent transactions did not overlap");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while synchronizing transactions", e);
        }
    }
}
