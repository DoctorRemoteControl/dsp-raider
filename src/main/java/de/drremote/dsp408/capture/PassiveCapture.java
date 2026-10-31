package de.drremote.dsp408.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.proxy.GuiSnifferClient;
import de.drremote.dsp408.snapshot.SnapshotFrames;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Passive observer with an absolute deadline, including periods with no traffic. */
public final class PassiveCapture {
    public ObjectNode capture(String host, int port, long durationMs) throws Exception {
        if (durationMs < 1 || durationMs > 60_000) throw new IllegalArgumentException("duration-ms must be in 1..60000");
        var mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true).put("type", "capture").put("captured_at", Instant.now().toString()).put("duration_ms", durationMs);
        var frames = result.putArray("frames");
        try (var sniffer = new GuiSnifferClient(host, port, false)) {
            sniffer.connect(2000);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                var frame = sniffer.waitForAnyFrame(Math.max(1, (remaining + 999_999) / 1_000_000));
                if (frame == null) continue;
                var checked = SnapshotFrames.checked(new ProxyResponse(frame.frame(), frame.payload(), frame.checksumOk()), null, null);
                var node = frames.addObject();
                node.put("direction", frame.direction()).put("raw_hex", checked.rawHex())
                        .put("payload_hex", checked.payloadHex()).put("cmd", checked.commandHex()).put("checksum_ok", true);
            }
        }
        result.put("frames_total", frames.size());
        return result;
    }
}
