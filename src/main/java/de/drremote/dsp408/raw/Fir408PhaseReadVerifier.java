package de.drremote.dsp408.raw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct FIR408 phase readback verifier.
 *
 * <p>The verifier is deliberately conservative: it forces every channel to
 * mute before testing, restores each tested phase immediately, and repeats
 * the all-channel phase-normal/mute safety anchor in a finally block.</p>
 */
public final class Fir408PhaseReadVerifier {

    private static final byte[] ACK_PAYLOAD = RawFrameCodec.hex("01 00 01 01");
    private static final List<ChannelSpec> CHANNELS = List.of(
            new ChannelSpec("InA", 0x00, 0x03, 5),
            new ChannelSpec("InB", 0x01, 0x05, 45),
            new ChannelSpec("InC", 0x02, 0x08, 35),
            new ChannelSpec("InD", 0x03, 0x0B, 25),
            new ChannelSpec("Out1", 0x04, 0x0D, 29),
            new ChannelSpec("Out2", 0x05, 0x0F, 33),
            new ChannelSpec("Out3", 0x06, 0x11, 37),
            new ChannelSpec("Out4", 0x07, 0x13, 41),
            new ChannelSpec("Out5", 0x08, 0x15, 45),
            new ChannelSpec("Out6", 0x09, 0x17, 49),
            new ChannelSpec("Out7", 0x0A, 0x19, 53),
            new ChannelSpec("Out8", 0x0B, 0x1C, 7)
    );

    private Fir408PhaseReadVerifier() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (options.help) {
            printUsage();
            return;
        }

        try {
            VerificationReport report = verify(options);
            System.out.println();
            System.out.println("FIR408 phase readback verified: " + report.allChannelsVerified);
            System.out.println("Full config restored byte-for-byte: " + report.fullConfigRestored);
            System.out.println("Report: " + options.outDir.resolve("summary.md").toAbsolutePath());
            if (!report.allChannelsVerified || !report.fullConfigRestored) {
                System.exit(1);
            }
        } catch (Exception ex) {
            System.err.println("ERROR: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static VerificationReport verify(Options options) throws Exception {
        Files.createDirectories(options.outDir);
        List<Map<String, Object>> channelResults = new ArrayList<>();
        Map<Integer, byte[]> fullBefore = Map.of();
        Map<Integer, byte[]> fullAfter = Map.of();
        Exception primaryFailure = null;

        try (RawSocketClient client = new RawSocketClient(
                options.host,
                options.port,
                options.connectTimeoutMs,
                options.readTimeoutMs,
                options.verbose)) {

            client.connect();
            handshake(client);
            forceSafeState(client, options.settleMs);
            fullBefore = readFullConfig(client, options.settleMs);
            saveConfig(options.outDir.resolve("baseline"), fullBefore);

            for (ChannelSpec channel : CHANNELS) {
                Map<String, Object> result = verifyChannel(client, channel, options);
                channelResults.add(result);
            }

            fullAfter = readFullConfig(client, options.settleMs);
            saveConfig(options.outDir.resolve("restored"), fullAfter);
        } catch (Exception ex) {
            primaryFailure = ex;
            throw ex;
        } finally {
            try (RawSocketClient restoreClient = new RawSocketClient(
                    options.host,
                    options.port,
                    options.connectTimeoutMs,
                    options.readTimeoutMs,
                    options.verbose)) {
                restoreClient.connect();
                handshake(restoreClient);
                forceSafeState(restoreClient, options.settleMs);
            } catch (Exception restoreFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure);
                } else {
                    throw new IllegalStateException(
                            "Verification completed, but the final phase-normal/mute safety restore failed.",
                            restoreFailure);
                }
            }
        }

        boolean allChannelsVerified = channelResults.stream()
                .allMatch(result -> Boolean.TRUE.equals(result.get("verified")));
        List<String> fullConfigDiffs = configDiffs(fullBefore, fullAfter);
        boolean fullConfigRestored = fullConfigDiffs.isEmpty();

