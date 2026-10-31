package de.drremote.dsp408.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public final class ApplyReport {
    private ApplyReport() { }

    public static String text(ApplyResult result) {
        var out = new StringBuilder("Apply: ").append(result.ok() ? "SUCCESS" : "FAILED")
                .append("\nDevice firmware: ").append(result.deviceVersion()).append('\n');
        if (!result.ok()) out.append("Error: ").append(result.error()).append('\n');
        for (var entry : result.entries()) {
            var change = entry.planned().change();
            out.append(change.channel()).append(' ').append(change.kind().label()).append(": ")
                    .append(entry.status()).append("; write_attempted=").append(entry.writeAttempted())
                    .append("; ").append(entry.detail()).append('\n');
        }
        return out.toString();
    }

    public static String json(ApplyResult result) throws IOException {
        var mapper = new ObjectMapper(); var root = mapper.createObjectNode();
        root.put("schema_version",1).put("type","change_apply").put("ok",result.ok())
                .put("device_version",result.deviceVersion()).put("error",result.error());
        var entries = root.putArray("entries");
        for (var entry : result.entries()) {
            var change = entry.planned().change();
            var row = entries.addObject().put("channel",change.channel()).put("parameter",change.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .put("status",entry.status().name()).put("write_attempted",entry.writeAttempted())
                    .put("requested_value",change.value()).put("target_value",change.targetValue()).put("detail",entry.detail());
            if (entry.readback() == null || entry.readback().value() == null) row.putNull("readback_value");
            else row.put("readback_value",entry.readback().value());
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}
