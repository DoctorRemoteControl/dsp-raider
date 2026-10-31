package de.drremote.dsp408.parameters;

import java.util.Objects;

public record Evidence(Level level,String source,String status) {
    public enum Level { CONFIRMED, INFERRED, UNKNOWN }
    public Evidence {
        Objects.requireNonNull(level);Objects.requireNonNull(source);Objects.requireNonNull(status);
    }
}
