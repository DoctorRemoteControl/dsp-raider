package de.drremote.dsp408.model;

import de.drremote.dsp408.protocol.DspPayloads;
import de.drremote.dsp408.protocol.Hex;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ReadBlockSet {
    private final List<ProxyResponse> responses;

    public ReadBlockSet(List<ProxyResponse> responses) {
        Objects.requireNonNull(responses, "responses");
        this.responses = List.copyOf(responses);
    }

    public List<ProxyResponse> responses() {
        return responses;
    }

    public int count() {
        return responses.size();
    }

    public boolean isEmpty() {
        return responses.isEmpty();
    }

    public ProxyResponse first() {
        return responses.isEmpty() ? null : responses.get(0);
    }

    public ProxyResponse last() {
        return responses.isEmpty() ? null : responses.get(responses.size() - 1);
    }

    public List<Integer> blockIndices() {
        List<Integer> out = new ArrayList<>();
        for (ProxyResponse response : responses) {
            Integer blockIndex = response.readBlockIndex();
            if (blockIndex != null) {
                out.add(blockIndex);
            }
        }
        return List.copyOf(out);
    }

    public byte[] data() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ProxyResponse response : responses) {
            byte[] payload = response.payload();
            if (!isReadBlockPayload(payload)) {
                continue;
            }
            out.write(payload, 5, payload.length - 5);
        }
        return out.toByteArray();
    }

    public int dataLen() {
        return data().length;
    }

    public String dataHex() {
        return Hex.bytes(data());
    }

    public String allBlocksHex() {
        StringBuilder sb = new StringBuilder();
        for (ProxyResponse response : responses) {
            Integer blockIndex = response.readBlockIndex();
            if (blockIndex == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(System.lineSeparator());
            }
            sb.append(String.format("block-%02X: %s", blockIndex, response.payloadHex()));
        }
        return sb.toString();
    }

    public ProxyResponse response(int blockIndex) {
        int expected = blockIndex & 0xFF;
        for (ProxyResponse response : responses) {
            Integer actual = response.readBlockIndex();
            if (actual != null && actual == expected) {
                return response;
            }
        }
        return null;
    }

    private static boolean isReadBlockPayload(byte[] payload) {
        return payload != null
                && payload.length >= 5
                && DspPayloads.command(payload) != null
                && DspPayloads.command(payload) == 0x24
                && DspPayloads.readBlockIndex(payload) != null;
    }

    @Override
    public String toString() {
        List<Integer> indices = blockIndices();
        String range;
        if (indices.isEmpty()) {
            range = "none";
        } else if (indices.size() == 1) {
            range = String.format("0x%02X", indices.get(0));
        } else {
            range = String.format("0x%02X..0x%02X", indices.get(0), indices.get(indices.size() - 1));
        }
        return "readBlockSet{count=" + count() + ", range=" + range + ", dataLen=" + dataLen() + "}";
    }

    public static ReadBlockSet of(ProxyResponse... responses) {
        return new ReadBlockSet(Arrays.asList(responses));
    }
}
