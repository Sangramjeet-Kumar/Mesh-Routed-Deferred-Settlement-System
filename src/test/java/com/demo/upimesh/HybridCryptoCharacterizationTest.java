package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.PaymentInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class HybridCryptoCharacterizationTest {
    private static final HybridCryptoService crypto = new HybridCryptoService();
    private static final ServerKeyHolder key = new ServerKeyHolder();

    @BeforeAll
    static void setUp() throws Exception {
        key.init();
        ReflectionTestUtils.setField(crypto, "serverKey", key);
    }

    @Test
    void envelopeIsInteroperableWithExplicitOaepSha256AndAes256Gcm() throws Exception {
        byte[] envelope = Base64.getDecoder().decode(encrypt());
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.DECRYPT_MODE, key.getPrivateKey(), new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] aesKey = rsa.doFinal(Arrays.copyOfRange(envelope, 0, 256));
        assertEquals(32, aesKey.length);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 256, 268)));
        String plaintext = new String(aes.doFinal(Arrays.copyOfRange(envelope, 268, envelope.length)),
                StandardCharsets.UTF_8);
        assertTrue(plaintext.contains("\"senderVpa\":\"alice@demo\""));
        assertTrue(plaintext.contains("\"amount\":123.45"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256, 268, -1})
    void tamperingWithWrappedKeyIvPayloadOrTagIsRejected(int offset) throws Exception {
        byte[] envelope = Base64.getDecoder().decode(encrypt());
        envelope[offset < 0 ? envelope.length - 1 : offset] ^= 1;
        assertThrows(Exception.class, () -> crypto.decrypt(Base64.getEncoder().encodeToString(envelope)));
    }

    @Test
    void truncationAndDifferentServerKeyAreRejected() throws Exception {
        String ciphertext = encrypt();
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("YQ=="));
        ServerKeyHolder otherKey = new ServerKeyHolder();
        otherKey.init();
        HybridCryptoService otherCrypto = new HybridCryptoService();
        ReflectionTestUtils.setField(otherCrypto, "serverKey", otherKey);
        assertThrows(Exception.class, () -> otherCrypto.decrypt(ciphertext));
    }

    @Test
    void hashRemainsSha256OfEncodedTextRatherThanDecodedBytes() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                crypto.hashCiphertext("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                crypto.hashCiphertext(""));
        assertNotEquals(crypto.hashCiphertext("YWJj"), crypto.hashCiphertext("abc"));
    }

    private String encrypt() throws Exception {
        return crypto.encrypt(new PaymentInstruction("alice@demo", "bob@demo", new BigDecimal("123.45"),
                "pin-hash", "nonce", 123456789L), key.getPublicKey());
    }
}
