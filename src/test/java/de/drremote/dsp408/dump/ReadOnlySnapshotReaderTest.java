package de.drremote.dsp408.dump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ProxyStatus;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.Hex;
import de.drremote.dsp408.session.DspSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlySnapshotReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> calls = new ArrayList<>();
    private boolean ready = true;
    private boolean corruptBlock;
    private String version;
    private int shortBlock = -1;
    private int shortDataBytes;

    private DspSession session() throws Exception {
        JsonNode reference;
        try (var input = getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")) {
            reference = mapper.readTree(input);
        }
        return (DspSession) Proxy.newProxyInstance(DspSession.class.getClassLoader(),
                new Class<?>[]{DspSession.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    calls.add(name);
                    return switch (name) {
                        case "connect", "ensureSession" -> null;
                        case "status" -> new ProxyStatus(ready, ready, "{}");
                        case "handshakeInit" -> response(reference.at("/handshake/init"));
                        case "deviceInfo" -> version == null ? response(reference.at("/handshake/device")) : device(version);
                        case "systemInfo" -> response(reference.at("/handshake/system"));
                        case "readBlock" -> {
                            ProxyResponse result = response(reference.path("blocks").get((Integer) args[0]));
                            if ((Integer) args[0] == shortBlock) {
                                byte[] payload = Arrays.copyOf(result.payload(),5 + shortDataBytes);
                                payload[2] = (byte) (payload.length - 3);
                                result = new ProxyResponse(DspFrameCodec.encode(payload),payload,true);
                            }
                            if (corruptBlock) {
                                byte[] raw = result.raw(); raw[raw.length - 1] ^= 1;
                                result = new ProxyResponse(raw, result.payload(), true);
                            }
                            yield result;
                        }
                        default -> throw new AssertionError("Unexpected device operation: " + name);
                    };
                });
    }

    private ProxyResponse response(JsonNode frame) throws Exception {
        return new ProxyResponse(Hex.parse(frame.path("raw_hex").asText()),
                Hex.parse(frame.path("payload_hex").asText()), true);
    }

    private ProxyResponse device(String text) {
        byte[] name = text.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[name.length + 4];
        payload[0] = 1; payload[2] = (byte) (payload.length - 3); payload[3] = 0x13;
        System.arraycopy(name, 0, payload, 4, name.length);
        return new ProxyResponse(DspFrameCodec.encode(payload), payload, true);
    }

    @Test void reusesReadySessionAndReadsExactly32FirBlocksWithoutWritesOrReset() throws Exception {
        JsonNode result = mapper.readTree(new ReadOnlySnapshotReader().dump(session(), null).toJson());
        assertTrue(result.path("ok").asBoolean());
        assertEquals("FIR408 V0113P", result.path("device_version").asText());
        assertEquals(32, result.path("blocks").size());
        assertEquals(32, calls.stream().filter("readBlock"::equals).count());
        assertFalse(calls.contains("ensureSession"));
        assertEquals(List.of("connect", "status", "handshakeInit", "deviceInfo", "systemInfo"), calls.subList(0, 5));
    }

    @Test void establishesOnlyAnAbsentSession() throws Exception {
        ready = false;
        new ReadOnlySnapshotReader().identify(session());
        assertEquals(List.of("connect", "status", "ensureSession", "handshakeInit", "deviceInfo", "systemInfo"), calls);
    }

    @Test void uses29BlocksForRecognizedNonFirDevice() throws Exception {
        version = "DSP408 VTEST";
        JsonNode result = mapper.readTree(new ReadOnlySnapshotReader().dump(session(), null).toJson());
        assertEquals(29, result.path("blocks").size());
        assertEquals("DSP408", result.path("device_profile").asText());
    }

    @Test void unknownDeviceRequiresExplicitBlockRangeForDump() throws Exception {
        version = "UNKNOWN V1";
        assertThrows(DumpFailedException.class, () -> new ReadOnlySnapshotReader().dump(session(), null));
        assertFalse(calls.contains("readBlock"));
    }

    @Test void rejectsBadRawChecksumEvenWhenTransportClaimsSuccess() throws Exception {
        corruptBlock = true;
        DumpFailedException error = assertThrows(DumpFailedException.class,
                () -> new ReadOnlySnapshotReader().dump(session(), null));
        JsonNode partial = mapper.readTree(error.getResult().toJson());
        assertFalse(partial.path("ok").asBoolean());
        assertTrue(partial.path("summary").path("handshake_ok").asBoolean());
        assertTrue(partial.path("blocks").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "5,49", "31,0", "31,5"})
    void rejectsIncompleteKnownProfileDataBeforeReportingSuccess(int index, int dataBytes) throws Exception {
        shortBlock = index; shortDataBytes = dataBytes;
        var error = assertThrows(DumpFailedException.class,() -> new ReadOnlySnapshotReader().dump(session(),null));
        var partial = mapper.readTree(error.getResult().toJson());
        assertFalse(partial.path("ok").asBoolean());
        assertEquals(index,partial.path("blocks").size());
    }

    @Test void preservesExplicitPartialRangeAndUnknownProfileReads() throws Exception {
        assertEquals(1,mapper.readTree(new ReadOnlySnapshotReader().dump(session(),0).toJson()).path("blocks").size());
        version = "UNKNOWN V1";
        assertEquals(1,mapper.readTree(new ReadOnlySnapshotReader().dump(session(),0).toJson()).path("blocks").size());
    }
}
