package com.demo.upimesh;

import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.MeshSimulatorService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeshSimulatorCharacterizationTest {
    private final MeshSimulatorService mesh = new MeshSimulatorService();

    @Test
    void oneRoundDistributesCopiesAndFurtherRoundsDoNotLowerHeldTtls() {
        MeshPacket packet = packet(5);
        mesh.inject("phone-alice", packet);
        assertEquals(4, mesh.gossipOnce().transfers());
        assertEquals(0, mesh.gossipOnce().transfers());
        mesh.getDevices().forEach(device -> {
            assertEquals(1, device.packetCount());
            MeshPacket held = device.getHeldPackets().iterator().next();
            assertEquals(device.getDeviceId().equals("phone-alice") ? 5 : 4, held.getTtl());
            assertEquals(packet.getCiphertext(), held.getCiphertext());
            assertEquals(packet.getCreatedAt(), held.getCreatedAt());
        });
    }

    @Test
    void zeroTtlStaysAtOriginAndDoesNotReachBridge() {
        mesh.inject("phone-alice", packet(0));
        assertEquals(0, mesh.gossipOnce().transfers());
        assertEquals(1, mesh.getDevice("phone-alice").packetCount());
        assertTrue(mesh.collectBridgeUploads().isEmpty());
    }

    @Test
    void bridgeRetainsZeroTtlPacketForRepeatedUploadCollection() {
        mesh.inject("phone-alice", packet(1));
        mesh.gossipOnce();
        assertEquals(1, mesh.collectBridgeUploads().size());
        assertEquals(0, mesh.collectBridgeUploads().get(0).packet().getTtl());
        assertEquals(1, mesh.collectBridgeUploads().size());
        mesh.resetMesh();
        assertTrue(mesh.collectBridgeUploads().isEmpty());
        assertEquals(5, mesh.getDevices().size());
    }

    @Test
    void devicesKeepFirstPacketForAnOuterPacketId() {
        MeshPacket first = packet(5);
        MeshPacket second = packet(2);
        second.setCiphertext("different-ciphertext");
        mesh.inject("phone-alice", first);
        mesh.inject("phone-alice", second);
        assertEquals(1, mesh.getDevice("phone-alice").packetCount());
        assertSame(first, mesh.getDevice("phone-alice").getHeldPackets().iterator().next());
    }

    @Test
    void unknownInjectionDeviceFailsWithExistingMessage() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> mesh.inject("missing", packet(5)));
        assertEquals("Unknown device: missing", error.getMessage());
    }

    private MeshPacket packet(int ttl) {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId("01234567-89ab-cdef-0123-456789abcdef");
        packet.setTtl(ttl);
        packet.setCreatedAt(123L);
        packet.setCiphertext("opaque-ciphertext");
        return packet;
    }
}
