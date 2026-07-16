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
 * Direct FIR408 input-gate readback verifier for the write-confirmed inputs
 * InA and InD.
 *
 * <p>The verifier reads the current gate tuple from the assembled FIR408
 * configuration, mutes all channels, changes one gate field at a time, reads
 * all configuration blocks, and immediately restores the original tuple.
 * It never sends an unmute command.</p>
 */
public final class Fir408GateReadVerifier {

    private static final byte[] ACK_PAYLOAD = RawFrameCodec.hex("01 00 01 01");
    private static final int PARAMETER_BLOCK_DATA_SIZE = 50;
    private static final int RESPONSE_HEADER_SIZE = 5;

    private static final List<ChannelSpec> ALL_CHANNELS = List.of(
            new ChannelSpec("InA", 0x00, 16),
            new ChannelSpec("InB", 0x01, 154),
            new ChannelSpec("InC", 0x02, 292),
            new ChannelSpec("InD", 0x03, 430),
            new ChannelSpec("Out1", 0x04, -1),
            new ChannelSpec("Out2", 0x05, -1),
            new ChannelSpec("Out3", 0x06, -1),
            new ChannelSpec("Out4", 0x07, -1),
            new ChannelSpec("Out5", 0x08, -1),
            new ChannelSpec("Out6", 0x09, -1),
            new ChannelSpec("Out7", 0x0A, -1),
            new ChannelSpec("Out8", 0x0B, -1)
    );

    private static final List<ChannelSpec> TEST_CHANNELS = List.of(
            ALL_CHANNELS.get(0),
            ALL_CHANNELS.get(3)
    );

    private Fir408GateReadVerifier() {
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
            System.out.println("FIR408 gate readback verified: " + report.allFieldsVerified);
            System.out.println("Full config restored byte-for-byte: " + report.fullConfigRestored);
            System.out.println("Report: " + options.outDir.resolve("summary.md").toAbsolutePath());
            if (!report.allFieldsVerified || !report.fullConfigRestored) {
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

        Map<ChannelSpec, GateRaw> originalGates = new LinkedHashMap<>();
        Map<Integer, byte[]> fullBefore = Map.of();
        Map<Integer, byte[]> fullAfter = Map.of();
        List<Map<String, Object>> fieldResults = new ArrayList<>();
        Exception primaryFailure = null;

        try (RawSocketClient client = new RawSocketClient(
                options.host,
                options.port,
                options.connectTimeoutMs,
                options.readTimeoutMs,
                options.verbose)) {

            client.connect();
            handshake(client);
            muteAll(client);
            sleep(options.settleMs);

            fullBefore = readFullConfig(client, options.settleMs);
            saveConfig(options.outDir.resolve("baseline"), fullBefore);

            for (ChannelSpec channel : TEST_CHANNELS) {
                GateRaw original = readGateFromAssembledConfig(fullBefore, channel);
                requireValidGate(channel, original);
                originalGates.put(channel, original);
                System.out.printf(
                        "%s original gate raw: attack=%d release=%d hold=%d threshold=%d%n",
                        channel.name,
                        original.attack,
                        original.release,
                        original.hold,
                        original.threshold);
            }

            for (ChannelSpec channel : TEST_CHANNELS) {
                GateRaw original = originalGates.get(channel);
                for (GateField field : GateField.values()) {
                    fieldResults.add(verifyField(client, channel, original, field, fullBefore, options));
                }
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
                muteAll(restoreClient);
                for (Map.Entry<ChannelSpec, GateRaw> entry : originalGates.entrySet()) {
                    writeGate(restoreClient, entry.getKey(), entry.getValue(), "final-restore");
                }
                muteAll(restoreClient);
                sleep(options.settleMs);
            } catch (Exception restoreFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure);
                } else {
                    throw new IllegalStateException(
                            "Verification completed, but the final gate/mute safety restore failed.",
                            restoreFailure);
                }
            }
        }

