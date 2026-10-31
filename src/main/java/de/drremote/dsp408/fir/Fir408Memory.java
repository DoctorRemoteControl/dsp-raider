package de.drremote.dsp408.fir;

import de.drremote.dsp408.parameters.BlockData;
import de.drremote.dsp408.parameters.ParameterKind;
import de.drremote.dsp408.parameters.ParameterProfile;
import de.drremote.dsp408.parameters.ParameterProfiles;
import de.drremote.dsp408.parameters.ParameterValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure analysis of captured FIR408 memory; never opens a connection. */
public final class Fir408Memory {
    private static final ParameterProfile PARAMETERS=ParameterProfiles.forVersion("FIR408 V0113P").orElseThrow();
    private Fir408Memory() { }

    static List<PingSpec> sameValuePings(byte[] memory) {
        requireMemory(memory, 1556);
        List<PingSpec> out = new ArrayList<>();
        String[] inputs = {"InA", "InB", "InC", "InD"};
        String[] outputs = {"Out1", "Out2", "Out3", "Out4", "Out5", "Out6", "Out7", "Out8"};

        for (int i = 0; i < inputs.length; i++) {
            int base = inputBase(i);
            int ch = i;
            out.add(new PingSpec(inputs[i] + "-name-current", channelNamePayload(ch, memory, base)));
            out.add(new PingSpec(inputs[i] + "-gain-current", payload(0x00, 0x01, 0x04, 0x34, ch, lo(u16(memory, base + 130)), hi(u16(memory, base + 130)))));
            out.add(new PingSpec(inputs[i] + "-phase-current", payload(0x00, 0x01, 0x03, 0x36, ch, u8(memory, base + 132))));
            out.add(new PingSpec(inputs[i] + "-delay-current", payload(0x00, 0x01, 0x04, 0x38, ch, lo(u16(memory, base + 134)), hi(u16(memory, base + 134)))));
            out.add(new PingSpec(inputs[i] + "-gate-current", payload(
                    0x00, 0x01, 0x0A, 0x3E, ch,
                    u8(memory, base + 8), u8(memory, base + 9),
                    u8(memory, base + 10), u8(memory, base + 11),
                    u8(memory, base + 12), u8(memory, base + 13),
                    u8(memory, base + 14), u8(memory, base + 15)
            )));
            out.add(new PingSpec(inputs[i] + "-peq1-current", inputPeqPayload(ch, memory, base + 64, 0)));
        }

        for (int i = 0; i < outputs.length; i++) {
            int base = outputBase(i);
            int ch = 0x04 + i;
            out.add(new PingSpec(outputs[i] + "-name-current", channelNamePayload(ch, memory, base)));
            out.add(new PingSpec(outputs[i] + "-gain-current", payload(0x00, 0x01, 0x04, 0x34, ch, lo(u16(memory, base + 100)), hi(u16(memory, base + 100)))));
            out.add(new PingSpec(outputs[i] + "-phase-current", payload(0x00, 0x01, 0x03, 0x36, ch, u8(memory, base + 102))));
            out.add(new PingSpec(outputs[i] + "-delay-current", payload(0x00, 0x01, 0x04, 0x38, ch, lo(u16(memory, base + 104)), hi(u16(memory, base + 104)))));
            out.add(new PingSpec(outputs[i] + "-matrix-route-current", payload(0x00, 0x01, 0x03, 0x3A, ch, u8(memory, base + 8))));
            for (int input = 0; input < 4; input++) {
                int raw = u16(memory, base + 10 + (input * 2));
                out.add(new PingSpec(outputs[i] + "-matrix-gain-in" + (input + 1) + "-current",
                        payload(0x00, 0x01, 0x05, 0x41, ch, input, lo(raw), hi(raw))));
            }
            out.add(new PingSpec(outputs[i] + "-peq1-current", outputPeqPayload(ch, memory, base + 36, 0)));
            out.add(new PingSpec(outputs[i] + "-fir-generator-current", payload(
                    0x00, 0x01, 0x0A, 0x4B, ch,
                    u8(memory, base + 28), u8(memory, base + 29),
                    u8(memory, base + 30), u8(memory, base + 31),
                    u8(memory, base + 32), u8(memory, base + 33),
                    u8(memory, base + 34), u8(memory, base + 35)
            )));
        }

        return List.copyOf(out);
    }

