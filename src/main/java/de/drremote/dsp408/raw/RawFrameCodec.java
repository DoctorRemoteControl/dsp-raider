package de.drremote.dsp408.raw;

import java.nio.charset.StandardCharsets;

final class RawFrameCodec {
    private RawFrameCodec() {
    }

    static byte[] buildLoginPayload(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("PIN muss genau 4 Ziffern haben, z. B. 1234");
        }

        byte[] ascii = pin.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[5 + ascii.length];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x06;
        payload[3] = 0x2D;
        payload[4] = 0x00;
        System.arraycopy(ascii, 0, payload, 5, ascii.length);
        return payload;
    }

    static byte[] hex(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Hex text is null");
        }

        String cleaned = text
                .replace("0x", "")
                .replace("0X", "")
                .replaceAll("[^0-9A-Fa-f]", "");

        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Hex text is empty");
        }
        if ((cleaned.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex text has odd length: " + cleaned);
        }

        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(cleaned.charAt(i * 2), 16);
            int lo = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex text: " + text);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    static String toAsciiPreview(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v >= 32 && v <= 126) {
                sb.append((char) v);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    static byte[] buildFrame(byte[] payload) {
        return de.drremote.dsp408.protocol.DspFrameCodec.encode(payload);
    }

    static boolean isValidFrame(byte[] frame) {
        return de.drremote.dsp408.protocol.DspFrameCodec.isValidFrame(frame);
    }

    static byte[] extractPayload(byte[] frame) {
        try {
            return de.drremote.dsp408.protocol.DspFrameCodec.decode(frame).payload();
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
