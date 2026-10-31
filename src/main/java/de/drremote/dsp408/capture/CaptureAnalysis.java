package de.drremote.dsp408.capture;

import com.fasterxml.jackson.databind.JsonNode;
import de.drremote.dsp408.decode.DspLibLookup;
import de.drremote.dsp408.decode.WriteDecodeService;
import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.snapshot.SnapshotFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Offline interpretation of passive captures, optionally using a field library. */
public final class CaptureAnalysis {
    private CaptureAnalysis() { }

    public static String decode(JsonNode capture, Path library) throws IOException {
        if (!capture.path("ok").asBoolean(false) || !capture.path("frames").isArray())
            throw new IOException("Invalid capture document");
        if (capture.has("frames_total") && capture.path("frames_total").asInt(-1) != capture.path("frames").size())
            throw new IOException("Capture frame count metadata differs");
        var decoder = library == null ? null : new WriteDecodeService(DspLibLookup.load(library));
        var report = new StringBuilder("DSP capture: " + capture.path("frames").size() + " frames\n");
        for (JsonNode node : capture.path("frames")) {
            String direction = node.path("direction").asText();
            if (!Set.of("PC_TO_DSP","DSP_TO_PC").contains(direction)) throw new IOException("Unknown frame direction");
            var response = SnapshotFiles.checkedFrame(node,null,null);
            report.append(direction).append(" ").append(response.commandHex()).append(" ")
                    .append(response.payloadAscii()).append("\n  payload: ").append(response.payloadHex()).append('\n');
            if (decoder != null && direction.equals("PC_TO_DSP")) {
                var frame = new SniffedFrame(direction,response.raw(),response.payload(),response.command(),response.payloadLen(),true);
                report.append(decoder.decode(frame).format()).append('\n');
            }
        }
        return report.toString();
    }
}
