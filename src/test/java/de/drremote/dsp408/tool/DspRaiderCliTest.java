package de.drremote.dsp408.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ProxyStatus;
import de.drremote.dsp408.protocol.Hex;
import de.drremote.dsp408.session.DspSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DspRaiderCliTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), error = new ByteArrayOutputStream();
    private final DspRaiderCli offline = new DspRaiderCli((a,b,c,d,e) -> { throw new AssertionError("Unexpected connection"); });

    private int run(DspRaiderCli cli, String... args) {
        return cli.run(args, new PrintStream(out), new PrintStream(error));
    }

    private Path reference() throws Exception {
        Path file = temp.resolve("reference.json");
        try (var input = getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")) { Files.copy(input, file); }
        return file;
    }

    @Test void helpListsAllCommandsWithoutOpeningAConnection() {
        assertEquals(0, run(offline, "--help"));
        for (String command : List.of("connect", "identify", "dump", "decode", "diff", "capture"))
            assertTrue(out.toString(StandardCharsets.UTF_8).contains(command));
    }

    @Test void decodesAndComparesReferenceOffline() throws Exception {
        Path file = reference(), report = temp.resolve("decode.md");
        assertEquals(0, run(offline, "decode", "--in", file.toString(), "--out", report.toString()));
        assertTrue(Files.readString(report).contains("MAIN_L"));
        assertEquals(0, run(offline, "diff", "--before", file.toString(), "--after", file.toString()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("changed=0 added=0 removed=0"));
    }

    @Test void reportsUsageErrorsBeforeAnyDeviceAccess() {
        assertEquals(2, run(offline, "unknown"));
        assertEquals(2, run(offline, "dump", "--stream-port", "70000"));
        assertEquals(2, run(offline, "decode"));
        assertEquals(2, run(offline, "capture", "--duration-ms", "0", "--out", temp.resolve("capture.json").toString()));
        assertEquals(2, run(offline, "dump", "--not-an-option", "x"));
    }

    @Test void refusesToOverwriteReferenceBeforeConnecting() throws Exception {
        Path file = temp.resolve("existing.json"); Files.writeString(file, "keep this");
        assertEquals(1, run(offline, "dump", "--out", file.toString()));
        assertEquals("keep this", Files.readString(file));
    }

    @Test void connectionCheckFailsWhenProxySessionRemainsUnready() {
        var cli = new DspRaiderCli((a,b,c,d,e) -> (DspSession) Proxy.newProxyInstance(DspSession.class.getClassLoader(),
                new Class<?>[]{DspSession.class}, (proxy,method,params) -> switch(method.getName()) {
                    case "connect", "ensureSession", "close" -> null;
                    case "status" -> new ProxyStatus(false,false,"{}");
                    default -> throw new AssertionError(method.getName());
                }));
        assertEquals(1,run(cli,"connect"));
    }

    @Test void identifiesAndClosesSessionThroughCommonEntryPoint() throws Exception {
        var mapper = new ObjectMapper();
        var reference = mapper.readTree(reference().toFile());
        List<String> calls = new ArrayList<>();
        DspSession session = (DspSession) Proxy.newProxyInstance(DspSession.class.getClassLoader(), new Class<?>[]{DspSession.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("connect") || method.getName().equals("close")) return null;
                    if (method.getName().equals("status")) return new ProxyStatus(true,true,"{}");
                    String key = switch(method.getName()) {
                        case "handshakeInit" -> "init"; case "deviceInfo" -> "device"; case "systemInfo" -> "system";
                        default -> throw new AssertionError(method.getName());
                    };
                    var node = reference.path("handshake").path(key);
                    return new ProxyResponse(Hex.parse(node.path("raw_hex").asText()), Hex.parse(node.path("payload_hex").asText()), true);
                });
        var cli = new DspRaiderCli((a,b,c,d,e) -> session);
        assertEquals(0, run(cli, "identify"));
        assertEquals("FIR408 V0113P", mapper.readTree(out.toByteArray()).path("device_version").asText());
        assertEquals(List.of("connect","status","handshakeInit","deviceInfo","systemInfo","close"), calls);
    }
}
