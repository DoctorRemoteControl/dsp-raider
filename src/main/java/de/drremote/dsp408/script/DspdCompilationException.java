package de.drremote.dsp408.script;

/** A complete-program preflight failure, before any runtime is constructed. */
public final class DspdCompilationException extends Exception {
    DspdCompilationException(String report) { super(report); }
}