    public static String decode(byte[] memory) {
        requireMemory(memory, 1556);
        BlockData data=BlockData.ofMemory(memory);
        List<String> lines = new ArrayList<>();
        lines.add("# FIR408 DSPD Static Decode");
        lines.add("");
        lines.add("- Assembled data bytes: " + memory.length);
        lines.add("- Memory layout: FIR408 V0113P");
        lines.add("- Method: DSPD `ReadBlockSet` static decode");
        lines.add("- No GUI capture required");
        lines.add("");
        lines.add("## Inputs");
        lines.add("");
        lines.add("| Input | Label | Gain | Phase | Delay | Gate A/R/H/T | PEQ1 | Mute |");
        lines.add("| --- | --- | ---: | ---: | ---: | --- | --- | --- |");
        for (int i = 0; i < 4; i++) {
            int base = inputBase(i);
            int peq = base + 64;
            lines.add("| In" + (char) ('A' + i)
                    + " | " + ascii(memory, base, 8)
                    + " | " + parameter(data,i,ParameterKind.GAIN)
                    + " | " + u16(memory, base + 132)
                    + " | " + parameter(data,i,ParameterKind.DELAY)
                    + " | " + u16(memory, base + 8) + "/" + u16(memory, base + 10) + "/" + u16(memory, base + 12) + "/" + u16(memory, base + 14)
                    + " | " + peqSummary(memory, peq)
                    + " | " + parameter(data,i,ParameterKind.MUTE)
                    + " |");
        }

        lines.add("");
        lines.add("## Outputs");
        lines.add("");
        lines.add("| Output | Label | Gain | Phase | Delay | Matrix | FIR | PEQ1 | Mute |");
        lines.add("| --- | --- | ---: | ---: | ---: | --- | --- | --- | --- |");
        for (int i = 0; i < 8; i++) {
            int base = outputBase(i);
            lines.add("| Out" + (i + 1)
                    + " | " + ascii(memory, base, 8)
                    + " | " + parameter(data,i+4,ParameterKind.GAIN)
                    + " | " + u16(memory, base + 102)
                    + " | " + parameter(data,i+4,ParameterKind.DELAY)
                    + " | route=0x" + String.format("%02X", u8(memory, base + 8))
                    + " " + matrixGainSummary(memory, base)
                    + " | " + firSummary(memory, base)
                    + " | " + peqSummary(memory, base + 36)
                    + " | " + parameter(data,i+4,ParameterKind.MUTE)
                    + " |");
        }

        lines.add("");
        lines.add("## Notes");
        lines.add("");
        lines.add("- Crossover active/remembered mode bytes are decoded statically but not written by the safe ping suite.");
        lines.add("- Mute: only FIR-specific InD and Out8 bits are confirmed; inherited bit assignments remain unknown.");
        lines.add("- Compressor/Limiter extended fields are intentionally excluded from the first DSPD safe ping pass.");
        return String.join(System.lineSeparator(), lines);
    }

    private static String parameter(BlockData data,int channel,ParameterKind kind) {
        var value=PARAMETERS.definition(channel,kind).read(data);
        if(value.status()!=ParameterValue.Status.CONFIRMED)return value.display();
        return switch(kind) {
            case GAIN -> formatDb(value.value());
            case DELAY -> formatMs(value.value());
            case MUTE -> value.display();
        };
    }

    private static String matrixGainSummary(byte[] memory, int outputBase) {
        String[] inputs = {"InA", "InB", "InC", "InD"};
        List<String> gains = new ArrayList<>();
        for (int input = 0; input < inputs.length; input++) {
            int raw = u16(memory, outputBase + 10 + (input * 2));
            gains.add(inputs[input] + "=" + formatDb(gainDb(raw)) + "(raw=" + raw + ")");
        }
        return "gains=" + String.join(" ", gains);
    }

    private static byte[] inputPeqPayload(int channel, byte[] memory, int offset, int band) {
        int gain = u16(memory, offset);
        int freq = u16(memory, offset + 2);
        return payload(
                0x00, 0x01, 0x0A, 0x33,
                channel, band,
                lo(gain), hi(gain),
                lo(freq), hi(freq),
                u8(memory, offset + 4), u8(memory, offset + 5), 0x00
        );
    }

    private static byte[] outputPeqPayload(int channel, byte[] memory, int offset, int band) {
        int gain = u16(memory, offset);
        int freq = u16(memory, offset + 2);
        return payload(
                0x00, 0x01, 0x0A, 0x33,
                channel, band,
                lo(gain), hi(gain),
                lo(freq), hi(freq),
                u8(memory, offset + 4), u8(memory, offset + 5), 0x00
        );
    }

    private static byte[] channelNamePayload(int channel, byte[] memory, int offset) {
        byte[] out = new byte[13];
        out[0] = 0x00;
        out[1] = 0x01;
        out[2] = 0x0A;
        out[3] = 0x3D;
        out[4] = (byte) (channel & 0xFF);
        for (int i = 0; i < 8; i++) {
            out[5 + i] = (byte) u8(memory, offset + i);
        }
        return out;
    }

