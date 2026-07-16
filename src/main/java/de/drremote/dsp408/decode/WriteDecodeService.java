package de.drremote.dsp408.decode;

import de.drremote.dsp408.dump.DumpByteReaders;
import de.drremote.dsp408.model.SniffedFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WriteDecodeService {
    private static final double PEQ_GAIN_MIN_DB = -12.0;
    private static final double PEQ_GAIN_STEP_DB = 0.1;
    private static final double FILTER_FREQ_MIN_HZ = 19.7;
    private static final double FILTER_FREQ_MAX_HZ = 20_160.0;
    private static final int FILTER_FREQ_RAW_MAX = 300;
    private static final double PEQ_Q_MIN = 0.40;
    private static final double PEQ_Q_MAX = 128.0;
    private static final int PEQ_Q_RAW_MAX = 100;

    private final DspLibLookup lib;

    public WriteDecodeService(DspLibLookup lib) {
        this.lib = lib;
    }

    public WriteDecodeResult decode(SniffedFrame frame) {
        if (frame == null) {
            return unknown("null", "frame is null");
        }
        if (!frame.isWrite()) {
            return unknown(frame.commandHex(), "not a write");
        }

        byte[] payload = frame.payload();
        Integer command = frame.command();
        if (command == null) {
            return unknown("null", "no command");
        }

        return switch (command) {
            case 0x20 -> decodeRecallPreset(payload, frame.commandHex());
            case 0x21 -> decodeStorePresetSlot(payload, frame.commandHex());
            case 0x26 -> decodeStorePresetName(payload, frame.commandHex());
            case 0x30 -> decodeCompressor(payload, frame.commandHex());
            case 0x31, 0x32 -> decodeCrossoverRawView(payload, frame.commandHex(), command);
            case 0x33 -> decodePeqRawView(payload, frame.commandHex());
            case 0x34 -> decodeGain(payload, frame.commandHex());
            case 0x35 -> decodeMute(payload, frame.commandHex());
            case 0x36 -> decodePhase(payload, frame.commandHex());
            case 0x38 -> decodeDelay(payload, frame.commandHex());
            case 0x39 -> decodeTestTone(payload, frame.commandHex());
            case 0x3A -> decodeMatrixRouting(payload, frame.commandHex());
            case 0x3D -> decodeChannelName(payload, frame.commandHex());
            case 0x3E -> decodeGate(payload, frame.commandHex());
            case 0x3F -> decodeLimiter(payload, frame.commandHex());
            case 0x41 -> decodeMatrixGain(payload, frame.commandHex());
            case 0x48 -> decodeGeqInput(payload, frame.commandHex());
            case 0x4B -> decodeFirGenerator(payload, frame.commandHex());
            case 0x4C -> decodeFirProcessingMode(payload, frame.commandHex());
            case 0x4E -> decodeExternalFirChunk(payload, frame.commandHex());
            case 0x4F -> decodeExternalFirTransferControl(payload, frame.commandHex());
            case 0x5B -> decodeExternalFirName(payload, frame.commandHex());
            default -> unknown(frame.commandHex(), "payload=" + frame.payloadHex());
        };
    }

    private WriteDecodeResult decodeRecallPreset(byte[] payload, String commandHex) {
        requireLen(payload, 5, "recall preset");
        int slot = u8(payload, 4);

        return match(commandHex, "$.parameters.preset_management.write.load",
                "Recall preset",
                List.of(
                        "slot     = " + lib.presetSlot(slot),
                        "slot_raw = 0x%02X".formatted(slot)
                ));
    }

    private WriteDecodeResult decodeStorePresetSlot(byte[] payload, String commandHex) {
        requireLen(payload, 5, "store preset slot");
        int slot = u8(payload, 4);

        return match(commandHex, "$.parameters.preset_management.write.store_slot",
                "Store preset slot",
                List.of(
                        "slot     = " + lib.presetSlot(slot),
                        "slot_raw = 0x%02X".formatted(slot)
                ));
    }

    private WriteDecodeResult decodeStorePresetName(byte[] payload, String commandHex) {
        requireLen(payload, 18, "store preset name");
        String name = asciiZeroTrim(payload, 4, 14).stripTrailing();

        return match(commandHex, "$.parameters.preset_management.write.store_name",
                "Store preset name",
                List.of(
                        "name = " + name
                ));
    }

    private WriteDecodeResult decodeFirGenerator(byte[] payload, String commandHex) {
        requireLen(payload, 13, "FIR generator");
        int channel = u8(payload, 4);
        int typeRaw = u8(payload, 5);
        int windowRaw = u8(payload, 6);
        int highPassRaw = u16le(payload, 7);
        int lowPassRaw = u16le(payload, 9);
        int tapsRaw = u16le(payload, 11);
        int taps = 256 + (tapsRaw * 32);

        return match(commandHex, "$.parameters.fir_generator.write",
                "FIR generator " + lib.channelName(channel),
                List.of(
                        "channel      = " + lib.channelName(channel),
                        "type_raw     = 0x%02X -> %s".formatted(typeRaw, firTypeName(typeRaw)),
                        "window_raw   = 0x%02X -> %s".formatted(windowRaw, firWindowName(windowRaw)),
                        "highpass     = " + formatFrequency(filterFrequencyHz(highPassRaw)) + " (raw " + highPassRaw + ")",
                        "lowpass      = " + formatFrequency(filterFrequencyHz(lowPassRaw)) + " (raw " + lowPassRaw + ")",
                        "taps         = " + taps + " (raw " + tapsRaw + ")"
                ));
    }

    private WriteDecodeResult decodeExternalFirChunk(byte[] payload, String commandHex) {
        requireLen(payload, 8, "External FIR chunk");
        int selector = u8(payload, 4);
        int chunkIndex = u8(payload, 5);
        int tapCount = u16le(payload, 6);
        int dataBytes = Math.max(0, payload.length - 8);
        String firstFloat = dataBytes >= 4 ? formatFloat(f32be(payload, 8)) : "n/a";

        return match(commandHex, "$.parameters.external_fir_upload.write",
                "External FIR chunk",
                List.of(
                        "selector    = " + selector,
                        "chunk_index = " + chunkIndex,
                        "tap_count   = " + tapCount,
                        "data_bytes  = " + dataBytes,
                        "first_f32be = " + firstFloat,
                        "data_prefix = " + hexBytes(payload, 8, Math.min(16, dataBytes))
                ));
    }

    private WriteDecodeResult decodeExternalFirTransferControl(byte[] payload, String commandHex) {
        requireLen(payload, 6, "External FIR transfer control");
        int selector = u8(payload, 4);
        int value = u8(payload, 5);

        return match(commandHex, "$.parameters.external_fir_upload.write",
                "External FIR transfer control",
                List.of(
                        "selector = " + selector,
                        "value    = " + value
                ));
    }

    private WriteDecodeResult decodeExternalFirName(byte[] payload, String commandHex) {
        requireLen(payload, 5, "External FIR name");
        int selector = u8(payload, 4);
        int len = Math.min(8, Math.max(0, payload.length - 5));
        String name = asciiZeroTrim(payload, 5, len);

        return match(commandHex, "$.parameters.external_fir_upload.write",
                "External FIR name",
                List.of(
                        "selector = " + selector,
                        "name     = " + name
                ));
    }

    private WriteDecodeResult decodeFirProcessingMode(byte[] payload, String commandHex) {
        requireLen(payload, 6, "FIR processing mode");
        int channel = u8(payload, 4);
        int mode = u8(payload, 5);
        String modeName = switch (mode) {
            case 0 -> "IIR";
            case 1 -> "FIR";
            default -> "unknown";
        };

        return match(commandHex, "$.parameters.fir_processing_mode.write",
                "FIR mode " + lib.channelName(channel),
                List.of(
                        "channel = " + lib.channelName(channel),
                        "mode    = " + modeName,
                        "raw     = " + mode
                ));
    }

    private WriteDecodeResult decodeMute(byte[] payload, String commandHex) {
        requireLen(payload, 6, "mute");
        int channel = u8(payload, 4);
        int state = u8(payload, 5);
        return match(commandHex, "$.parameters.mute.write",
                "Mute " + lib.channelName(channel),
                List.of(
                        "channel = " + lib.channelName(channel),
                        "state   = " + (state == 0 ? "unmute" : "mute"),
                        "raw     = " + state
                ));
    }

    private WriteDecodeResult decodePhase(byte[] payload, String commandHex) {
        requireLen(payload, 6, "phase");
        int channel = u8(payload, 4);
        int state = u8(payload, 5);
        return match(commandHex, "$.parameters.phase.write",
                "Phase " + lib.channelName(channel),
                List.of(
                        "channel = " + lib.channelName(channel),
                        "state   = " + (state == 0 ? "0" : "180"),
                        "raw     = " + state
                ));
    }

    private WriteDecodeResult decodeDelay(byte[] payload, String commandHex) {
        requireLen(payload, 7, "delay");
        int channel = u8(payload, 4);
        int raw = u16le(payload, 5);
        double ms = raw / 96.0;

        return match(commandHex, "$.parameters.delay.write",
                "Delay " + lib.channelName(channel),
                List.of(
                        "channel = " + lib.channelName(channel),
                        "raw     = " + raw,
                        "ms      = " + format1(ms)
                ));
    }

    private WriteDecodeResult decodeTestTone(byte[] payload, String commandHex) {
        requireLen(payload, 6, "test tone");
        int source = u8(payload, 4);
        int frequency = u8(payload, 5);

        return match(commandHex, "$.parameters.test_tone_generator.write",
                "Test tone",
                List.of(
                        "source      = " + lib.testToneSource(source),
                        "source_raw  = " + source,
                        "freq_sel    = " + lib.testToneFrequency(frequency),
                        "freq_raw    = " + frequency
                ));
    }

    private WriteDecodeResult decodeChannelName(byte[] payload, String commandHex) {
        requireLen(payload, 13, "channel name");
        int channel = u8(payload, 4);
        String name = asciiZeroTrim(payload, 5, 8);

        return match(commandHex, "$.parameters.channel_names.write",
                "Channel name " + lib.channelName(channel),
                List.of(
                        "channel = " + lib.channelName(channel),
                        "name    = " + name
                ));
    }

    private WriteDecodeResult decodeMatrixRouting(byte[] payload, String commandHex) {
        requireLen(payload, 6, "matrix routing");
        int output = u8(payload, 4);
        int mask = u8(payload, 5);
        String inputs = lib.inputNamesFromMask(mask);

        return match(commandHex, "$.parameters.matrix_routing.write",
                "Matrix routing " + lib.outputName(output),
                List.of(
                        "output     = " + lib.outputName(output),
                        "input_mask = 0x%02X".formatted(mask),
                        "inputs     = " + inputs
                ));
    }

    private WriteDecodeResult decodeGeqInput(byte[] payload, String commandHex) {
        requireLen(payload, 8, "geq");
        int input = u8(payload, 4);
        int band = u8(payload, 5);
        int raw = u16le(payload, 6);
        double db = (raw / 10.0) - 12.0;

        return match(commandHex, "$.parameters.geq_input.write",
                "GEQ " + lib.inputName(input) + " / " + lib.geqBandName(band),
                List.of(
                        "channel = " + lib.inputName(input),
                        "band    = " + lib.geqBandName(band) + " (#" + band + ")",
                        "raw     = " + raw,
                        "db      = " + format1(db)
                ));
    }

    private WriteDecodeResult decodeGate(byte[] payload, String commandHex) {
        requireLen(payload, 13, "gate");
        int input = u8(payload, 4);
        int attack = u16le(payload, 5);
        int release = u16le(payload, 7);
        int hold = u16le(payload, 9);
        int threshold = u16le(payload, 11);

        return match(commandHex, "$.parameters.gate.write",
                "Gate " + lib.inputName(input),
                List.of(
                        "channel       = " + lib.inputName(input),
                        "attack_raw    = " + attack + " -> " + (attack + 1) + " ms",
                        "release_raw   = " + release + " -> " + (release + 1) + " ms",
                        "hold_raw      = " + hold + " -> " + (hold + 1) + " ms",
                        "threshold_raw = " + threshold + " -> " + format1((threshold / 2.0) - 90.0) + " dB"
                ));
    }

    private WriteDecodeResult decodeCompressor(byte[] payload, String commandHex) {
        requireLen(payload, 15, "compressor");
        int channel = u8(payload, 4);
        if (channel <= 0x03 && payload.length >= 19) {
            return decodeInputCompressor(payload, commandHex);
        }
        return decodeOutputCompressor(payload, commandHex);
    }

    private WriteDecodeResult decodeOutputCompressor(byte[] payload, String commandHex) {
        requireLen(payload, 15, "compressor");
        int output = u8(payload, 4);
        int ratio = u16le(payload, 5);
        int attack = u16le(payload, 7);
        int release = u16le(payload, 9);
        int knee = u16le(payload, 11);
        int threshold = u16le(payload, 13);
        List<String> details = new ArrayList<>(List.of(
                "channel       = " + lib.outputName(output),
                "ratio_raw     = " + ratio + " -> " + lib.compressorRatio(ratio),
                "attack_raw    = " + attack + " -> " + (attack + 1) + " ms",
                "release_raw   = " + release + " -> " + (release + 1) + " ms",
                "knee_raw      = " + knee + " -> " + knee + " dB",
                "threshold_raw = " + threshold + " -> " + format1((threshold / 2.0) - 90.0) + " dB"
        ));
        if (payload.length > 15) {
            details.add("extra_tail    = " + hexBytes(payload, 15, payload.length - 15));
        }

        return match(commandHex, "$.parameters.compressor.write",
                "Compressor " + lib.outputName(output),
                details);
    }

    private WriteDecodeResult decodeInputCompressor(byte[] payload, String commandHex) {
        requireLen(payload, 19, "input compressor");
        int input = u8(payload, 4);
        int ratio = u16le(payload, 5);
        int attack = u16le(payload, 7);
        int release = u16le(payload, 9);
        int knee = u16le(payload, 11);
        int threshold = u16le(payload, 13);
        int frequency = u16le(payload, 15);
        int typeContext = u8(payload, 17);
        int type = u8(payload, 18);

        return match(commandHex, "$.parameters.input_compressor.write",
                "Input compressor " + lib.inputName(input),
                List.of(
                        "channel       = " + lib.inputName(input),
                        "ratio_raw     = " + ratio + " -> " + lib.compressorRatio(ratio),
                        "attack_raw    = " + attack + " -> " + (attack + 1) + " ms",
                        "release_raw   = " + release + " -> " + (release + 1) + " ms",
                        "knee_raw      = " + knee + " -> " + knee + " dB",
                        "threshold_raw = " + threshold + " -> " + format1((threshold / 2.0) - 90.0) + " dB",
                        "frequency     = " + formatFrequency(filterFrequencyHz(frequency)) + " (raw " + frequency + ")",
                        "type_raw      = " + type + " -> " + lib.inputCompressorType(type),
                        "type_context  = 0x%02X".formatted(typeContext)
                ));
    }

    private WriteDecodeResult decodeLimiter(byte[] payload, String commandHex) {
        requireLen(payload, 15, "limiter");
        int output = u8(payload, 4);
        int ratio = u16le(payload, 5);
        int attack = u16le(payload, 7);
        int release = u16le(payload, 9);
        int knee = u16le(payload, 11);
        int threshold = u16le(payload, 13);
        List<String> details = new ArrayList<>(List.of(
                "channel       = " + lib.outputName(output),
                "ratio_raw     = " + ratio + " -> " + lib.compressorRatio(ratio),
                "attack_raw    = " + attack + " -> " + (attack + 1) + " ms",
                "release_raw   = " + release + " -> " + (release + 1) + " ms",
                "knee_raw      = " + knee + " -> " + knee + " dB",
                "threshold_raw = " + threshold + " -> " + format1((threshold / 2.0) - 90.0) + " dB"
        ));
        if (u8(payload, 6) != 0 || u8(payload, 12) != 0) {
            details.add("pad_warning   = expected ratio/knee high bytes at offsets 6/12 to be 00");
        }
        if (payload.length > 15) {
            details.add("extra_tail    = " + hexBytes(payload, 15, payload.length - 15));
        }

        return match(commandHex, "$.parameters.limiter.write",
                "Limiter " + lib.outputName(output),
                details);
    }

    private WriteDecodeResult decodeGain(byte[] payload, String commandHex) {
        requireLen(payload, 7, "gain");
        int channel = u8(payload, 4);
        int raw = u16le(payload, 5);
        double db = lib.gainDb(raw);

        return match(commandHex, "$.parameters.gain.write",
                "Gain " + lib.channelName(channel),
                List.of(
                        "channel = " + lib.channelName(channel),
                        "gain    = " + format1(db) + " dB (raw " + raw + ")",
                        "model   = piecewise gain mapping from DspLib anchors"
                ));
    }

    private WriteDecodeResult decodeMatrixGain(byte[] payload, String commandHex) {
        requireLen(payload, 8, "matrix gain");
        int output = u8(payload, 4);
        int input = u8(payload, 5);
        int raw = u16le(payload, 6);
        double db = lib.matrixCrosspointGainDb(raw);

        return match(commandHex, "$.parameters.matrix_crosspoint_gain.write",
                "Matrix gain " + lib.outputName(output) + " <- " + lib.inputName(input),
                List.of(
                        "output  = " + lib.outputName(output),
                        "input   = " + lib.inputName(input),
                        "gain    = " + format1(db) + " dB (raw " + raw + ")",
                        "model   = piecewise gain mapping from observed matrix anchors"
                ));
    }

    private WriteDecodeResult decodePeqRawView(byte[] payload, String commandHex) {
        requireLen(payload, 13, "peq");
        int channel = u8(payload, 4);
        int band = u8(payload, 5);
        int gainRaw = u16le(payload, 6);
        int frequencyRaw = u16le(payload, 8);
        int qRaw = u8(payload, 10);
        int typeRaw = u8(payload, 11);
        int tailRaw = u8(payload, 12);
        double gainDb = peqGainDb(gainRaw);
        double frequencyHz = filterFrequencyHz(frequencyRaw);
        double qValue = peqQ(qRaw);

        boolean inputChannel = channel <= 0x03;
        String path = inputChannel ? "$.parameters.peq_input.write" : "$.parameters.peq_output.write";
        String who = inputChannel ? lib.inputName(channel) : lib.outputName(channel);

        return match(commandHex, path,
                "PEQ " + who + " / band " + (band + 1),
                List.of(
                        "channel   = " + who,
                        "band      = " + (band + 1),
                        "gain      = " + format1(gainDb) + " dB (raw " + gainRaw + ")",
                        "freq      = " + formatFrequency(frequencyHz) + " (raw " + frequencyRaw + ")",
                        "q         = " + formatQ(qValue) + " (raw " + qRaw + ")",
                        "type_raw  = " + typeRaw + " -> " + lib.peqType(typeRaw),
                        "bypass    = " + (tailRaw != 0 ? "on" : "off") + " (raw " + tailRaw + ")"
                ));
    }

    private WriteDecodeResult decodeCrossoverRawView(byte[] payload, String commandHex, int command) {
        requireLen(payload, 8, "crossover");
        int channel = u8(payload, 4);
        int valueRaw = u16le(payload, 5);
        int modeRaw = u8(payload, 7);
        double frequencyHz = filterFrequencyHz(valueRaw);
        boolean inputChannel = channel <= 0x03;

        String which = command == 0x31 ? "LowPass" : "HighPass";
        List<String> details = new ArrayList<>();
        details.add("channel   = " + lib.channelName(channel));
        details.add("freq      = " + formatFrequency(frequencyHz) + " (raw " + valueRaw + ")");
        details.addAll(decodeCrossoverMode(which, inputChannel, channel, modeRaw));

        return match(commandHex, "$.parameters.crossover.write",
                which + " " + lib.channelName(channel),
                details);
    }

    private WriteDecodeResult match(String commandHex, String path, String headline, List<String> details) {
        return new WriteDecodeResult(
                true,
                commandHex,
                path,
                lib.statusAt(pointerOf(path)),
                headline,
                details
        );
    }

    private WriteDecodeResult unknown(String commandHex, String text) {
        return new WriteDecodeResult(false, commandHex, null, null, text, List.of());
    }

    private static String pointerOf(String path) {
        return path.replace("$", "").replace(".", "/");
    }

    private static void requireLen(byte[] payload, int len, String label) {
        if (payload == null || payload.length < len) {
            throw new IllegalArgumentException(label + " payload too short");
        }
    }

    private static int u8(byte[] payload, int offset) {
        return DumpByteReaders.u8(payload, offset);
    }

    private static int u16le(byte[] payload, int offset) {
        return DumpByteReaders.u16le(payload, offset);
    }

    private static String asciiZeroTrim(byte[] payload, int offset, int len) {
        return DumpByteReaders.ascii(payload, offset, len, true);
    }

    private static String format1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatFloat(float value) {
        return String.format(Locale.US, "%.8f", value);
    }

    private static double peqGainDb(int raw) {
        return (raw * PEQ_GAIN_STEP_DB) + PEQ_GAIN_MIN_DB;
    }

    private static double peqQ(int raw) {
        return expMap(raw, 0, PEQ_Q_RAW_MAX, PEQ_Q_MIN, PEQ_Q_MAX);
    }

    private static double filterFrequencyHz(int raw) {
        return expMap(raw, 0, FILTER_FREQ_RAW_MAX, FILTER_FREQ_MIN_HZ, FILTER_FREQ_MAX_HZ);
    }

    private static double expMap(int raw, int rawMin, int rawMax, double valueMin, double valueMax) {
        int clamped = Math.max(rawMin, Math.min(rawMax, raw));
        if (rawMax <= rawMin || valueMin <= 0.0 || valueMax <= 0.0) {
            return valueMin;
        }

        double fraction = (clamped - rawMin) / (double) (rawMax - rawMin);
        double ratio = valueMax / valueMin;
        return valueMin * Math.pow(ratio, fraction);
    }

    private static String formatQ(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatFrequency(double hz) {
        if (hz >= 1000.0) {
            double khz = hz / 1000.0;
            return String.format(Locale.US, "%.2f kHz", khz);
        }
        return String.format(Locale.US, "%.1f Hz", hz);
    }

    private static String firTypeName(int raw) {
        return switch (raw) {
            case 0 -> "BYPASS";
            case 1 -> "LOW PASS";
            case 2 -> "HIGH PASS";
            case 3 -> "BAND PASS";
            case 4 -> "External FIR";
            default -> "type#" + raw;
        };
    }

    private static String firWindowName(int raw) {
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

    private static float f32be(byte[] payload, int offset) {
        int bits = (u8(payload, offset) << 24)
                | (u8(payload, offset + 1) << 16)
                | (u8(payload, offset + 2) << 8)
                | u8(payload, offset + 3);
        return Float.intBitsToFloat(bits);
    }

    private static String hexBytes(byte[] payload, int offset, int len) {
        int safeOffset = Math.max(0, Math.min(offset, payload.length));
        int safeEnd = Math.max(safeOffset, Math.min(payload.length, safeOffset + Math.max(0, len)));
        List<String> out = new ArrayList<>();
        for (int i = safeOffset; i < safeEnd; i++) {
            out.add("%02X".formatted(u8(payload, i)));
        }
        return String.join(" ", out);
    }

    private List<String> decodeCrossoverMode(String which, boolean inputChannel, int channel, int modeRaw) {
        List<String> details = new ArrayList<>();
        details.add("mode_raw  = 0x%02X".formatted(modeRaw));

        if (modeRaw == 0x00) {
            details.add("bypass   = on");
            details.add("mode     = " + roleLabel(inputChannel) + " " + which + " bypass");
        } else {
            details.add("bypass   = off");
            details.add("slope    = " + lib.crossoverSlopeByRaw(modeRaw));
            details.add("mode     = " + roleLabel(inputChannel) + " " + which + " slope/family");
        }
        details.add("rule     = inputs InA..InD and outputs Out1..Out8 use 0x00 for bypass and 0x01..0x14 for crossover_slopes");

        return details;
    }

    private static String roleLabel(boolean inputChannel) {
        return inputChannel ? "input" : "output";
    }
}
