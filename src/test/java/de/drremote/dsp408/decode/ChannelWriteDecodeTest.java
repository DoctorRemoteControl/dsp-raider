package de.drremote.dsp408.decode;

import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.parameters.ParameterKind;
import de.drremote.dsp408.protocol.DspFrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChannelWriteDecodeTest {
    private WriteDecodeResult decode(byte[] payload) throws Exception {
        var lib=DspLibLookup.load(Path.of("DspLib-408-fir.json"));
        return new WriteDecodeService(lib).decode(new SniffedFrame("PC_TO_DSP",DspFrameCodec.encode(payload),payload,null,payload.length,true));
    }

    @Test void sharedBuilderAndPassiveDecoderAgreeOnKnownValues() throws Exception {
        var gain=decode(ParameterKind.GAIN.writePayload(4,-6));
        var delay=decode(ParameterKind.DELAY.writePayload(11,20));
        var mute=decode(ParameterKind.MUTE.writePayload(3,1));
        assertTrue(gain.matched());assertTrue(gain.format().contains("-6.0 dB (raw 220)"));
        assertTrue(delay.matched());assertTrue(delay.format().contains("ms      = 20.0"));
        assertTrue(mute.matched());assertTrue(mute.format().contains("state   = mute"));
    }

    @ParameterizedTest @CsvSource({"GAIN,401","DELAY,65535","MUTE,2"})
    void outOfRangeRawIsUnknownInsteadOfAFalseValue(ParameterKind kind,int raw) throws Exception {
        byte[] payload=kind.writePayload(0,0);
        payload[5]=(byte)raw;if(payload.length==7)payload[6]=(byte)(raw>>>8);
        var result=decode(payload);
        assertFalse(result.matched(),result.format());
        assertTrue(result.format().contains("raw="+raw),result.format());
        assertTrue(result.format().contains("invalid"),result.format());
    }

    @Test void invalidChannelAndExtraBytesAreNotMatchedAsScalarWrites() throws Exception {
        byte[] payload=ParameterKind.GAIN.writePayload(0,0);payload[4]=12;
        assertFalse(decode(payload).matched());
        payload=java.util.Arrays.copyOf(ParameterKind.MUTE.writePayload(0,1),7);payload[2]=4;
        assertFalse(decode(payload).matched());
    }

    @Test void matrixConversionSharesStrictGainCodecAndInvalidCaptureStaysUnknown() throws Exception {
        var lib=DspLibLookup.load(Path.of("DspLib-408-fir.json"));
        assertEquals(-6,lib.gainDb(220));assertEquals(-6,lib.matrixCrosspointGainDb(220));
        assertThrows(IllegalArgumentException.class,()->lib.gainDb(401));
        assertFalse(decode(new byte[]{0,1,5,0x41,4,0,(byte)255,(byte)255}).matched());
    }
}
