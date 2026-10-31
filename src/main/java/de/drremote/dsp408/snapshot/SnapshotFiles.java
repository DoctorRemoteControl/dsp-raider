package de.drremote.dsp408.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.model.DeviceIdentity;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.protocol.Hex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.TreeMap;

/** Reads legacy/new dump JSON and checks its wire bytes and declared metadata. */
public final class SnapshotFiles {
    private SnapshotFiles() { }

    public static Snapshot read(Path path) throws IOException {
        return read(new ObjectMapper().readTree(path.toFile()));
    }

    public static Snapshot read(JsonNode root) throws IOException {
        if (root == null || !root.path("ok").asBoolean(false)) throw new IOException("Snapshot is not a successful read");
        checkedFrame(root.at("/handshake/init"), 0x10, null);
        var device = checkedFrame(root.at("/handshake/device"), 0x13, null);
        checkedFrame(root.at("/handshake/system"), 0x2c, null);
        var identity = DeviceIdentity.fromPayload(device.payload());
        if (root.has("device_version") && !root.path("device_version").asText().equals(identity.version()))
            throw new IOException("Device version metadata differs from wire response");
        if (root.has("device_profile") && !root.path("device_profile").asText().equals(identity.profile()))
            throw new IOException("Device profile metadata differs from wire response");
        if (!root.path("blocks").isArray()) throw new IOException("Missing configuration blocks array");
        var blocks = new TreeMap<Integer, ProxyResponse>();
        for (JsonNode node : root.path("blocks")) {
            int index = number(node.path("block").asText(), "block index");
            if (index < 0 || index > 0x1f) throw new IOException("Block index outside 0x00..0x1F");
            if (blocks.putIfAbsent(index, checkedFrame(node, 0x24, index)) != null)
                throw new IOException("Duplicate configuration block 0x%02X".formatted(index));
        }
        if (root.at("/summary/blocks_total").isNumber() && root.at("/summary/blocks_total").asInt() != blocks.size())
            throw new IOException("Block count metadata differs from block array");
        return new Snapshot(identity, blocks);
    }

    public static ProxyResponse checkedFrame(JsonNode node, Integer command, Integer block) throws IOException {
        byte[] raw = Hex.parse(node.path("raw_hex").asText());
        byte[] payload = Hex.parse(node.path("payload_hex").asText());
        var response = SnapshotFrames.checked(new ProxyResponse(raw, payload,
                !node.has("checksum_ok") || node.path("checksum_ok").asBoolean()), command, block);
        if (node.has("cmd") && number(node.path("cmd").asText(), "command") != response.command())
            throw new IOException("Command metadata differs from wire response");
        if (node.has("len") && node.path("len").asInt(-1) != payload.length)
            throw new IOException("Length metadata differs from payload");
        if (block != null && node.has("read_block_index") && node.path("read_block_index").asInt(-1) != block)
            throw new IOException("Block metadata differs from payload");
        return response;
    }

    private static int number(String text, String label) throws IOException {
        try { return Integer.decode(text); }
        catch (NumberFormatException e) { throw new IOException("Invalid " + label + ": " + text, e); }
    }
}
