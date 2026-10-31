package de.drremote.dsp408.util;

import java.io.IOException;

public final class HexUtil {
    private HexUtil() {
    }

    public static String payloadAscii(byte[] payload) {
        return de.drremote.dsp408.protocol.Hex.ascii(payload);
    }

    public static String toHex(byte[] data) {
        return de.drremote.dsp408.protocol.Hex.bytes(data);
    }

    public static String toHexPreview(byte[] data, int maxBytes) {
        return de.drremote.dsp408.protocol.Hex.preview(data, maxBytes);
    }

    public static String toHexCompact(byte[] data) {
        return de.drremote.dsp408.protocol.Hex.compact(data);
    }

    public static String compactHex(String hex) {
        return hex == null ? null : hex.replaceAll("\\s+", "").toUpperCase();
    }

    public static byte[] hexToBytes(String hex) throws IOException {
        return de.drremote.dsp408.protocol.Hex.parse(hex);
    }
}
