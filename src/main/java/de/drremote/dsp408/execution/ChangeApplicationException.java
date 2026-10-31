package de.drremote.dsp408.execution;

/** Script execution stops on failure, retaining all partial application results. */
public final class ChangeApplicationException extends Exception {
    private final ApplyResult result;
    public ChangeApplicationException(ApplyResult result) {
        super(ApplyReport.text(result)); this.result = result;
    }
    public ApplyResult result() { return result; }
}
