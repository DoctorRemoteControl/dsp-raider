package de.drremote.dsp408.execution;

import de.drremote.dsp408.parameters.ParameterValue;
import de.drremote.dsp408.planning.ChangePlan;
import java.util.List;

/** Actual application progress; attempted writes may have effects even on failure. */
public record ApplyResult(boolean ok, String deviceVersion, List<Entry> entries, String error) {
    public ApplyResult { entries = List.copyOf(entries); }
    public enum Status { APPLIED, UNCHANGED, BLOCKED, FAILED, NOT_ATTEMPTED }
    public record Entry(ChangePlan.Entry planned, Status status, boolean writeAttempted,
                        ParameterValue readback, String detail) { }
}
