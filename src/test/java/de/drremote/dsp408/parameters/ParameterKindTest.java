package de.drremote.dsp408.parameters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ParameterKindTest {
    @ParameterizedTest @CsvSource({"279,-0.1","281,0.1","249,-3.1","81,-19.9","79,-20.5"})
    void representableGainsDoNotAcquireSubtractionNoise(int raw,double db) {
        assertEquals(db,ParameterKind.GAIN.rawToValue(raw));
    }

    @ParameterizedTest
    @CsvSource({"0,-60", "64,-28", "80,-20", "81,-19.9", "220,-6", "250,-3", "280,0", "400,12"})
    void gainRoundTripsObservedAnchors(int raw, double db) {
        assertEquals(db,ParameterKind.GAIN.rawToValue(raw),1e-9);
        assertEquals(raw,ParameterKind.GAIN.valueToRaw(db));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "960,10", "1920,20", "65280,680"})
    void delayUses96CountsPerMillisecond(int raw, double ms) {
        assertEquals(ms,ParameterKind.DELAY.rawToValue(raw),1e-9);
        assertEquals(raw,ParameterKind.DELAY.valueToRaw(ms));
    }

    @Test void writePayloadsMatchObservedWireBytesWithoutTransmitting() {
        assertArrayEquals(new byte[]{0,1,4,0x34,4,(byte)0xdc,0},ParameterKind.GAIN.writePayload(4,-6));
        assertArrayEquals(new byte[]{0,1,4,0x38,11,(byte)0x80,7},ParameterKind.DELAY.writePayload(11,20));
        assertArrayEquals(new byte[]{0,1,3,0x35,3,1},ParameterKind.MUTE.writePayload(3,1));
        assertEquals("-6.0 dB",ParameterKind.GAIN.format(-6));
        assertEquals("20.000 ms",ParameterKind.DELAY.format(20));
        assertEquals("AN",ParameterKind.MUTE.format(1));
        assertEquals("AUS",ParameterKind.MUTE.format(0));
    }

    @Test void invalidRawAndEngineeringValuesAreNeverClamped() {
        assertThrows(IllegalArgumentException.class,() -> ParameterKind.GAIN.rawToValue(401));
        assertThrows(IllegalArgumentException.class,() -> ParameterKind.DELAY.rawToValue(65535));
        assertThrows(IllegalArgumentException.class,() -> ParameterKind.MUTE.rawToValue(2));
        assertThrows(IllegalArgumentException.class,() -> ParameterKind.GAIN.valueToRaw(-61));
        assertThrows(IllegalArgumentException.class,() -> ParameterKind.MUTE.valueToRaw(0.5));
        for(var kind:ParameterKind.values()) {
            assertThrows(IllegalArgumentException.class,() -> kind.valueToRaw(Double.NaN));
            assertThrows(IllegalArgumentException.class,() -> kind.valueToRaw(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,() -> kind.writePayload(12,0));
        }
    }
}
