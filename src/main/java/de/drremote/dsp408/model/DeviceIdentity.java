package de.drremote.dsp408.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** A recognized memory profile; unknown firmware names never silently become DSP408. */
public record DeviceIdentity(String profile, String version, int lastBlock) {
    public static DeviceIdentity fromPayload(byte[] payload) {
        String version = payload.length <= 4 ? "" :
                new String(payload, 4, payload.length - 4, StandardCharsets.US_ASCII).replace("\0", "").strip();
        String upper = version.toUpperCase(Locale.ROOT);
        if (upper.startsWith("FIR408")) return new DeviceIdentity("FIR408", version, 0x1f);
        if (upper.startsWith("DSP408") || upper.startsWith("DSP 408")) return new DeviceIdentity("DSP408", version, 0x1c);
        return new DeviceIdentity("UNKNOWN", version, -1);
    }
}
