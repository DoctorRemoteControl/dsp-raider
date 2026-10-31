package de.drremote.dsp408.fir;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.util.DspProtocol;
import de.drremote.dsp408.util.HexUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static de.drremote.dsp408.fir.Fir408Memory.*;

/** Explicit FIR test uploads; separate from read-only diagnostics. */
public final class Fir408TestFilter {
    private final DspSession session;

    public Fir408TestFilter(DspSession session) { this.session = Objects.requireNonNull(session); }

    public String upload(int selector, String pattern, String name8) throws Exception {
        if (selector < 0 || selector > 0xFF) {
            throw new IllegalArgumentException("Selector must be inside 0x00..0xFF.");
        }
        byte[] nameBytes = firNameBytes(name8);
        float[] coeffs = firTestCoefficients(pattern);
        DspSession client = session;
        List<String> lines = new ArrayList<>();
        lines.add("# FIR408 External FIR Test Upload");
        lines.add("");
        lines.add("- Selector: " + String.format("0x%02X", selector));
        lines.add("- Pattern: " + pattern);
        lines.add("- Name: `" + new String(nameBytes, StandardCharsets.US_ASCII) + "`");
        lines.add("- Taps: " + coeffs.length);
        lines.add("- Upload encoding: float32 big-endian");
        lines.add("- Chunk order: 0x2A down to 0x00");
        lines.add("");
        lines.add("| Step | Payload | Response |");
        lines.add("| --- | --- | --- |");

        ProxyResponse start = client.sendPayload(payload(0x00, 0x01, 0x03, 0x4F, selector, 0x00), 0x01, true, "fir408_upload_start");
        lines.add(uploadRow("transfer-start", payload(0x00, 0x01, 0x03, 0x4F, selector, 0x00), start));

        for (int chunk = 0x2A; chunk >= 0; chunk--) {
            byte[] chunkPayload = firUploadChunkPayload(selector, chunk, coeffs);
            ProxyResponse response = client.sendPayload(chunkPayload, 0x01, true, "fir408_upload_chunk_" + String.format("%02X", chunk));
            lines.add(uploadRow("chunk-" + String.format("%02X", chunk), chunkPayload, response));
        }

        byte[] namePayload = new byte[13];
        namePayload[0] = 0x00;
        namePayload[1] = 0x01;
        namePayload[2] = 0x0A;
        namePayload[3] = 0x5B;
        namePayload[4] = (byte) (selector & 0xFF);
        System.arraycopy(nameBytes, 0, namePayload, 5, 8);
        ProxyResponse nameResponse = client.sendPayload(namePayload, 0x01, true, "fir408_upload_name");
        lines.add(uploadRow("name", namePayload, nameResponse));

        lines.add("");
        lines.add("- Writes: 45");
        return String.join(System.lineSeparator(), lines);
    }

    private static String uploadRow(String name, byte[] request, ProxyResponse response) {
        return "| " + name
                + " | `" + HexUtil.toHex(request) + "`"
                + " | `" + (response == null ? "null" : response.payloadHex()) + "` |";
    }

    private static byte[] firUploadChunkPayload(int selector, int chunk, float[] coeffs) {
        int startCoeff = chunk * 12;
        int remainingCoeffs = Math.max(0, coeffs.length - startCoeff);
        int coeffCount = Math.min(12, remainingCoeffs);
        int dataBytes = coeffCount * 4;
        byte[] out = new byte[8 + dataBytes];
        out[0] = 0x00;
        out[1] = 0x01;
        out[2] = (byte) ((out.length - 3) & 0xFF);
        out[3] = 0x4E;
        out[4] = (byte) (selector & 0xFF);
        out[5] = (byte) (chunk & 0xFF);
        out[6] = 0x00;
        out[7] = 0x02;
        for (int i = 0; i < coeffCount; i++) {
            int bits = Float.floatToIntBits(coeffs[startCoeff + i]);
            int offset = 8 + (i * 4);
            out[offset] = (byte) ((bits >>> 24) & 0xFF);
            out[offset + 1] = (byte) ((bits >>> 16) & 0xFF);
            out[offset + 2] = (byte) ((bits >>> 8) & 0xFF);
            out[offset + 3] = (byte) (bits & 0xFF);
        }
        return out;
    }

    private static float[] firTestCoefficients(String pattern) {
        String key = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        float[] coeffs = new float[512];
        switch (key) {
            case "first", "impulse-first", "impulse_first" -> coeffs[0] = 1.0f;
            case "center", "impulse-center", "impulse_center" -> coeffs[255] = 1.0f;
            case "two-tap", "two_tap", "two-tap-1-05", "two_tap_1_05" -> {
                coeffs[0] = 1.0f;
                coeffs[1] = 0.5f;
            }
            case "small-ramp", "small_ramp" -> {
                for (int i = 0; i < coeffs.length; i++) {
                    coeffs[i] = (i + 1) / 1_000_000.0f;
                }
            }
            case "strong-ramp", "strong_ramp" -> {
                for (int i = 0; i < coeffs.length; i++) {
                    coeffs[i] = (i + 1) / 100_000.0f;
                }
            }
            default -> throw new IllegalArgumentException("Unknown FIR test pattern: " + pattern);
        }
        return coeffs;
    }

    private static byte[] firNameBytes(String name8) {
        String text = name8 == null ? "" : name8;
        if (text.length() > 8) {
            text = text.substring(0, 8);
        }
        while (text.length() < 8) {
            text += "_";
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != 8) {
            throw new IllegalArgumentException("FIR name must be exactly 8 ASCII bytes after padding/truncation.");
        }
        return bytes;
    }

    public static String defaultName(String pattern) {
        String key = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "first", "impulse-first", "impulse_first" -> "imp1st__";
            case "center", "impulse-center", "impulse_center" -> "ctr_255_";
            case "two-tap", "two_tap", "two-tap-1-05", "two_tap_1_05" -> "two_tap_";
            case "small-ramp", "small_ramp" -> "ramp____";
            case "strong-ramp", "strong_ramp" -> "ramp10x_";
            default -> "firtest_";
        };
    }

}
