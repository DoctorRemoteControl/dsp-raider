package de.drremote.dsp408.emu;

import de.drremote.dsp408.protocol.DspFrameCodec;
import java.io.IOException;
import java.io.InputStream;

final class Dsp408Frame {
    static final int DLE = DspFrameCodec.DLE;
    static final int STX = DspFrameCodec.STX;
    static final int ETX = DspFrameCodec.ETX;
    private final byte[] payload;

    Dsp408Frame(byte[] payload) { this.payload = payload.clone(); }
    byte[] payload() { return payload.clone(); }
    int command() { return payload.length >= 4 ? payload[3] & 0xff : -1; }
    byte[] encode() { return DspFrameCodec.encode(payload); }

    static Dsp408Frame read(InputStream in) throws IOException {
        var decoded = DspFrameCodec.readFrame(in);
        if (!decoded.checksumValid()) throw new IOException("Invalid DSP request checksum");
        return new Dsp408Frame(decoded.payload());
    }
}
