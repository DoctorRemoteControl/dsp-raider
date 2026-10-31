package de.drremote.dsp408.fir;

import de.drremote.dsp408.protocol.DspPayloads;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class Fir408MemoryTest {
    @Test void decodesCapturedMemoryWithoutScriptRuntimeOrConnection() {
        byte[] memory = new byte[1556];
        byte[] name = "Main A".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name,0,memory,16,name.length);
        memory[16+130]=0x18; memory[16+131]=1; // gain raw 280 -> 0 dB
        String report=Fir408Memory.decode(memory);
        assertTrue(report.contains("| InA | Main A | +0.0dB |"));
        assertTrue(report.contains("| Out8 |"));
        assertTrue(report.contains("Assembled data bytes: 1556"));
    }

    @Test void rejectsTruncatedOrMissingConfigMemory() {
        assertThrows(IllegalArgumentException.class,() -> Fir408Memory.decode(new byte[1555]));
        assertThrows(IllegalArgumentException.class,() -> Fir408Memory.decode(null));
    }

    @Test void invalidScalarsAndUnconfirmedMuteRemainUnknownInLegacyDetailTable() {
        byte[] memory=new byte[1556];
        memory[146]=(byte)255;memory[147]=(byte)255;
        memory[150]=(byte)255;memory[151]=(byte)255;
        memory[1432]=9;memory[1434]=(byte)128;
        String report=Fir408Memory.decode(memory);
        String ina=report.lines().filter(line->line.startsWith("| InA |")).findFirst().orElseThrow();
        String ind=report.lines().filter(line->line.startsWith("| InD |")).findFirst().orElseThrow();
        String out8=report.lines().filter(line->line.startsWith("| Out8 |")).findFirst().orElseThrow();
        assertTrue(ina.contains("unbekannt"),ina);
        assertFalse(ina.contains("6525.5"),ina);assertFalse(ina.contains("682.66"),ina);
        assertTrue(ina.contains("unbestätigte Lesezuordnung"),ina);
        assertTrue(ind.endsWith("| AN |"),ind);assertTrue(out8.endsWith("| AN |"),out8);
    }

    @Test void sameValuePingPlanPreservesExistingWriteSetAndExcludesMute() {
        var pings=Fir408Memory.sameValuePings(new byte[1556]);
        assertEquals(112,pings.size());
        assertTrue(pings.stream().noneMatch(p -> DspPayloads.command(p.payload()) == 0x35));
        assertTrue(pings.stream().allMatch(p -> p.payload().length == 3+(p.payload()[2]&0xff)));
    }
}
