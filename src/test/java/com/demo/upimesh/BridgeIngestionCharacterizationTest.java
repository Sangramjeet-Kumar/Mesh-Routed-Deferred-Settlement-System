package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Characterizes existing outcomes, including failures that retain their claims. */
@ExtendWith(OutputCaptureExtension.class)
class BridgeIngestionCharacterizationTest {
    private final TestCrypto crypto = new TestCrypto();
    private final TestSettlement settlement = new TestSettlement();
    private final IdempotencyService idempotency = new IdempotencyService();
    private final BridgeIngestionService bridge = new BridgeIngestionService();
    private final MeshPacket packet = new MeshPacket();
    private final PaymentInstruction instruction = new PaymentInstruction(
            "alice@demo", "bob@demo", BigDecimal.TEN, "unused", "nonce", 0L);
    private static final String HASH = "a".repeat(64);

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(bridge, "crypto", crypto);
        ReflectionTestUtils.setField(bridge, "settlement", settlement);
        ReflectionTestUtils.setField(bridge, "idempotency", idempotency);
        ReflectionTestUtils.setField(bridge, "maxAgeSeconds", 86400L);
        packet.setCiphertext("ciphertext");
        instruction.setSignedAt(Instant.now().toEpochMilli());
        crypto.instruction = instruction;
    }

    @ParameterizedTest
    @CsvSource({"90000, stale_packet", "-600, future_dated"})
    void freshnessRejectionsRetainTheirClaims(long ageSeconds, String reason) {
        instruction.setSignedAt(Instant.now().minusSeconds(ageSeconds).toEpochMilli());
        assertEquals(new BridgeIngestionService.IngestResult("INVALID", HASH, reason, null),
                bridge.ingest(packet, "bridge-1", 2));
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge-2", 3).outcome());
        assertEquals(0, settlement.calls);
    }

    @ParameterizedTest
    @CsvSource({"0", "86390", "-290"})
    void freshAndSlightlyFutureDatedInstructionsProceed(long ageSeconds) {
        instruction.setSignedAt(Instant.now().minusSeconds(ageSeconds).toEpochMilli());
        Transaction tx = new Transaction();
        tx.setId(42L);
        tx.setStatus(Transaction.Status.SETTLED);
        settlement.transaction = tx;
        assertEquals(new BridgeIngestionService.IngestResult("SETTLED", HASH, null, 42L),
                bridge.ingest(packet, "bridge-1", 2));
    }

    @Test
    void decryptionFailureRetainsClaimAndDoesNotSettle() throws Exception {
        crypto.decryptFailure = new IllegalArgumentException("bad envelope");
        assertEquals("decryption_failed", bridge.ingest(packet, "bridge-1", 0).reason());
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge-2", 0).outcome());
        assertEquals(1, crypto.decryptCalls);
        assertEquals(0, settlement.calls);
    }

    @Test
    void optimisticLockFailureRetainsClaimAndLegacyInternalErrorResponse(CapturedOutput output) {
        var failure = new ObjectOptimisticLockingFailureException("Account", "alice@demo");
        settlement.failure = failure;
        assertEquals(new BridgeIngestionService.IngestResult(
                        "INVALID", "?", "internal_error: " + failure.getMessage(), null),
                bridge.ingest(packet, "bridge-1", 2));
        assertEquals("DUPLICATE_DROPPED", bridge.ingest(packet, "bridge-2", 2).outcome());
        assertEquals(1, settlement.calls);
        assertTrue(output.getOut().contains("stage=settlement"));
        assertTrue(output.getOut().contains("packetHash=" + HASH));
        assertTrue(output.getOut().contains("bridge=bridge-1"));
        assertTrue(output.getOut().contains("ObjectOptimisticLockingFailureException"));
    }

    @Test
    void hashFailureDoesNotClaimOrDecrypt() throws Exception {
        crypto.hashFailure = new IllegalArgumentException("bad input");
        assertEquals(new BridgeIngestionService.IngestResult(
                        "INVALID", "?", "internal_error: bad input", null),
                bridge.ingest(packet, "bridge-1", 0));
        assertEquals(0, idempotency.size());
        assertEquals(0, crypto.decryptCalls);
        assertEquals(0, settlement.calls);
    }

    // Plain test doubles avoid bytecode instrumentation and work on the local JDK 25.
    private static class TestCrypto extends HybridCryptoService {
        private PaymentInstruction instruction;
        private RuntimeException hashFailure;
        private RuntimeException decryptFailure;
        private int decryptCalls;

        @Override
        public String hashCiphertext(String ciphertext) {
            if (hashFailure != null) throw hashFailure;
            return HASH;
        }

        @Override
        public PaymentInstruction decrypt(String ciphertext) {
            decryptCalls++;
            if (decryptFailure != null) throw decryptFailure;
            return instruction;
        }
    }

    private static class TestSettlement extends SettlementService {
        private Transaction transaction;
        private RuntimeException failure;
        private int calls;

        @Override
        public Transaction settle(PaymentInstruction instruction, String hash, String bridge, int hops) {
            calls++;
            if (failure != null) throw failure;
            return transaction;
        }
    }
}
