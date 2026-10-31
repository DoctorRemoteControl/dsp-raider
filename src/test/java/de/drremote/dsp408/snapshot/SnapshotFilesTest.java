package de.drremote.dsp408.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotFilesTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode reference() throws IOException {
        try (var input = getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")) {
            return (ObjectNode) mapper.readTree(input);
        }
    }

    private Path save(String name, ObjectNode data) throws IOException {
        Path path = temp.resolve(name);
        mapper.writeValue(path.toFile(), data);
        return path;
    }

    @Test void validatesAndDecodesUnmodifiedRealDeviceReference() throws Exception {
        var snapshot = SnapshotFiles.read(save("reference.json", reference()));
        assertEquals("FIR408 V0113P", snapshot.identity().version());
        assertEquals(32, snapshot.blocks().size());
        assertEquals(1556, snapshot.data().length);
        String report = SnapshotAnalysis.decode(snapshot);
        assertTrue(report.contains("MAIN_L"));
        assertTrue(report.contains("| Out8 |"));
    }

    @Test void rejectsCorruptWireBytesDespiteSuccessMetadata() throws Exception {
        ObjectNode data = reference();
        ObjectNode block = (ObjectNode) data.path("blocks").get(0);
        byte[] raw = Hex.parse(block.path("raw_hex").asText()); raw[raw.length - 1] ^= 1;
        block.put("raw_hex", Hex.bytes(raw)); block.put("checksum_ok", true);
        assertThrows(IOException.class, () -> SnapshotFiles.read(save("corrupt.json", data)));
    }

    @Test void rejectsPayloadThatDisagreesWithRawFrame() throws Exception {
        ObjectNode data = reference();
        ObjectNode block = (ObjectNode) data.path("blocks").get(0);
        byte[] payload = Hex.parse(block.path("payload_hex").asText()); payload[8] ^= 1;
        block.put("payload_hex", Hex.bytes(payload));
        assertThrows(IOException.class, () -> SnapshotFiles.read(save("payload.json", data)));
    }

    @Test void rejectsDuplicateBlockIndices() throws Exception {
        ObjectNode data = reference();
        ArrayNode blocks = (ArrayNode) data.path("blocks"); blocks.set(1, blocks.get(0).deepCopy());
        assertThrows(IOException.class, () -> SnapshotFiles.read(save("duplicate.json", data)));
    }

    @Test void incompleteFirMemoryCannotBeDecodedAsCompleteState() throws Exception {
        ObjectNode data = reference();
        ((ArrayNode) data.path("blocks")).remove(7);
        ((ObjectNode) data.path("summary")).put("blocks_total", 31);
        var snapshot = SnapshotFiles.read(save("partial.json", data));
        assertThrows(IOException.class, () -> SnapshotAnalysis.decode(snapshot));
        var full = SnapshotFiles.read(save("full.json", reference()));
        assertTrue(SnapshotAnalysis.diff(full, snapshot).contains("removed block 0x07"));
        assertTrue(SnapshotAnalysis.diff(snapshot, full).contains("added block 0x07"));
    }

    @Test void diffIncludesRemovedTrailingBytesRatherThanOnlyCommonPrefix() throws Exception {
        ObjectNode before = reference(), after = reference();
        ObjectNode block = (ObjectNode) after.path("blocks").get(31);
        byte[] payload = Hex.parse(block.path("payload_hex").asText());
        payload = Arrays.copyOf(payload, payload.length - 1); payload[2]--;
        block.put("payload_hex", Hex.bytes(payload));
        block.put("raw_hex", Hex.bytes(DspFrameCodec.encode(payload)));
        block.put("len", payload.length);
        String diff = SnapshotAnalysis.diff(SnapshotFiles.read(save("before.json", before)),
                SnapshotFiles.read(save("after.json", after)));
        assertTrue(diff.contains("0x1F"));
        assertTrue(diff.contains("6 -> 5"));
        assertTrue(diff.contains("removed=1"));
    }

    @Test void identicalSnapshotsHaveNoChanges() throws Exception {
        var data = SnapshotFiles.read(save("same.json", reference()));
        assertTrue(SnapshotAnalysis.diff(data, data).contains("changed=0 added=0 removed=0"));
    }
}
