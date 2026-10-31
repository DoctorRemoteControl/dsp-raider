package de.drremote.dsp408.snapshot;

import de.drremote.dsp408.fir.Fir408Memory;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.parameters.ParameterProfiles;

import java.io.IOException;
import java.util.TreeSet;

/** Offline-only analysis: no network/session dependency. */
public final class SnapshotAnalysis {
    private SnapshotAnalysis() { }

    public static String decode(Snapshot snapshot) throws IOException {
        String heading = "Device: " + snapshot.identity().version() + "\nProfile: " + snapshot.identity().profile() + "\n\n";
        if (snapshot.identity().profile().equals("FIR408")
                && ParameterProfiles.forVersion(snapshot.identity().version()).isPresent()) {
            try { return heading + SnapshotParameters.describe(snapshot) + Fir408Memory.decode(snapshot.data()); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid FIR408 memory", e); }
        }
        StringBuilder report = new StringBuilder(heading).append(SnapshotParameters.describe(snapshot))
                .append("Raw block overview (no memory decoder for this profile):\n");
        snapshot.blocks().forEach((index, response) -> report.append("block 0x%02X: %d data bytes\n"
                .formatted(index, response.payloadLen() - 5)));
        return report.toString();
    }

    public static String diff(Snapshot before, Snapshot after) {
        StringBuilder report = new StringBuilder("Before: " + before.identity().version() + "\nAfter: " + after.identity().version() + "\n");
        report.append(SnapshotParameters.diff(before,after));
        var indices = new TreeSet<>(before.blocks().keySet()); indices.addAll(after.blocks().keySet());
        int changed = 0, added = 0, removed = 0;
        for (int index : indices) {
            ProxyResponse left = before.blocks().get(index), right = after.blocks().get(index);
            if (left == null) {
                report.append("added block 0x%02X (%d bytes)\n".formatted(index, right.payloadLen() - 5));
                added += right.payloadLen() - 5; continue;
            }
            if (right == null) {
                report.append("removed block 0x%02X (%d bytes)\n".formatted(index, left.payloadLen() - 5));
                removed += left.payloadLen() - 5; continue;
            }
            byte[] a = left.payload(), b = right.payload();
            if (a.length != b.length) report.append("block 0x%02X length %d -> %d\n".formatted(index, a.length - 5, b.length - 5));
            for (int offset = 5; offset < Math.max(a.length, b.length); offset++) {
                Integer old = offset < a.length ? a[offset] & 0xff : null;
                Integer value = offset < b.length ? b[offset] & 0xff : null;
                if (java.util.Objects.equals(old, value)) continue;
                if (old == null) added++; else if (value == null) removed++; else changed++;
                report.append("block 0x%02X offset 0x%04X: %s -> %s\n".formatted(index, offset - 5, hex(old), hex(value)));
            }
        }
        return report.append("changed=%d added=%d removed=%d\n".formatted(changed, added, removed)).toString();
    }

    private static String hex(Integer value) { return value == null ? "--" : "%02X".formatted(value); }
}
