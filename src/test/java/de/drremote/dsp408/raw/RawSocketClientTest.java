package de.drremote.dsp408.raw;

import de.drremote.dsp408.protocol.DspFrameCodec;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RawSocketClientTest {
    private static final byte[] REQUEST = {0, 1, 1, 0x2c};

    @Test
    void retainsSecondBatchedResponseWhenEachReadRequestsOnlyOneFrame() throws Exception {
        byte[] first = DspFrameCodec.encode(new byte[]{1, 0, 1, 0x2c});
        byte[] second = DspFrameCodec.encode(new byte[]{1, 0, 2, 0x24, 1});
        byte[] batch = new byte[first.length + second.length];
        System.arraycopy(first, 0, batch, 0, first.length);
        System.arraycopy(second, 0, batch, first.length, second.length);

        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = new RawSocketClient(server.getInetAddress().getHostAddress(),
                     server.getLocalPort(), 1000, 200, false)) {
            server.setSoTimeout(2000);
            client.connect();
            try (var peer = server.accept()) {
                peer.getOutputStream().write(batch);
                peer.getOutputStream().flush();
                peer.shutdownOutput();

                var firstRead = client.sendPayloadAndRead("first", REQUEST, 1);
                assertEquals(1, firstRead.size());
                assertArrayEquals(first, firstRead.getFirst());

                var secondRead = client.sendPayloadAndRead("second", REQUEST, 1);
                assertEquals(1, secondRead.size());
                assertArrayEquals(second, secondRead.getFirst());
            }
        }
    }

    @Test
    void retainsPartialBinaryFrameAcrossSocketTimeoutAndNextRequest() throws Exception {
        byte[] wire = DspFrameCodec.encode(new byte[]{1, 0, 8, 0x24, 0x10, 0x03, 0x55, 0x66, 0x10, 0x10, 0});
        // Split inside a literal 10 03 in the payload, not at a frame boundary.
        int split = 7;

        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = new RawSocketClient(server.getInetAddress().getHostAddress(),
                     server.getLocalPort(), 1000, 100, false)) {
            server.setSoTimeout(2000);
            client.connect();
            try (var peer = server.accept()) {
                peer.getOutputStream().write(Arrays.copyOfRange(wire, 0, split));
                peer.getOutputStream().flush();

                // The remainder is sent only after the real read timeout returns.
                assertTrue(client.sendPayloadAndRead("partial", REQUEST, 1).isEmpty());

                peer.getOutputStream().write(Arrays.copyOfRange(wire, split, wire.length));
                peer.getOutputStream().flush();
                peer.shutdownOutput();

                var resumed = client.sendPayloadAndRead("resumed", REQUEST, 1);
                assertEquals(1, resumed.size());
                assertArrayEquals(wire, resumed.getFirst());
            }
        }
    }
}
