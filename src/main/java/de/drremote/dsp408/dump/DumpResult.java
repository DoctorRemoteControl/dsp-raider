package de.drremote.dsp408.dump;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.util.DspProtocol;
import de.drremote.dsp408.util.HexUtil;
import de.drremote.dsp408.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;

public final class DumpResult {
    boolean ok;
    String error;
    String deviceVersion;
    String deviceProfile;

    FrameJson handshakeInit;
    FrameJson deviceInfo;
    FrameJson systemInfo;
    FrameJson login;
    final List<BlockJson> blocks = new ArrayList<>();

    static DumpResult start() {
        return new DumpResult();
    }

    public String toJson() {
        int badChecksums = 0;
        for (BlockJson block : blocks) {
            if (!block.checksumOk) {
                badChecksums++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        JsonUtil.appendField(sb, 1, "ok", ok, true);
        if (deviceVersion != null) JsonUtil.appendField(sb, 1, "device_version", deviceVersion, true);
        if (deviceProfile != null) JsonUtil.appendField(sb, 1, "device_profile", deviceProfile, true);

        sb.append(JsonUtil.indent(1)).append("\"summary\": {\n");
        JsonUtil.appendField(sb, 2, "handshake_ok", handshakeInit != null && deviceInfo != null && systemInfo != null, true);
        JsonUtil.appendField(sb, 2, "login_used", login != null, true);
        JsonUtil.appendField(sb, 2, "blocks_total", blocks.size(), true);
        JsonUtil.appendField(sb, 2, "blocks_bad_checksum", badChecksums, false);
        sb.append(JsonUtil.indent(1)).append("},\n");

        sb.append(JsonUtil.indent(1)).append("\"handshake\": {\n");
        entry(sb, 2, "init", handshakeInit, true);
        entry(sb, 2, "device", deviceInfo, true);
        entry(sb, 2, "system", systemInfo, false);
        sb.append(JsonUtil.indent(1)).append("},\n");

        sb.append(JsonUtil.indent(1)).append("\"login\": ");
        sb.append(login == null ? "null" : login.toJson(1));
        sb.append(",\n");

        sb.append(JsonUtil.indent(1)).append("\"blocks\": [\n");
        for (int i = 0; i < blocks.size(); i++) {
            sb.append(blocks.get(i).toJson(2));
            if (i + 1 < blocks.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(JsonUtil.indent(1)).append("]");

        if (error != null) {
            sb.append(",\n");
            JsonUtil.appendField(sb, 1, "error", error, false);
        } else {
            sb.append("\n");
        }

        sb.append("}");
        return sb.toString();
    }

    private static void entry(StringBuilder sb, int level, String key, FrameJson value, boolean comma) {
        sb.append(JsonUtil.indent(level)).append(JsonUtil.quote(key)).append(": ");
        sb.append(value == null ? "null" : value.toJson(level));
        if (comma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendNullableInt(StringBuilder sb, int level, String key, Integer value, boolean comma) {
        sb.append(JsonUtil.indent(level)).append(JsonUtil.quote(key)).append(": ");
        sb.append(value == null ? "null" : value);
        if (comma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    static final class FrameJson {
        String command;
        int payloadLen;
        String previewHex;
        String rawHex;
        String payloadHex;
        String payloadAscii;
        boolean checksumOk;

        static FrameJson fromResponse(ProxyResponse response) {
            FrameJson e = new FrameJson();
            Integer cmd = response.command();
            e.command = DspProtocol.commandHex(cmd);
            e.payloadLen = response.payloadLen();
            e.previewHex = HexUtil.toHexPreview(response.payload(), 12);
            e.rawHex = response.rawHex();
            e.payloadHex = response.payloadHex();
            e.payloadAscii = response.payloadAscii();
            e.checksumOk = response.checksumOk();
            return e;
        }

        String toJson(int level) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            JsonUtil.appendField(sb, level + 1, "cmd", command, true);
            JsonUtil.appendField(sb, level + 1, "len", payloadLen, true);
            JsonUtil.appendField(sb, level + 1, "hex", previewHex, true);
            JsonUtil.appendField(sb, level + 1, "payload_hex", payloadHex, true);
            JsonUtil.appendField(sb, level + 1, "payload_ascii", payloadAscii, true);
            JsonUtil.appendField(sb, level + 1, "raw_hex", rawHex, !checksumOk);
            if (!checksumOk) {
                JsonUtil.appendField(sb, level + 1, "checksum_ok", false, false);
            }
            sb.append(JsonUtil.indent(level)).append("}");
            return sb.toString();
        }
    }

    static final class BlockJson {
        String block;
        String command;
        int payloadLen;
        boolean checksumOk;
        String previewHex;
        String rawHex;
        String payloadHex;
        String payloadAscii;
        Integer readBlockIndex;

        static BlockJson fromResponse(int blockIndex, ProxyResponse response) {
            BlockJson block = new BlockJson();
            block.block = String.format("0x%02X", blockIndex);
            Integer cmd = response.command();
            block.command = DspProtocol.commandHex(cmd);
            block.payloadLen = response.payloadLen();
            block.checksumOk = response.checksumOk();
            block.previewHex = HexUtil.toHexPreview(response.payload(), 16);
            block.rawHex = response.rawHex();
            block.payloadHex = response.payloadHex();
            block.payloadAscii = response.payloadAscii();
            block.readBlockIndex = response.readBlockIndex();
            return block;
        }

        String toJson(int level) {
            StringBuilder sb = new StringBuilder();
            sb.append(JsonUtil.indent(level)).append("{\n");
            JsonUtil.appendField(sb, level + 1, "block", block, true);
            JsonUtil.appendField(sb, level + 1, "cmd", command, true);
            appendNullableInt(sb, level + 1, "read_block_index", readBlockIndex, true);
            JsonUtil.appendField(sb, level + 1, "len", payloadLen, true);
            JsonUtil.appendField(sb, level + 1, "hex", previewHex, true);
            JsonUtil.appendField(sb, level + 1, "payload_hex", payloadHex, true);
            JsonUtil.appendField(sb, level + 1, "payload_ascii", payloadAscii, true);
            JsonUtil.appendField(sb, level + 1, "raw_hex", rawHex, !checksumOk);
            if (!checksumOk) {
                JsonUtil.appendField(sb, level + 1, "checksum_ok", false, false);
            }
            sb.append(JsonUtil.indent(level)).append("}");
            return sb.toString();
        }
    }
}
