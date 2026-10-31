package de.drremote.dsp408.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.Hex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CaptureAnalysisTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode capture() throws Exception {
        var root = mapper.createObjectNode().put("ok",true).put("type","capture").put("frames_total",1);
        try (var input = getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")) {
            ObjectNode device = (ObjectNode) mapper.readTree(input).at("/handshake/device").deepCopy();
            device.put("direction","DSP_TO_PC"); root.putArray("frames").add(device);
        }
        return root;
    }

    @Test void interpretsRealIdentityFrameInSavedCaptureWithoutNetwork() throws Exception {
        assertTrue(CaptureAnalysis.decode(capture(),null).contains("FIR408 V0113P"));
    }

    @Test void rejectsForgedCaptureChecksum() throws Exception {
        ObjectNode root = capture();
        ObjectNode frame = (ObjectNode) root.path("frames").get(0);
        byte[] raw = Hex.parse(frame.path("raw_hex").asText()); raw[raw.length-1] ^= 1;
        frame.put("raw_hex",Hex.bytes(raw));
        assertThrows(IOException.class,() -> CaptureAnalysis.decode(root,null));
    }

    @Test void decodesParameterWriteUsingExplicitFieldLibrary() throws Exception {
        ObjectNode root = capture(), frame = (ObjectNode) root.path("frames").get(0);
        byte[] payload = {0,1,3,0x35,0,1};
        frame.removeAll();
        frame.put("direction","PC_TO_DSP").put("payload_hex",Hex.bytes(payload))
                .put("raw_hex",Hex.bytes(DspFrameCodec.encode(payload))).put("cmd","0x35");
        String report = CaptureAnalysis.decode(root,Path.of("DspLib-408.json"));
        assertTrue(report.contains("[MATCH]"));
        assertTrue(report.toLowerCase().contains("mute"));
    }
}
