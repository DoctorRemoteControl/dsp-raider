package de.drremote.dsp408.fir;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.session.DspSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Fir408TestFilterTest {
    @Test
    void uploadsAll512TapsIn45OrderedRequestsWithBigEndianCoefficients() throws Exception {
        var capture = new CapturingSession();
        String report = new Fir408TestFilter(capture.session()).upload(0xab, "two-tap", "two_tap_");

        assertEquals(45, capture.requests.size());
        assertArrayEquals(new byte[]{0, 1, 3, 0x4f, (byte) 0xab, 0}, capture.requests.getFirst().payload());

        for (int position = 1; position <= 43; position++) {
            byte[] payload = capture.requests.get(position).payload();
            assertEquals(3 + (payload[2] & 0xff), payload.length);
            assertEquals(0x4e, payload[3] & 0xff);
            assertEquals(0xab, payload[4] & 0xff);
            assertEquals(43 - position, payload[5] & 0xff);
            assertEquals(position == 1 ? 40 : 56, payload.length);
            assertArrayEquals(new byte[]{0, 2}, Arrays.copyOfRange(payload, 6, 8)); // 512 taps, uint16 LE
        }

        float[] expected = new float[512];
        expected[0] = 1.0f;
        expected[1] = 0.5f;
        assertArrayEquals(expected, coefficients(capture.requests));
        assertArrayEquals(new byte[]{0x3f, (byte) 0x80, 0, 0, 0x3f, 0, 0, 0},
                Arrays.copyOfRange(capture.requests.get(43).payload(), 8, 16));

        byte[] expectedName = {0, 1, 10, 0x5b, (byte) 0xab, 't', 'w', 'o', '_', 't', 'a', 'p', '_'};
        assertArrayEquals(expectedName, capture.requests.getLast().payload());
        assertTrue(capture.requests.stream().allMatch(r -> r.expectedCommand() == 1 && r.strict()));
        assertTrue(report.contains("- Writes: 45"));
    }

    @Test
    void preservesCenteredImpulsePositionAcrossChunkBoundaries() throws Exception {
        var capture = new CapturingSession();
        new Fir408TestFilter(capture.session()).upload(0, "center", "center__");

        float[] expected = new float[512];
        expected[255] = 1.0f;
        assertArrayEquals(expected, coefficients(capture.requests));
    }

    @ParameterizedTest
    @CsvSource({"ab,ab______", "0123456789,01234567"})
    void encodesPaddedOrTruncatedEightByteName(String input, String expected) throws Exception {
        var capture = new CapturingSession();
        new Fir408TestFilter(capture.session()).upload(0, "first", input);
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(capture.requests.getLast().payload(), 5, 13));
    }

    @ParameterizedTest
    @CsvSource({"-1,first", "256,first", "0,unknown"})
    void rejectsInvalidInputsBeforeSendingAnyRequest(int selector, String pattern) {
        var capture = new CapturingSession();
        assertThrows(IllegalArgumentException.class,
                () -> new Fir408TestFilter(capture.session()).upload(selector, pattern, "test____"));
        assertTrue(capture.requests.isEmpty());
    }

    @Test
    void stopsBeforeFurtherChunksAndFinalNameWhenSessionFails() {
        var capture = new CapturingSession();
        capture.failAt = 3;
        IOException failure = assertThrows(IOException.class,
                () -> new Fir408TestFilter(capture.session()).upload(0, "first", "test____"));

        assertSame(capture.failure, failure);
        assertEquals(3, capture.requests.size());
        assertEquals(41, capture.requests.getLast().payload()[5]);
        assertTrue(capture.requests.stream().noneMatch(r -> r.payload()[3] == 0x5b));
    }

    private static float[] coefficients(List<Request> requests) {
        float[] result = new float[512];
        for (Request request : requests) {
            byte[] payload = request.payload();
            if (payload[3] != 0x4e) continue;
            int offset = (payload[5] & 0xff) * 12;
            ByteBuffer data = ByteBuffer.wrap(payload, 8, payload.length - 8).order(ByteOrder.BIG_ENDIAN);
            while (data.hasRemaining()) result[offset++] = data.getFloat();
        }
        return result;
    }

    private record Request(byte[] payload, Integer expectedCommand, boolean strict) { }

    /** Only the device transport is replaced; the uploader and its encoding stay real. */
    private static final class CapturingSession {
        final List<Request> requests = new ArrayList<>();
        final IOException failure = new IOException("simulated DSP connection failure");
        int failAt = -1;

        DspSession session() {
            return (DspSession) Proxy.newProxyInstance(DspSession.class.getClassLoader(),
                    new Class<?>[]{DspSession.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("sendPayload")) throw new AssertionError(method.getName());
                        requests.add(new Request(((byte[]) args[0]).clone(), (Integer) args[1], (Boolean) args[2]));
                        if (requests.size() == failAt) throw failure;
                        byte[] ack = {1, 0, 2, 1, 0};
                        return new ProxyResponse(DspFrameCodec.encode(ack), ack, true);
                    });
        }
    }
}
