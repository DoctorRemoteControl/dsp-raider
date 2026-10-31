package de.drremote.dsp408.emu;

import de.drremote.dsp408.protocol.DspFrameCodec;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class Dsp408FrameTest {
    @Test void readsEmbeddedTrailerBytesWithoutTruncatingPayload() throws Exception {
        byte[] payload = {1,0,5,0x24,0x10,0x03,0x55,0x66};
        assertArrayEquals(payload, Dsp408Frame.read(new ByteArrayInputStream(DspFrameCodec.encode(payload))).payload());
    }

    @Test void rejectsCorruptRequestsRatherThanEmulatingThem() {
        byte[] wire = DspFrameCodec.encode(new byte[]{0,1,1,0x2c});
        wire[wire.length-1] ^= 1;
        assertThrows(IOException.class, () -> Dsp408Frame.read(new ByteArrayInputStream(wire)));
    }
}
