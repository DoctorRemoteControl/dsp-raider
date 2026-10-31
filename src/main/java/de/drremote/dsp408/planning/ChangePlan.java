package de.drremote.dsp408.planning;

import de.drremote.dsp408.parameters.ParameterValue;
import de.drremote.dsp408.protocol.Hex;

import java.util.List;

/** A preview only. This value object has no execution operation. */
public record ChangePlan(String deviceVersion, List<Entry> entries) {
    public ChangePlan { entries = List.copyOf(entries); }

    public enum Status { READY, UNCHANGED, BLOCKED }

    public record Entry(ParameterChange change, ParameterValue before, Status status, String reason) {
        public String payloadHex() {
            return status == Status.READY ? Hex.bytes(change.kind().writePayload(change.channelIndex(), change.value())) : "";
        }
    }

    public long readyCount() { return count(Status.READY); }
    public long unchangedCount() { return count(Status.UNCHANGED); }
    public long blockedCount() { return count(Status.BLOCKED); }
    private long count(Status status) { return entries.stream().filter(entry -> entry.status() == status).count(); }
}
