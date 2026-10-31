package de.drremote.dsp408.model;

import de.drremote.dsp408.protocol.DspPayloads;
import de.drremote.dsp408.protocol.Hex;

import java.util.Arrays;

public final class ProxyResponse {
    private final byte[] raw;
    private final byte[] payload;
    private final boolean checksumOk;
    private final Integer command;

    public ProxyResponse(byte[] raw, byte[] payload, boolean checksumOk) {
        this.raw = raw == null ? new byte[0] : Arrays.copyOf(raw, raw.length);
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        this.checksumOk = checksumOk;
        this.command = DspPayloads.command(this.payload);
    }

    public byte[] raw() {
        return Arrays.copyOf(raw, raw.length);
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public boolean checksumOk() {
        return checksumOk;
    }

    public Integer command() {
        return command;
    }

    public String commandHex() {
        return DspPayloads.commandHex(command);
    }

    public Integer readBlockIndex() {
        return DspPayloads.readBlockIndex(payload);
    }

    public String rawHex() {
        return Hex.bytes(raw);
    }

    public String payloadHex() {
        return Hex.bytes(payload);
    }

    public String payloadAscii() {
        return Hex.ascii(payload);
    }

    public int rawLen() {
        return raw.length;
    }

    public int payloadLen() {
        return payload.length;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("resp{");
        sb.append("cmd=").append(commandHex());
        sb.append(", len=").append(payloadLen());
        sb.append(", hex=").append(Hex.preview(payload, 12));
        if (readBlockIndex() != null) {
            sb.append(", block=").append(String.format("0x%02X", readBlockIndex()));
        }
        if (!checksumOk) {
            sb.append(", checksum_ok=false");
        }
        sb.append("}");
        return sb.toString();
    }
}