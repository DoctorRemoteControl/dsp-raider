package de.drremote.dsp408.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.execution.ChangeApplier;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.snapshot.Snapshot;
import de.drremote.dsp408.snapshot.SnapshotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChangeApplyCliTest {
    @TempDir Path temporary;
    private static final String FIXTURE="src/test/resources/live/fir408-v0113p-20261031.json";

    @Test void blockedRequestProducesFailureReportWithoutConstructingSession() throws Exception {
        Path changes=temporary.resolve("blocked.json");
        Files.writeString(changes,"{\"device_version\":\"FIR408 V0113P\",\"changes\":[{\"channel\":\"InA\",\"parameter\":\"mute\",\"value\":true}]}");
        var out=new ByteArrayOutputStream(); var err=new ByteArrayOutputStream();
        var cli=new DspRaiderCli((a,b,c,d,e)->{throw new AssertionError("Blocked request opened session");});
        assertEquals(1,cli.run(new String[]{"apply","--in",FIXTURE,"--changes",changes.toString(),"--format","json"},new PrintStream(out),new PrintStream(err)),err::toString);
        var report=new ObjectMapper().readTree(out.toString());
        assertFalse(report.path("ok").asBoolean());assertEquals("BLOCKED",report.path("entries").get(0).path("status").asText());
    }

    @Test void appliesOnlyAfterPreflightAndRetainsReportIfClosingFails() throws Exception {
        var snapshots=new ArrayDeque<Snapshot>();
        var before=SnapshotFiles.read(Path.of(FIXTURE));
        snapshots.add(before);
        var blocks=new TreeMap<>(before.blocks());byte[] payload=blocks.get(15).payload();payload[31]=(byte)250;payload[32]=0;
        blocks.put(15,new ProxyResponse(DspFrameCodec.encode(payload),payload,true));snapshots.add(new Snapshot(before.identity(),blocks));
        var calls=new AtomicInteger();
        DspSession session=(DspSession)Proxy.newProxyInstance(DspSession.class.getClassLoader(),new Class<?>[]{DspSession.class},(p,m,args)->{
            if(m.getName().equals("sendPayload")){calls.incrementAndGet();byte[] ack={1,0,1,1};return new ProxyResponse(DspFrameCodec.encode(ack),ack,true);}
            if(m.getName().equals("close"))throw new java.io.IOException("cleanup failed");
            throw new AssertionError(m.getName());
        });
        var cli=new DspRaiderCli((a,b,c,d,e)->session,new ChangeApplier(s->snapshots.removeFirst()));
        Path changes=temporary.resolve("ready.json");
        Files.writeString(changes,"{\"device_version\":\"FIR408 V0113P\",\"changes\":[{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}]}");
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        int code=cli.run(new String[]{"apply","--in",FIXTURE,"--changes",changes.toString(),"--format","json"},new PrintStream(out),new PrintStream(err));
        assertEquals(1,code,err::toString);assertEquals(1,calls.get());
        var report=new ObjectMapper().readTree(out.toString());
        assertEquals("APPLIED",report.path("entries").get(0).path("status").asText());
        assertTrue(report.path("error").asText().contains("cleanup"));
    }

    @Test void cleanupFailureRetainsOriginalStaleBaselineReason() throws Exception {
        var baseline=SnapshotFiles.read(Path.of(FIXTURE));
        var blocks=new TreeMap<>(baseline.blocks());byte[] payload=blocks.get(15).payload();payload[31]=(byte)250;payload[32]=0;
        blocks.put(15,new ProxyResponse(DspFrameCodec.encode(payload),payload,true));var stale=new Snapshot(baseline.identity(),blocks);
        DspSession session=(DspSession)Proxy.newProxyInstance(DspSession.class.getClassLoader(),new Class<?>[]{DspSession.class},(p,m,args)->{
            if(m.getName().equals("close"))throw new java.io.IOException("cleanup failed");
            throw new AssertionError("No write should be sent: "+m.getName());
        });
        var cli=new DspRaiderCli((a,b,c,d,e)->session,new ChangeApplier(s->stale));
        Path changes=temporary.resolve("stale.json");
        Files.writeString(changes,"{\"device_version\":\"FIR408 V0113P\",\"changes\":[{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}]}");
        var out=new ByteArrayOutputStream();
        assertEquals(1,cli.run(new String[]{"apply","--in",FIXTURE,"--changes",changes.toString(),"--format","json"},new PrintStream(out),System.err));
        var error=new ObjectMapper().readTree(out.toString()).path("error").asText();
        assertTrue(error.contains("baseline"),error);assertTrue(error.contains("cleanup"),error);
    }

    @Test void outputFailureAfterApplicationFallsBackToCompleteReport() throws Exception {
        var before=SnapshotFiles.read(Path.of(FIXTURE));var snapshots=new ArrayDeque<Snapshot>();snapshots.add(before);
        var blocks=new TreeMap<>(before.blocks());byte[] payload=blocks.get(15).payload();payload[31]=(byte)250;payload[32]=0;
        blocks.put(15,new ProxyResponse(DspFrameCodec.encode(payload),payload,true));snapshots.add(new Snapshot(before.identity(),blocks));
        Path output=temporary.resolve("created-concurrently.json");var calls=new AtomicInteger();
        DspSession session=(DspSession)Proxy.newProxyInstance(DspSession.class.getClassLoader(),new Class<?>[]{DspSession.class},(p,m,args)->{
            if(m.getName().equals("sendPayload")){calls.incrementAndGet();Files.writeString(output,"other owner's file");byte[] ack={1,0,1,1};return new ProxyResponse(DspFrameCodec.encode(ack),ack,true);}
            if(m.getName().equals("close"))return null;
            throw new AssertionError(m.getName());
        });
        var cli=new DspRaiderCli((a,b,c,d,e)->session,new ChangeApplier(s->snapshots.removeFirst()));
        Path changes=temporary.resolve("race.json");
        Files.writeString(changes,"{\"device_version\":\"FIR408 V0113P\",\"changes\":[{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}]}");
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        assertEquals(1,cli.run(new String[]{"apply","--in",FIXTURE,"--changes",changes.toString(),"--format","json","--out",output.toString()},new PrintStream(out),new PrintStream(err)));
        assertEquals(1,calls.get());assertEquals("other owner's file",Files.readString(output));
        var report=new ObjectMapper().readTree(out.toString());
        assertEquals("APPLIED",report.path("entries").get(0).path("status").asText());assertTrue(err.toString().contains("report"));
    }

    @Test void knownInvalidOutputDestinationIsRejectedBeforeSessionCreation() throws Exception {
        Path parent=temporary.resolve("file-as-parent");Files.writeString(parent,"existing file");
        Path request=temporary.resolve("valid.json");
        Files.writeString(request,"{\"device_version\":\"FIR408 V0113P\",\"changes\":[{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}]}");
        var cli=new DspRaiderCli((a,b,c,d,e)->{throw new AssertionError("Invalid destination opened session");});
        var errors=new ByteArrayOutputStream();
        assertEquals(1,cli.run(new String[]{"apply","--in",FIXTURE,"--changes",request.toString(),"--out",parent.resolve("report.json").toString()},System.out,new PrintStream(errors)));
        assertTrue(errors.toString().contains("Output"));
        assertEquals("existing file",Files.readString(parent));
    }
}
