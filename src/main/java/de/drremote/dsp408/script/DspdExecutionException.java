package de.drremote.dsp408.script;

import de.drremote.dsp408.dspd.SourcePosition;

/** A runtime failure at the original source position, retaining its actual cause. */
public final class DspdExecutionException extends Exception {
    private final SourcePosition position;

    DspdExecutionException(String sourceName, SourcePosition position, Exception cause) {
        super(sourceName + ":" + position.line() + ":" + position.column() + ": "
                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        this.position = position;
    }

    public SourcePosition position() { return position; }
}