        VerificationReport report = new VerificationReport(
                OffsetDateTime.now().toString(),
                options.host,
                options.port,
                allChannelsVerified,
                fullConfigRestored,
                fullConfigDiffs,
                channelResults
        );
        saveReport(options.outDir, report);
        return report;
    }

    private static Map<String, Object> verifyChannel(
            RawSocketClient client,
            ChannelSpec channel,
            Options options) throws Exception {

        System.out.printf(
                "Verify %s: channel=0x%02X (legacy candidate block=0x%02X offset=%d)%n",
                channel.name,
                channel.channelIndex,
                channel.blockIndex,
                channel.payloadOffset);

        Map<Integer, byte[]> normal;
        Map<Integer, byte[]> inverted;
        Map<Integer, byte[]> restored;
        try {
            writePhase(client, channel.channelIndex, false);
            sleep(options.settleMs);
            normal = readFullConfig(client, 0);

            writePhase(client, channel.channelIndex, true);
            sleep(options.settleMs);
            inverted = readFullConfig(client, 0);
        } finally {
            writePhase(client, channel.channelIndex, false);
            sleep(options.settleMs);
        }
        restored = readFullConfig(client, 0);

        List<Map<String, Object>> changes = configChanges(normal, inverted);
        Map<String, Object> onlyChange = changes.size() == 1 ? changes.getFirst() : Map.of();
        Integer observedBlock = integerValue(onlyChange.get("block"));
        Integer observedOffset = integerValue(onlyChange.get("offset"));
        Integer beforeByte = integerValue(onlyChange.get("before"));
        Integer afterByte = integerValue(onlyChange.get("after"));

        boolean singleByteChange = changes.size() == 1;
        boolean rawByteMappingConfirmed = singleByteChange && beforeByte == 0 && afterByte == 1;
        int normalRaw = observedBlock == null || observedOffset == null
                ? -1
                : u16le(normal.get(observedBlock), observedOffset);
        int invertedRaw = observedBlock == null || observedOffset == null
                ? -1
                : u16le(inverted.get(observedBlock), observedOffset);
        int restoredRaw = observedBlock == null || observedOffset == null
                ? -1
                : u16le(restored.get(observedBlock), observedOffset);
        boolean legacyCandidateMatches = observedBlock != null
                && observedOffset != null
                && observedBlock == channel.blockIndex
                && observedOffset == channel.payloadOffset;
        List<String> restoreDiffs = configDiffs(normal, restored);
        boolean restoredConfigMatches = restoreDiffs.isEmpty();
        boolean verified = normalRaw == 0
                && invertedRaw == 1
                && restoredRaw == 0
                && rawByteMappingConfirmed
                && restoredConfigMatches;

        Path channelDir = options.outDir.resolve(channel.name.toLowerCase());
        Files.createDirectories(channelDir);
        if (observedBlock != null) {
            Files.writeString(
                    channelDir.resolve("normal-block-" + hexByte(observedBlock) + ".hex.txt"),
                    RawFrameCodec.toHex(normal.get(observedBlock)));
            Files.writeString(
                    channelDir.resolve("inverted-block-" + hexByte(observedBlock) + ".hex.txt"),
                    RawFrameCodec.toHex(inverted.get(observedBlock)));
            Files.writeString(
                    channelDir.resolve("restored-block-" + hexByte(observedBlock) + ".hex.txt"),
                    RawFrameCodec.toHex(restored.get(observedBlock)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel.name);
        result.put("channelIndexHex", hexByte(channel.channelIndex));
        result.put("legacyCandidateBlockHex", hexByte(channel.blockIndex));
        result.put("legacyCandidatePayloadOffset", channel.payloadOffset);
        result.put("observedBlock", observedBlock);
        result.put("observedBlockHex", observedBlock == null ? null : hexByte(observedBlock));
        result.put("observedPayloadOffset", observedOffset);
        result.put("normalRawU16le", normalRaw);
        result.put("invertedRawU16le", invertedRaw);
        result.put("restoredRawU16le", restoredRaw);
        result.put("changes", changes);
        result.put("singleByteChange", singleByteChange);
        result.put("rawByteMappingConfirmed", rawByteMappingConfirmed);
        result.put("legacyCandidateMatches", legacyCandidateMatches);
        result.put("restoredConfigMatches", restoredConfigMatches);
        result.put("restoreDiffs", restoreDiffs);
        result.put("verified", verified);
        return result;
    }

    private static void handshake(RawSocketClient client) throws Exception {
        requireResponse(client.sendPayloadAndRead(
                "handshake_init",
                RawFrameCodec.hex("00 01 01 10"),
                1), "handshake_init");
        requireResponse(client.sendPayloadAndRead(
                "device_info",
                RawFrameCodec.hex("00 01 01 13"),
                1), "device_info");
        requireResponse(client.sendPayloadAndRead(
                "system_info",
                RawFrameCodec.hex("00 01 01 2C"),
                1), "system_info");
    }

    private static void forceSafeState(RawSocketClient client, int settleMs) throws Exception {
        for (ChannelSpec channel : CHANNELS) {
            writeMute(client, channel.channelIndex, true);
        }
        for (ChannelSpec channel : CHANNELS) {
            writePhase(client, channel.channelIndex, false);
        }
        sleep(settleMs);
    }

    private static void writeMute(RawSocketClient client, int channelIndex, boolean muted) throws Exception {
        byte[] payload = new byte[]{
                0x00, 0x01, 0x03, 0x35,
                (byte) channelIndex,
                (byte) (muted ? 1 : 0)
        };
        requireAck(client.sendPayloadAndRead("mute-" + hexByte(channelIndex), payload, 1));
    }

    private static void writePhase(RawSocketClient client, int channelIndex, boolean inverted) throws Exception {
        byte[] payload = new byte[]{
                0x00, 0x01, 0x03, 0x36,
                (byte) channelIndex,
                (byte) (inverted ? 1 : 0)
        };
        requireAck(client.sendPayloadAndRead(
                "phase-" + hexByte(channelIndex) + "-" + (inverted ? "180" : "normal"),
                payload,
                1));
    }

    private static Map<Integer, byte[]> readFullConfig(RawSocketClient client, int settleMs) throws Exception {
        Map<Integer, byte[]> blocks = new LinkedHashMap<>();
        for (int block = 0x00; block <= 0x1F; block++) {
            blocks.put(block, readBlock(client, block));
            sleep(Math.min(settleMs, 50));
        }
        return blocks;
    }

    private static byte[] readBlock(RawSocketClient client, int blockIndex) throws Exception {
        byte[] request = new byte[]{
                0x00, 0x01, 0x02, 0x27, (byte) blockIndex
        };
        List<byte[]> frames = client.sendPayloadAndRead("read-block-" + hexByte(blockIndex), request, 1);
        byte[] frame = requireResponse(frames, "read block " + hexByte(blockIndex));
        byte[] payload = RawFrameCodec.extractPayload(frame);
        if (!RawFrameCodec.isValidFrame(frame)) {
            throw new IllegalStateException("Invalid checksum for block " + hexByte(blockIndex));
        }
        if (payload.length < 5 || (payload[3] & 0xFF) != 0x24 || (payload[4] & 0xFF) != blockIndex) {
            throw new IllegalStateException(
                    "Unexpected response for block " + hexByte(blockIndex) + ": "
                            + RawFrameCodec.toHex(payload));
        }
        return payload;
    }

    private static void requireAck(List<byte[]> frames) {
        byte[] frame = requireResponse(frames, "write ACK");
        if (!RawFrameCodec.isValidFrame(frame)) {
            throw new IllegalStateException("Invalid write ACK checksum.");
        }
        byte[] payload = RawFrameCodec.extractPayload(frame);
        if (!Arrays.equals(payload, ACK_PAYLOAD)) {
            throw new IllegalStateException("Unexpected write ACK: " + RawFrameCodec.toHex(payload));
        }
    }

    private static byte[] requireResponse(List<byte[]> frames, String label) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalStateException("No response for " + label);
        }
        return frames.getFirst();
    }

    private static int u16le(byte[] payload, int offset) {
        if (offset < 0 || offset + 1 >= payload.length) {
            throw new IllegalArgumentException(
                    "u16le offset " + offset + " outside payload length " + payload.length);
        }
        return (payload[offset] & 0xFF) | ((payload[offset + 1] & 0xFF) << 8);
    }

    private static List<Integer> changedOffsets(byte[] before, byte[] after) {
        if (before.length != after.length) {
            throw new IllegalArgumentException(
                    "Cannot diff payloads with different lengths: " + before.length + " vs " + after.length);
        }
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                offsets.add(i);
            }
        }
        return offsets;
    }

    private static List<String> configDiffs(Map<Integer, byte[]> before, Map<Integer, byte[]> after) {
        List<String> diffs = new ArrayList<>();
        for (int block = 0x00; block <= 0x1F; block++) {
            byte[] a = before.get(block);
            byte[] b = after.get(block);
            if (a == null || b == null) {
                diffs.add("block " + hexByte(block) + ": missing");
                continue;
            }
            List<Integer> offsets = changedOffsets(a, b);
            if (!offsets.isEmpty()) {
                diffs.add("block " + hexByte(block) + ": offsets " + offsets);
            }
        }
        return diffs;
    }

    private static List<Map<String, Object>> configChanges(
            Map<Integer, byte[]> before,
            Map<Integer, byte[]> after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int block = 0x00; block <= 0x1F; block++) {
            byte[] a = before.get(block);
            byte[] b = after.get(block);
            if (a == null || b == null) {
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("block", block);
                missing.put("blockHex", hexByte(block));
                missing.put("missing", true);
                changes.add(missing);
                continue;
            }
            if (a.length != b.length) {
                Map<String, Object> lengthChange = new LinkedHashMap<>();
                lengthChange.put("block", block);
                lengthChange.put("blockHex", hexByte(block));
                lengthChange.put("beforeLength", a.length);
                lengthChange.put("afterLength", b.length);
                changes.add(lengthChange);
                continue;
            }
            for (int offset = 0; offset < a.length; offset++) {
                if (a[offset] != b[offset]) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("block", block);
                    change.put("blockHex", hexByte(block));
                    change.put("offset", offset);
                    change.put("before", a[offset] & 0xFF);
                    change.put("after", b[offset] & 0xFF);
                    changes.add(change);
                }
            }
        }
        return changes;
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static void saveConfig(Path dir, Map<Integer, byte[]> blocks) throws Exception {
        Files.createDirectories(dir);
        for (Map.Entry<Integer, byte[]> entry : blocks.entrySet()) {
            Files.writeString(
                    dir.resolve("block-" + hexByte(entry.getKey()) + ".hex.txt"),
                    RawFrameCodec.toHex(entry.getValue()));
        }
    }

    private static void saveReport(Path outDir, VerificationReport report) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outDir.resolve("summary.json").toFile(), report);

        StringBuilder md = new StringBuilder();
        md.append("# FIR408 Phase Readback Verification\n\n");
        md.append("- Time: `").append(report.timestamp).append("`\n");
        md.append("- DSP: `").append(report.host).append(':').append(report.port).append("`\n");
        md.append("- All channels verified: **").append(report.allChannelsVerified).append("**\n");
        md.append("- Full config restored byte-for-byte: **").append(report.fullConfigRestored).append("**\n");
        md.append("- Safety restore: all channels phase-normal and muted\n\n");
        md.append("| Channel | Index | Observed block | Offset | 0° | 180° | Restored | Legacy match | Verified |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
        for (Map<String, Object> channel : report.channels) {
            md.append("| ").append(channel.get("channel"))
                    .append(" | `").append(channel.get("channelIndexHex")).append('`')
                    .append(" | `").append(channel.get("observedBlockHex")).append('`')
                    .append(" | ").append(channel.get("observedPayloadOffset"))
                    .append(" | ").append(channel.get("normalRawU16le"))
                    .append(" | ").append(channel.get("invertedRawU16le"))
                    .append(" | ").append(channel.get("restoredRawU16le"))
                    .append(" | ").append(channel.get("legacyCandidateMatches"))
                    .append(" | ").append(channel.get("verified"))
                    .append(" |\n");
        }
        if (!report.fullConfigDiffs.isEmpty()) {
            md.append("\n## Full config differences\n\n");
            for (String diff : report.fullConfigDiffs) {
                md.append("- ").append(diff).append('\n');
            }
        }
        Files.writeString(outDir.resolve("summary.md"), md.toString());
    }

    private static String hexByte(int value) {
        return String.format("%02X", value & 0xFF);
    }

    private static void sleep(int millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static void printUsage() {
        System.out.println("FIR408 Phase Readback Verifier");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp target\\tracks-dsp-script-all.jar "
                + "de.drremote.dsp408.raw.Fir408PhaseReadVerifier");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --host <ip>                 DSP host (default: 192.168.0.166)");
        System.out.println("  --port <port>               DSP port (default: 9761)");
        System.out.println("  --out <directory>           Report directory");
        System.out.println("  --settle-ms <ms>            Delay after writes (default: 150)");
        System.out.println("  --connect-timeout-ms <ms>   Connect timeout (default: 2000)");
        System.out.println("  --read-timeout-ms <ms>      Read timeout (default: 1500)");
        System.out.println("  --verbose                   Print raw receive chunks");
        System.out.println("  --help                      Help");
        System.out.println();
        System.out.println("Safety:");
        System.out.println("  The tool never unmutes. It restores every phase to 0 degrees and");
        System.out.println("  mutes all 12 channels again in a final safety pass.");
    }

    private record ChannelSpec(String name, int channelIndex, int blockIndex, int payloadOffset) {
    }

    private record VerificationReport(
            String timestamp,
            String host,
            int port,
            boolean allChannelsVerified,
            boolean fullConfigRestored,
            List<String> fullConfigDiffs,
            List<Map<String, Object>> channels) {
    }

    private static final class Options {
        private String host = "192.168.0.166";
        private int port = 9761;
        private Path outDir = Path.of("out", "fir", "fir408-phase-readback-verification");
        private int settleMs = 150;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 1500;
        private boolean verbose;
        private boolean help;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--host" -> options.host = requireValue(args, ++i, arg);
                    case "--port" -> options.port = positiveInt(requireValue(args, ++i, arg), arg);
                    case "--out" -> options.outDir = Path.of(requireValue(args, ++i, arg));
                    case "--settle-ms" -> options.settleMs = nonNegativeInt(requireValue(args, ++i, arg), arg);
                    case "--connect-timeout-ms" ->
                            options.connectTimeoutMs = positiveInt(requireValue(args, ++i, arg), arg);
                    case "--read-timeout-ms" ->
                            options.readTimeoutMs = positiveInt(requireValue(args, ++i, arg), arg);
                    case "--verbose" -> options.verbose = true;
                    case "--help", "-h" -> options.help = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static int positiveInt(String value, String option) {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(option + " must be > 0");
            }
            return parsed;
        }

        private static int nonNegativeInt(String value, String option) {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(option + " must be >= 0");
            }
            return parsed;
        }
    }
}