        boolean allFieldsVerified = fieldResults.stream()
                .allMatch(result -> Boolean.TRUE.equals(result.get("verified")));
        List<String> fullConfigDiffs = configDiffs(fullBefore, fullAfter);
        boolean fullConfigRestored = fullConfigDiffs.isEmpty();

        List<Map<String, Object>> originalGateValues = new ArrayList<>();
        for (Map.Entry<ChannelSpec, GateRaw> entry : originalGates.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("channel", entry.getKey().name);
            values.put("channelIndexHex", hexByte(entry.getKey().channelIndex));
            values.put("attackRaw", entry.getValue().attack);
            values.put("releaseRaw", entry.getValue().release);
            values.put("holdRaw", entry.getValue().hold);
            values.put("thresholdRaw", entry.getValue().threshold);
            originalGateValues.add(values);
        }

        VerificationReport report = new VerificationReport(
                OffsetDateTime.now().toString(),
                options.host,
                options.port,
                allFieldsVerified,
                fullConfigRestored,
                fullConfigDiffs,
                originalGateValues,
                fieldResults
        );
        saveReport(options.outDir, report);
        return report;
    }

    private static Map<String, Object> verifyField(
            RawSocketClient client,
            ChannelSpec channel,
            GateRaw original,
            GateField field,
            Map<Integer, byte[]> baseline,
            Options options) throws Exception {

        int baselineRaw = original.value(field);
        int markerRaw = field.markerFor(baselineRaw);
        GateRaw markerGate = original.with(field, markerRaw);
        Map<Integer, byte[]> markerConfig;
        Map<Integer, byte[]> restoredConfig;

        System.out.printf(
                "Verify %s.%s: raw %d -> %d -> %d%n",
                channel.name,
                field.jsonName,
                baselineRaw,
                markerRaw,
                baselineRaw);

        try {
            writeGate(client, channel, markerGate, "marker-" + field.jsonName);
            sleep(options.settleMs);
            markerConfig = readFullConfig(client, 0);
        } finally {
            writeGate(client, channel, original, "restore-" + field.jsonName);
            sleep(options.settleMs);
        }
        restoredConfig = readFullConfig(client, 0);

        List<ByteChange> changes = configChanges(baseline, markerConfig);
        int expectedChangedByteCount = changedByteCount(baselineRaw, markerRaw);
        ObservedLocation observed = observedLocation(changes);

        int markerObservedRaw = observed == null
                ? -1
                : u16le(markerConfig.get(observed.blockIndex), observed.payloadOffset);
        int baselineObservedRaw = observed == null
                ? -1
                : u16le(baseline.get(observed.blockIndex), observed.payloadOffset);
        int restoredObservedRaw = observed == null
                ? -1
                : u16le(restoredConfig.get(observed.blockIndex), observed.payloadOffset);

        FieldLocation expected = expectedLocation(channel, field);
        boolean exactChangeShape = observed != null
                && changes.size() == expectedChangedByteCount
                && changes.stream().allMatch(change -> change.blockIndex == observed.blockIndex)
                && changes.stream().allMatch(change ->
                        change.payloadOffset == observed.payloadOffset
                                || change.payloadOffset == observed.payloadOffset + 1);
        boolean rawMappingConfirmed = baselineObservedRaw == baselineRaw
                && markerObservedRaw == markerRaw
                && restoredObservedRaw == baselineRaw;
        boolean expectedLocationMatches = observed != null
                && observed.blockIndex == expected.blockIndex
                && observed.payloadOffset == expected.payloadOffset;
        List<String> restoreDiffs = configDiffs(baseline, restoredConfig);
        boolean restoredConfigMatches = restoreDiffs.isEmpty();
        boolean verified = exactChangeShape
                && rawMappingConfirmed
                && expectedLocationMatches
                && restoredConfigMatches;

        Path fieldDir = options.outDir
                .resolve(channel.name.toLowerCase())
                .resolve(field.jsonName);
        Files.createDirectories(fieldDir);
        if (observed != null) {
            Files.writeString(
                    fieldDir.resolve("baseline-block-" + hexByte(observed.blockIndex) + ".hex.txt"),
                    RawFrameCodec.toHex(baseline.get(observed.blockIndex)));
            Files.writeString(
                    fieldDir.resolve("marker-block-" + hexByte(observed.blockIndex) + ".hex.txt"),
                    RawFrameCodec.toHex(markerConfig.get(observed.blockIndex)));
            Files.writeString(
                    fieldDir.resolve("restored-block-" + hexByte(observed.blockIndex) + ".hex.txt"),
                    RawFrameCodec.toHex(restoredConfig.get(observed.blockIndex)));
        }

        List<Map<String, Object>> changeMaps = new ArrayList<>();
        for (ByteChange change : changes) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("block", change.blockIndex);
            value.put("blockHex", hexByte(change.blockIndex));
            value.put("payloadOffset", change.payloadOffset);
            value.put("dataOffset", change.payloadOffset - RESPONSE_HEADER_SIZE);
            value.put("before", change.before);
            value.put("after", change.after);
            changeMaps.add(value);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel.name);
        result.put("channelIndexHex", hexByte(channel.channelIndex));
        result.put("field", field.jsonName);
        result.put("baselineRaw", baselineRaw);
        result.put("markerRaw", markerRaw);
        result.put("restoredRaw", restoredObservedRaw);
        result.put("expectedBlockHex", hexByte(expected.blockIndex));
        result.put("expectedPayloadOffset", expected.payloadOffset);
        result.put("expectedDataOffset", expected.payloadOffset - RESPONSE_HEADER_SIZE);
        result.put("observedBlock", observed == null ? null : observed.blockIndex);
        result.put("observedBlockHex", observed == null ? null : hexByte(observed.blockIndex));
        result.put("observedPayloadOffset", observed == null ? null : observed.payloadOffset);
        result.put(
                "observedDataOffset",
                observed == null ? null : observed.payloadOffset - RESPONSE_HEADER_SIZE);
        result.put("baselineObservedRawU16le", baselineObservedRaw);
        result.put("markerObservedRawU16le", markerObservedRaw);
        result.put("restoredObservedRawU16le", restoredObservedRaw);
        result.put("changes", changeMaps);
        result.put("expectedChangedByteCount", expectedChangedByteCount);
        result.put("exactChangeShape", exactChangeShape);
        result.put("rawMappingConfirmed", rawMappingConfirmed);
        result.put("expectedLocationMatches", expectedLocationMatches);
        result.put("restoredConfigMatches", restoredConfigMatches);
        result.put("restoreDiffs", restoreDiffs);
        result.put("verified", verified);
        return result;
    }

    private static GateRaw readGateFromAssembledConfig(
            Map<Integer, byte[]> config,
            ChannelSpec channel) {
        return new GateRaw(
                readMemoryU16(config, channel.inputBase + 8),
                readMemoryU16(config, channel.inputBase + 10),
                readMemoryU16(config, channel.inputBase + 12),
                readMemoryU16(config, channel.inputBase + 14)
        );
    }

    private static int readMemoryU16(Map<Integer, byte[]> config, int memoryOffset) {
        FieldLocation location = memoryLocation(memoryOffset);
        return u16le(config.get(location.blockIndex), location.payloadOffset);
    }

    private static FieldLocation expectedLocation(ChannelSpec channel, GateField field) {
        return memoryLocation(channel.inputBase + 8 + (field.ordinal() * 2));
    }

    private static FieldLocation memoryLocation(int memoryOffset) {
        int blockIndex = memoryOffset / PARAMETER_BLOCK_DATA_SIZE;
        int dataOffset = memoryOffset % PARAMETER_BLOCK_DATA_SIZE;
        return new FieldLocation(blockIndex, dataOffset + RESPONSE_HEADER_SIZE);
    }

    private static void requireValidGate(ChannelSpec channel, GateRaw gate) {
        requireRange(channel, "attack", gate.attack, 0, 998);
        requireRange(channel, "release", gate.release, 9, 2999);
        requireRange(channel, "hold", gate.hold, 9, 998);
        requireRange(channel, "threshold", gate.threshold, 0, 180);
    }

    private static void requireRange(
            ChannelSpec channel,
            String field,
            int value,
            int min,
            int max) {
        if (value < min || value > max) {
            throw new IllegalStateException(
                    channel.name + " " + field + " raw " + value
                            + " is outside the valid range " + min + ".." + max
                            + "; refusing to write any gate marker.");
        }
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

    private static void muteAll(RawSocketClient client) throws Exception {
        for (ChannelSpec channel : ALL_CHANNELS) {
            byte[] payload = new byte[]{
                    0x00, 0x01, 0x03, 0x35,
                    (byte) channel.channelIndex,
                    0x01
            };
            requireAck(client.sendPayloadAndRead(
                    "mute-" + channel.name.toLowerCase(),
                    payload,
                    1));
        }
    }

    private static void writeGate(
            RawSocketClient client,
            ChannelSpec channel,
            GateRaw gate,
            String label) throws Exception {
        byte[] payload = new byte[]{
                0x00, 0x01, 0x0A, 0x3E,
                (byte) channel.channelIndex,
                low(gate.attack), high(gate.attack),
                low(gate.release), high(gate.release),
                low(gate.hold), high(gate.hold),
                low(gate.threshold), high(gate.threshold)
        };
        requireAck(client.sendPayloadAndRead(
                channel.name.toLowerCase() + "-" + label,
                payload,
                1));
    }

    private static byte low(int value) {
        return (byte) (value & 0xFF);
    }

    private static byte high(int value) {
        return (byte) ((value >>> 8) & 0xFF);
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
        if (payload.length < RESPONSE_HEADER_SIZE
                || (payload[3] & 0xFF) != 0x24
                || (payload[4] & 0xFF) != blockIndex) {
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
        if (payload == null || offset < 0 || offset + 1 >= payload.length) {
            throw new IllegalArgumentException(
                    "u16le offset " + offset + " outside payload");
        }
        return (payload[offset] & 0xFF) | ((payload[offset + 1] & 0xFF) << 8);
    }

    private static int changedByteCount(int before, int after) {
        int count = 0;
        if ((before & 0xFF) != (after & 0xFF)) {
            count++;
        }
        if (((before >>> 8) & 0xFF) != ((after >>> 8) & 0xFF)) {
            count++;
        }
        return count;
    }

    private static ObservedLocation observedLocation(List<ByteChange> changes) {
        if (changes.isEmpty()) {
            return null;
        }
        int blockIndex = changes.getFirst().blockIndex;
        int startOffset = changes.stream()
                .mapToInt(change -> change.payloadOffset)
                .min()
                .orElse(-1);
        return new ObservedLocation(blockIndex, startOffset);
    }

    private static List<ByteChange> configChanges(
            Map<Integer, byte[]> before,
            Map<Integer, byte[]> after) {
        List<ByteChange> changes = new ArrayList<>();
        for (int block = 0x00; block <= 0x1F; block++) {
            byte[] a = before.get(block);
            byte[] b = after.get(block);
            if (a == null || b == null || a.length != b.length) {
                throw new IllegalArgumentException(
                        "Cannot compare block " + hexByte(block) + " with missing/different payloads.");
            }
            for (int offset = 0; offset < a.length; offset++) {
                if (a[offset] != b[offset]) {
                    changes.add(new ByteChange(
                            block,
                            offset,
                            a[offset] & 0xFF,
                            b[offset] & 0xFF));
                }
            }
        }
        return changes;
    }

    private static List<String> configDiffs(Map<Integer, byte[]> before, Map<Integer, byte[]> after) {
        List<String> diffs = new ArrayList<>();
        for (ByteChange change : configChanges(before, after)) {
            diffs.add(
                    "block " + hexByte(change.blockIndex)
                            + " payload offset " + change.payloadOffset
                            + ": " + change.before + " -> " + change.after);
        }
        return diffs;
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
        md.append("# FIR408 Gate Readback Verification\n\n");
        md.append("- Time: `").append(report.timestamp).append("`\n");
        md.append("- DSP: `").append(report.host).append(':').append(report.port).append("`\n");
        md.append("- All tested fields verified: **").append(report.allFieldsVerified).append("**\n");
        md.append("- Full config restored byte-for-byte: **").append(report.fullConfigRestored).append("**\n");
        md.append("- Safety: all 12 channels muted; no unmute command was sent\n\n");

        md.append("## Original gate values\n\n");
        md.append("| Channel | Attack raw | Release raw | Hold raw | Threshold raw |\n");
        md.append("| --- | ---: | ---: | ---: | ---: |\n");
        for (Map<String, Object> gate : report.originalGateValues) {
            md.append("| ").append(gate.get("channel"))
                    .append(" | ").append(gate.get("attackRaw"))
                    .append(" | ").append(gate.get("releaseRaw"))
                    .append(" | ").append(gate.get("holdRaw"))
                    .append(" | ").append(gate.get("thresholdRaw"))
                    .append(" |\n");
        }

        md.append("\n## Readback locations\n\n");
        md.append("| Channel | Field | Block | Payload offset | Data offset | Baseline | Marker | Restored | Verified |\n");
        md.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (Map<String, Object> field : report.fields) {
            md.append("| ").append(field.get("channel"))
                    .append(" | ").append(field.get("field"))
                    .append(" | `").append(field.get("observedBlockHex")).append('`')
                    .append(" | ").append(field.get("observedPayloadOffset"))
                    .append(" | ").append(field.get("observedDataOffset"))
                    .append(" | ").append(field.get("baselineRaw"))
                    .append(" | ").append(field.get("markerRaw"))
                    .append(" | ").append(field.get("restoredRaw"))
                    .append(" | ").append(field.get("verified"))
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
        System.out.println("FIR408 Gate Readback Verifier");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp target\\tracks-dsp-script-all.jar "
                + "de.drremote.dsp408.raw.Fir408GateReadVerifier");
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
        System.out.println("  The tool tests only write-confirmed InA and InD gate fields.");
        System.out.println("  It preserves their current tuples, never unmutes, and ends by");
        System.out.println("  restoring both tuples and muting all 12 channels again.");
    }

    private enum GateField {
        ATTACK("attack", 0, 998),
        RELEASE("release", 9, 2999),
        HOLD("hold", 9, 998),
        THRESHOLD("threshold", 0, 180);

        private final String jsonName;
        private final int minRaw;
        private final int maxRaw;

        GateField(String jsonName, int minRaw, int maxRaw) {
            this.jsonName = jsonName;
            this.minRaw = minRaw;
            this.maxRaw = maxRaw;
        }

        private int markerFor(int baselineRaw) {
            return baselineRaw == maxRaw ? minRaw : maxRaw;
        }
    }

    private record ChannelSpec(String name, int channelIndex, int inputBase) {
    }

    private record FieldLocation(int blockIndex, int payloadOffset) {
    }

    private record ObservedLocation(int blockIndex, int payloadOffset) {
    }

    private record ByteChange(int blockIndex, int payloadOffset, int before, int after) {
    }

    private record GateRaw(int attack, int release, int hold, int threshold) {
        private int value(GateField field) {
            return switch (field) {
                case ATTACK -> attack;
                case RELEASE -> release;
                case HOLD -> hold;
                case THRESHOLD -> threshold;
            };
        }

        private GateRaw with(GateField field, int value) {
            return switch (field) {
                case ATTACK -> new GateRaw(value, release, hold, threshold);
                case RELEASE -> new GateRaw(attack, value, hold, threshold);
                case HOLD -> new GateRaw(attack, release, value, threshold);
                case THRESHOLD -> new GateRaw(attack, release, hold, value);
            };
        }
    }

    private record VerificationReport(
            String timestamp,
            String host,
            int port,
            boolean allFieldsVerified,
            boolean fullConfigRestored,
            List<String> fullConfigDiffs,
            List<Map<String, Object>> originalGateValues,
            List<Map<String, Object>> fields) {
    }

    private static final class Options {
        private String host = "192.168.0.166";
        private int port = 9761;
        private Path outDir = Path.of("out", "fir", "fir408-gate-readback-verification");
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
