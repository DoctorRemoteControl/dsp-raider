package de.drremote.dsp408.planning;

import de.drremote.dsp408.parameters.ParameterKind;

/** A requested engineering value, validated before it can enter a preview. */
public record ParameterChange(int channelIndex, ParameterKind kind, double value) {
    public ParameterChange {
        if (channelIndex < 0 || channelIndex > 11) throw new IllegalArgumentException("Channel must be in 0..11");
        if (kind == null) throw new IllegalArgumentException("Missing parameter kind");
        kind.valueToRaw(value);
    }

    public String channel() { return channelIndex < 4 ? "In" + (char) ('A' + channelIndex) : "Out" + (channelIndex - 3); }
    public int writeRaw() { return kind.valueToRaw(value); }
    public double targetValue() { return kind.rawToValue(writeRaw()); }
}
