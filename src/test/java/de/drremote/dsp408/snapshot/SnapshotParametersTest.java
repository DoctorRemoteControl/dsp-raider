package de.drremote.dsp408.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.model.DeviceIdentity;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.protocol.DspFrameCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotParametersTest {
    @TempDir Path temp;

    private Snapshot reference() throws Exception {
        var mapper=new ObjectMapper();
        try(var input=getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")) {
            Path path=temp.resolve("reference.json");
            mapper.writeValue(path.toFile(),mapper.readTree(input));
            return SnapshotFiles.read(path);
        }
    }

    private Snapshot edit(Snapshot original,int block,int offset,int... bytes) {
        var blocks=new TreeMap<>(original.blocks());
        byte[] payload=blocks.get(block).payload().clone();
        for(int i=0;i<bytes.length;i++)payload[5+offset+i]=(byte)bytes[i];
        blocks.put(block,new ProxyResponse(DspFrameCodec.encode(payload),payload,true));
        return new Snapshot(original.identity(),blocks);
    }

    @Test void reportsGainAndDelayInEngineeringUnitsAndRetainsRawDiff() throws Exception {
        var before=reference();
        var after=edit(edit(before,13,18,250,0),28,28,0x80,7); // Out1 -3 dB; Out8 20 ms
        String diff=SnapshotAnalysis.diff(before,after);
        assertTrue(diff.contains("Out1 Gain: -1.0 dB -> -3.0 dB"),diff);
        assertTrue(diff.contains("Out8 Delay: 0.000 ms -> 20.000 ms"),diff);
        assertTrue(diff.contains("block 0x0D offset 0x0012: 0E -> FA"),diff);
        assertTrue(diff.contains("unmapped_bytes=0"),diff);
    }

    @Test void confirmedMuteBitDoesNotHideUnknownBitsInSameByte() throws Exception {
        var before=reference();
        var after=edit(before,28,34,0x81); // confirmed Out8, inferred Out1
        String diff=SnapshotAnalysis.diff(before,after);
        assertTrue(diff.contains("Out8 Mute: AUS -> AN"),diff);
        assertFalse(diff.contains("Out1 Mute: AUS -> AN"),diff);
        assertTrue(diff.contains("unmapped block 0x1C offset 0x0022 mask 0x01"),diff);
        assertTrue(diff.contains("unmapped_bytes=1"),diff);
    }

    @Test void inferredInputMuteStaysUnknownAndRawChangeRemainsVisible() throws Exception {
        var before=reference();var after=edit(before,28,32,1);
        String diff=SnapshotAnalysis.diff(before,after);
        assertFalse(diff.contains("InA Mute: AUS -> AN"),diff);
        assertTrue(diff.contains("unmapped block 0x1C offset 0x0020 mask 0x01"),diff);
        assertTrue(SnapshotAnalysis.decode(after).contains("INFERRED"));
    }

    @Test void invalidValueIsNotPresentedAsConfirmedOrConsumedByCoverage() throws Exception {
        var before=reference();var after=edit(before,13,18,255,255);
        String diff=SnapshotAnalysis.diff(before,after);
        assertTrue(diff.contains("Out1 Gain: -1.0 dB -> unbekannt"),diff);
        assertTrue(diff.contains("unmapped_bytes=2"),diff);
        String decoded=SnapshotAnalysis.decode(after);
        assertTrue(decoded.contains("INVALID"),decoded);
        assertFalse(decoded.contains("6525.5"),decoded);
    }

    @Test void missingBlockAndUnknownFirmwareCannotProduceConfirmedValues() throws Exception {
        var before=reference();var blocks=new TreeMap<>(before.blocks());blocks.remove(13);
        String missing=SnapshotAnalysis.diff(before,new Snapshot(before.identity(),blocks));
        assertTrue(missing.contains("Out1 Gain: -1.0 dB -> unbekannt"),missing);
        assertTrue(missing.contains("removed block 0x0D"),missing);
        var unknown=new Snapshot(new DeviceIdentity("FIR408","FIR408 V0115",31),before.blocks());
        String decoded=SnapshotAnalysis.decode(unknown);
        assertTrue(decoded.contains("Kein bestätigtes Parameterprofil"),decoded);
        assertFalse(decoded.contains("FIR408 DSPD Static Decode"),decoded);
        assertFalse(SnapshotAnalysis.diff(before,unknown).contains("Out1 Gain:"));
    }

    @Test void identicalDataDoesNotProduceSemanticOrUnmappedChanges() throws Exception {
        var snapshot=reference();String diff=SnapshotAnalysis.diff(snapshot,snapshot);
        assertTrue(diff.contains("parameter_changes=0"),diff);
        assertTrue(diff.contains("unmapped_bytes=0"),diff);
        assertTrue(diff.contains("changed=0 added=0 removed=0"),diff);
    }
}
