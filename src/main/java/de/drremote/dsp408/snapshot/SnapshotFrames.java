package de.drremote.dsp408.snapshot;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.DeviceIdentity;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.DspPayloads;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Validate the actual wire data instead of trusting transport/JSON success flags. */
public final class SnapshotFrames {
    private SnapshotFrames() { }

    public static void configurationSize(DeviceIdentity identity, int index, ProxyResponse response) throws IOException {
        if (identity.lastBlock() < 0 || index > identity.lastBlock()) return;
        int size = response.payloadLen() - 5;
        int expected = index < identity.lastBlock() ? 50 : identity.profile().equals("FIR408") ? 6 : -1;
        if ((expected >= 0 && size != expected) || (expected < 0 && (size < 1 || size > 50)))
            throw new IOException("Unexpected configuration data length at block 0x%02X: %d bytes".formatted(index,size));
    }

    public static ProxyResponse checked(ProxyResponse response, Integer command, Integer block) throws IOException {
        if (response == null) throw new IOException("Missing DSP response");
        try {
            var decoded = DspFrameCodec.decode(response.raw());
            byte[] payload = decoded.payload();
            if (!decoded.checksumValid() || !response.checksumOk()) throw new IOException("Invalid DSP checksum");
            if (!Arrays.equals(payload, response.payload())) throw new IOException("Payload differs from wire frame");
            if (payload.length < 4 || payload.length != 3 + (payload[2] & 0xff))
                throw new IOException("Invalid DSP payload length");
            if (command != null && !Objects.equals(command, DspPayloads.command(payload)))
                throw new IOException("Unexpected DSP response command");
            if (block != null && !Objects.equals(block, DspPayloads.readBlockIndex(payload)))
                throw new IOException("Unexpected read block index");
            return new ProxyResponse(decoded.raw(), payload, true);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid DSP wire frame", e);
        }
    }
}