    private static String peqSummary(byte[] memory, int offset) {
        return formatFrequency(rawToFrequencyHz(u16(memory, offset + 2)))
                + " q=" + formatQ(rawToQ(u8(memory, offset + 4)))
                + " g=" + formatDb(peqGainDb(u16(memory, offset)))
                + " type=" + peqType(u8(memory, offset + 5));
    }

    private static String firSummary(byte[] memory, int base) {
        int type = u8(memory, base + 28);
        int window = u8(memory, base + 29);
        int hp = u16(memory, base + 30);
        int lp = u16(memory, base + 32);
        int tapsRaw = u16(memory, base + 34);
        return firType(type)
                + "/" + firWindow(window)
                + " hp=" + formatFrequency(rawToFrequencyHz(hp))
                + " lp=" + formatFrequency(rawToFrequencyHz(lp))
                + " taps=" + (256 + (tapsRaw * 32));
    }

    private static int inputBase(int index) {
        return 16 + (index * 138);
    }

    private static int outputBase(int index) {
        return 568 + (index * 108);
    }

    private static int blockOffset(int block, int payloadOffset) {
        return (block * 50) + Math.max(0, payloadOffset - 5);
    }

    private static int u8(byte[] memory, int offset) {
        if (offset < 0 || offset >= memory.length) {
            throw new IllegalArgumentException("Offset out of range: " + offset);
        }
        return memory[offset] & 0xFF;
    }

    private static int u16(byte[] memory, int offset) {
        return u8(memory, offset) | (u8(memory, offset + 1) << 8);
    }

    static int lo(int value) {
        return value & 0xFF;
    }

    static int hi(int value) {
        return (value >>> 8) & 0xFF;
    }

    static byte[] payload(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) (values[i] & 0xFF);
        }
        return out;
    }

    private static void requireMemory(byte[] memory, int minLength) {
        if (memory == null || memory.length < minLength) {
            throw new IllegalArgumentException("Config memory too short. Expected at least "
                    + minLength + " bytes, got " + (memory == null ? 0 : memory.length));
        }
    }

    private static String ascii(byte[] memory, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int b = u8(memory, offset + i);
            if (b == 0) {
                continue;
            }
            if (b >= 32 && b <= 126) {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static double gainDb(int raw) {
        try { return ParameterKind.GAIN.rawToValue(raw); }
        catch(IllegalArgumentException invalid) { return Double.NaN; }
    }

    private static double peqGainDb(int raw) {
        return (raw / 10.0d) - 12.0d;
    }

    private static double rawToFrequencyHz(int raw) {
        return 19.7d * Math.pow(20160.0d / 19.7d, raw / 300.0d);
    }

    private static double rawToQ(int raw) {
        return 0.4d * Math.pow(128.0d / 0.4d, raw / 100.0d);
    }

    private static String formatDb(double value) {
        if(!Double.isFinite(value))return "unbekannt (ungültiger Rohwert)";
        return String.format(Locale.ROOT, "%+.1fdB", value);
    }

    private static String formatMs(double value) {
        return String.format(Locale.ROOT, "%.2fms", value);
    }

    private static String formatQ(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatFrequency(double hz) {
        if (hz >= 1000.0d) {
            return String.format(Locale.ROOT, "%.2fKHz", hz / 1000.0d);
        }
        return String.format(Locale.ROOT, "%.1fHz", hz);
    }

    private static String peqType(int raw) {
        return switch (raw) {
            case 0 -> "Peak";
            case 1 -> "Low Shelf";
            case 2 -> "High Shelf";
            case 3 -> "LP -6dB";
            case 4 -> "LP -12dB";
            case 5 -> "HP -6dB";
            case 6 -> "HP -12dB";
            case 7 -> "Allpass1";
            case 8 -> "Allpass2";
            default -> "type#" + raw;
        };
    }

    private static String firType(int raw) {
        return switch (raw) {
            case 0 -> "BYPASS";
            case 1 -> "LOW PASS";
            case 2 -> "HIGH PASS";
            case 3 -> "BAND PASS";
            case 4 -> "External FIR";
            default -> "type#" + raw;
        };
    }

    private static String firWindow(int raw) {
        return switch (raw) {
            case 3 -> "HAMMING";
            case 4 -> "BLACKMAN";
            case 5 -> "SINE";
            case 6 -> "SINC";
            case 7 -> "NUTTALL";
            case 8 -> "KAISER";
            default -> "window#" + raw;
        };
    }

    record PingSpec(String name, byte[] payload) {
        PingSpec {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

}
