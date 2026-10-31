package de.drremote.dsp408.execution;

import de.drremote.dsp408.model.DeviceIdentity;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.parameters.ParameterKind;
import de.drremote.dsp408.planning.ChangeRequest;
import de.drremote.dsp408.planning.ParameterChange;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.Hex;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.snapshot.Snapshot;
import de.drremote.dsp408.snapshot.SnapshotFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class ChangeApplierTest {
    private final List<String> writes = new ArrayList<>();
    private final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();
    private boolean validAck = true;
    private final DspSession session = (DspSession) Proxy.newProxyInstance(DspSession.class.getClassLoader(),
            new Class<?>[]{DspSession.class}, (proxy,method,args) -> {
                if (!method.getName().equals("sendPayload")) throw new AssertionError("Unexpected transport call: " + method.getName());
                writes.add(Hex.bytes((byte[])args[0]));
                assertEquals(1,args[1]); assertEquals(true,args[2]);
                byte[] payload = validAck ? new byte[]{1,0,1,1} : new byte[]{1,0,1,2};
                return new ProxyResponse(DspFrameCodec.encode(payload),payload,true);
            });

    @Test void sendsQuantizedPayloadAndVerifiesReadback() throws Exception {
        Snapshot baseline = fixture(); snapshots.add(baseline); snapshots.add(gain(baseline,250));
        var result = applier().apply(session,baseline,request(new ParameterChange(5,ParameterKind.GAIN,-3.04)));
        assertTrue(result.ok(),result::error);
        assertEquals(List.of("00 01 04 34 05 FA 00"),writes);
        assertEquals(ApplyResult.Status.APPLIED,result.entries().getFirst().status());
        assertEquals(-3.0,result.entries().getFirst().readback().value());
    }

    @Test void unchangedTargetStillChecksFreshStateAndSendsNothing() throws Exception {
        Snapshot baseline=fixture(); snapshots.add(baseline);
        var result=applier().apply(session,baseline,request(new ParameterChange(6,ParameterKind.GAIN,-6)));
        assertTrue(result.ok(),result::error); assertTrue(writes.isEmpty());
        assertEquals(ApplyResult.Status.UNCHANGED,result.entries().getFirst().status());
    }

    @Test void inferredMuteBlocksWholeRequestBeforeAnyLiveReadOrWrite() throws Exception {
        var result=applier().apply(session,fixture(),request(
                new ParameterChange(5,ParameterKind.GAIN,-3),new ParameterChange(0,ParameterKind.MUTE,1)));
        assertFalse(result.ok()); assertTrue(writes.isEmpty());
        assertEquals(ApplyResult.Status.BLOCKED,result.entries().get(1).status());
        assertFalse(result.entries().getFirst().writeAttempted());
    }

    @Test void staleBaselinePreventsWritingEvenWhenLiveStateMatchesTarget() throws Exception {
        var baseline=fixture();snapshots.add(gain(baseline,250));
        var result=applier().apply(session,baseline,request(new ParameterChange(5,ParameterKind.GAIN,-3)));
        assertFalse(result.ok());assertTrue(writes.isEmpty());assertTrue(result.error().contains("baseline"));
    }

    @Test void differentFirmwarePreventsAllWrites() throws Exception {
        var baseline=fixture();snapshots.add(new Snapshot(new DeviceIdentity("FIR408","FIR408 V0999",31),baseline.blocks()));
        var result=applier().apply(session,baseline,request(new ParameterChange(5,ParameterKind.GAIN,-3)));
        assertFalse(result.ok());assertTrue(writes.isEmpty());assertTrue(result.error().contains("firmware"));
    }

    @Test void failedAckRecordsAttemptWithoutReadingBackOrSendingLaterChanges() throws Exception {
        var baseline=fixture();snapshots.add(baseline);validAck=false;
        var result=applier().apply(session,baseline,request(new ParameterChange(5,ParameterKind.GAIN,-3),new ParameterChange(11,ParameterKind.DELAY,20)));
        assertFalse(result.ok());assertEquals(1,writes.size());assertTrue(result.entries().getFirst().writeAttempted());
        assertEquals(ApplyResult.Status.FAILED,result.entries().getFirst().status());
        assertEquals(ApplyResult.Status.NOT_ATTEMPTED,result.entries().get(1).status());
    }

    @Test void wrongReadbackStopsAndPreservesEarlierVerifiedResult() throws Exception {
        var baseline=fixture();var afterGain=gain(baseline,250);
        snapshots.add(baseline);snapshots.add(afterGain);snapshots.add(afterGain);
        var result=applier().apply(session,baseline,request(new ParameterChange(5,ParameterKind.GAIN,-3),new ParameterChange(11,ParameterKind.DELAY,20)));
        assertFalse(result.ok());assertEquals(2,writes.size());
        assertEquals(ApplyResult.Status.APPLIED,result.entries().getFirst().status());
        assertEquals(ApplyResult.Status.FAILED,result.entries().get(1).status());
        assertTrue(result.error().contains("readback"));
    }

    @Test void unrelatedMuteBitsDoNotMakeConfirmedChannelStale() throws Exception {
        var baseline=fixture();var changed=byteAt(baseline,28,32,1);var after=byteAt(changed,28,32,9);
        snapshots.add(changed);snapshots.add(after);
        var result=applier().apply(session,baseline,request(new ParameterChange(3,ParameterKind.MUTE,1)));
        assertTrue(result.ok(),result::error); assertEquals(List.of("00 01 03 35 03 01"),writes);
    }

    @Test void cancelledApplicationDoesNotReadOrWriteAndKeepsInterruptFlag() throws Exception {
        var baseline=fixture();
        Thread.currentThread().interrupt();
        try {
            var result=applier().apply(session,baseline,request(new ParameterChange(5,ParameterKind.GAIN,-3)));
            assertFalse(result.ok());assertTrue(writes.isEmpty());assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }

    private ChangeApplier applier() { return new ChangeApplier(s -> {
        if(snapshots.isEmpty()) throw new AssertionError("Unexpected snapshot read");
        return snapshots.removeFirst();
    }); }
    private ChangeRequest request(ParameterChange... changes) { return new ChangeRequest("FIR408 V0113P",List.of(changes)); }
    private Snapshot fixture() throws IOException { return SnapshotFiles.read(Path.of("src/test/resources/live/fir408-v0113p-20261031.json")); }
    private Snapshot gain(Snapshot original,int raw) { return byteAt(byteAt(original,15,26,raw&255),15,27,raw>>>8); }
    private Snapshot byteAt(Snapshot original,int block,int offset,int value) {
        var blocks=new TreeMap<>(original.blocks());byte[] payload=blocks.get(block).payload();payload[offset+5]=(byte)value;
        blocks.put(block,new ProxyResponse(DspFrameCodec.encode(payload),payload,true));
        return new Snapshot(original.identity(),blocks);
    }
}
