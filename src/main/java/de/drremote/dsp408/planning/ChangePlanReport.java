package de.drremote.dsp408.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.parameters.Evidence;
import de.drremote.dsp408.parameters.ParameterKind;

/** Human and machine-readable views of the same offline plan. */
public final class ChangePlanReport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChangePlanReport() { }

    public static String text(ChangePlan plan) {
        var report = new StringBuilder("DSP Raider change preview\nDevice: ")
                .append(plan.deviceVersion()).append("\nPreview – no transmission.\n\n");
        for (var entry : plan.entries()) {
            var change = entry.change();
            String before = entry.before() == null ? "unknown" : entry.before().display();
            report.append("%s %s [%s]: %s -> %s\n".formatted(change.channel(), change.kind().label(), entry.status(), before,
                    change.kind().format(change.targetValue())));
            String requested = change.kind() == ParameterKind.MUTE ? change.kind().format(change.value())
                    : Double.toString(change.value()) + " " + change.kind().unit();
            report.append("  requested: %s; representable: %s; rounded: %s\n".formatted(requested,
                    change.kind().format(change.targetValue()), rounded(change) ? "yes" : "no"));
            report.append("  ").append(entry.reason()).append('\n');
            var reading = entry.before();
            report.append("  Storage raw value: ").append(reading == null || reading.raw() == null ? "unknown" : reading.raw()).append('\n');
            report.append("  Read evidence: ").append(reading == null ? "unknown" : evidence(reading.definition().readEvidence())).append('\n');
            report.append("  Write evidence: ").append(reading == null ? "unknown" : evidence(reading.definition().writeEvidence())).append('\n');
            if (!entry.payloadHex().isEmpty()) report.append("  payload: ").append(entry.payloadHex()).append('\n');
        }
        return report.append("\nready=%d unchanged=%d blocked=%d\n".formatted(plan.readyCount(), plan.unchangedCount(), plan.blockedCount())).toString();
    }

    public static String json(ChangePlan plan) throws JsonProcessingException {
        var root = MAPPER.createObjectNode().put("schema_version", 1).put("type", "change_plan")
                .put("preview_only", true).put("device_version", plan.deviceVersion());
        root.putObject("summary").put("ready", plan.readyCount()).put("unchanged", plan.unchangedCount()).put("blocked", plan.blockedCount());
        var entries = root.putArray("entries");
        for (var entry : plan.entries()) {
            var change = entry.change();
            var row = entries.addObject().put("channel", change.channel()).put("parameter", change.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .put("unit", change.kind().unit()).put("requested_value", change.value()).put("target_value", change.targetValue())
                    .put("target_write_raw", change.writeRaw()).put("rounded", rounded(change))
                    .put("status", entry.status().name()).put("reason", entry.reason());
            var before = entry.before();
            row.put("before_status", before == null ? "UNKNOWN" : before.status().name());
            if (before != null && before.value() != null) row.put("before_value", before.value()); else row.putNull("before_value");
            if (before != null && before.raw() != null) row.put("before_storage_raw", before.raw()); else row.putNull("before_storage_raw");
            if (before != null) {
                row.set("read_evidence", MAPPER.valueToTree(before.definition().readEvidence()));
                row.set("write_evidence", MAPPER.valueToTree(before.definition().writeEvidence()));
            } else {
                row.putNull("read_evidence"); row.putNull("write_evidence");
            }
            if (entry.status() == ChangePlan.Status.READY) row.put("payload_hex", entry.payloadHex()); else row.putNull("payload_hex");
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static boolean rounded(ParameterChange change) {
        return change.value() != change.targetValue();
    }

    private static String evidence(Evidence evidence) {
        return evidence.level() + " " + evidence.source() + " (" + evidence.status() + ")";
    }
}
