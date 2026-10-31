package de.drremote.dsp408.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PassiveCaptureTest {
    @ParameterizedTest @ValueSource(booleans = {false,true}) @Timeout(5)
    void disconnectedStreamCannotBeReportedAsASuccessfulIdleCapture(boolean sendFrame) throws Exception {
        try (var server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            var finished = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    if (sendFrame) {
                        String frame = "{\"type\":\"frame\",\"direction\":\"PC_TO_DSP\",\"validFrame\":true,"
                                + "\"frameHex\":\"10020001012C10032D\",\"payloadHex\":\"0001012C\"}\n";
                        socket.getOutputStream().write(frame.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                    finished.complete(null);
                } catch(Exception error) { finished.completeExceptionally(error); }
            });
            assertThrows(IOException.class,() -> new PassiveCapture().capture(
                    server.getInetAddress().getHostAddress(),server.getLocalPort(),120));
            finished.get(2,TimeUnit.SECONDS);
        }
    }
    @Test @Timeout(5)
    void observesTheFullIdleWindowAndReturnsAnEmptyCapture() throws Exception {
        check(false);
    }

    @Test @Timeout(5)
    void recordsActualStreamFrameWithoutSendingApplicationData() throws Exception {
        check(true);
    }

    private void check(boolean sendFrame) throws Exception {
        try (var server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            var release = new CountDownLatch(1);
            var result = new CompletableFuture<Integer>();
            Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    if (sendFrame) {
                        String frame = "{\"type\":\"frame\",\"direction\":\"PC_TO_DSP\",\"validFrame\":true,"
                                + "\"frameHex\":\"10020001012C10032D\",\"payloadHex\":\"0001012C\"}\n";
                        socket.getOutputStream().write(frame.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                    release.await(2,TimeUnit.SECONDS);
                    result.complete(socket.getInputStream().read());
                } catch(Exception error) { result.completeExceptionally(error); }
            });
            try {
                long start = System.nanoTime();
                var capture = new PassiveCapture().capture(server.getInetAddress().getHostAddress(), server.getLocalPort(), 120);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(millis >= 110, "Capture stopped before the requested idle window");
                assertTrue(millis < 2000, "Capture did not respect its deadline");
                assertEquals(sendFrame ? 1 : 0, capture.path("frames").size());
                if (sendFrame) assertEquals("00 01 01 2C", capture.path("frames").get(0).path("payload_hex").asText());
            } finally { release.countDown(); }
            assertEquals(-1,result.get(2,TimeUnit.SECONDS));
        }
    }
}
