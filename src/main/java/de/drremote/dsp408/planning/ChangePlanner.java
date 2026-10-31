package de.drremote.dsp408.planning;

import de.drremote.dsp408.parameters.BlockData;
import de.drremote.dsp408.parameters.Evidence;
import de.drremote.dsp408.parameters.ParameterProfiles;
import de.drremote.dsp408.parameters.ParameterValue;

import java.util.ArrayList;
import java.util.Objects;

/** Pure comparison of saved parameter readings and requested target values. */
public final class ChangePlanner {
    private ChangePlanner() { }

    public static ChangePlan plan(String version, BlockData baseline, ChangeRequest request) {
        Objects.requireNonNull(version); Objects.requireNonNull(baseline); Objects.requireNonNull(request);
        if (!version.strip().equalsIgnoreCase(request.deviceVersion()))
            throw new IllegalArgumentException("Requested device_version differs from snapshot: " + request.deviceVersion() + " / " + version);
        var profile = ParameterProfiles.forVersion(version);
        var entries = new ArrayList<ChangePlan.Entry>();
        for (var change : request.changes()) {
            if (profile.isEmpty()) {
                entries.add(new ChangePlan.Entry(change, null, ChangePlan.Status.BLOCKED, "Kein bestätigtes Parameterprofil für diese Firmware"));
                continue;
            }
            var definition = profile.get().definition(change.channelIndex(), change.kind());
            var before = definition.read(baseline);
            if (before.status() != ParameterValue.Status.CONFIRMED) {
                entries.add(new ChangePlan.Entry(change, before, ChangePlan.Status.BLOCKED, before.status() + ": " + before.detail()));
            } else if (definition.writeEvidence().level() != Evidence.Level.CONFIRMED) {
                entries.add(new ChangePlan.Entry(change, before, ChangePlan.Status.BLOCKED, "Unbestätigte Schreibzuordnung"));
            } else if (change.kind().valueToRaw(before.value()) == change.writeRaw()) {
                entries.add(new ChangePlan.Entry(change, before, ChangePlan.Status.UNCHANGED, "Darstellbarer Zielwert entspricht dem Ausgangswert"));
            } else {
                entries.add(new ChangePlan.Entry(change, before, ChangePlan.Status.READY, "Bestätigter Ausgangswert und belegtes Schreibformat"));
            }
        }
        return new ChangePlan(version, entries);
    }
}
