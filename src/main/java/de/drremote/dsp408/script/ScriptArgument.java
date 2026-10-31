package de.drremote.dsp408.script;

/** A lazily evaluated builtin argument, supplied by either language frontend. */
@FunctionalInterface
interface ScriptArgument {
    Object evaluate() throws Exception;
}
