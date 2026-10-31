package de.drremote.dsp408.script;

import de.drremote.dsp408.dspd.DspdCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ProxyStatus;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.Hex;
import de.drremote.dsp408.snapshot.SnapshotFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ScriptParametersTest {
    @TempDir Path temporary;
    private final CheckedDspScriptEngine engine = new CheckedDspScriptEngine((a,b,c,d,e) -> {
        throw new AssertionError("Offline parameter operations opened a connection");
    });

    @Test void readsEvidenceAndPlansQuantizedChangesWithoutConnecting() throws Exception {
        var output=new ByteArrayOutputStream();
        engine.executeText("parameters.dspd", """
                let snapshot = loadSnapshot("src/test/resources/live/fir408-v0113p-20261031.json");
                let gain = parameter(snapshot, "Out2", "gain");
                assert(gain.status == "CONFIRMED");
                assert(gain.value == -6);
                assert(gain.unit == "dB");
                let mute = parameter(snapshot, "InA", "mute");
                assert(mute.status == "INFERRED");
                assert(mute.value == null);
                let change = planChange(snapshot, "Out2", "gain", -3.04);
                assert(change.status == "READY");
                assert(change.targetValue == -3);
                assert(change.rounded == true);
                print(change.payloadHex);
                assert(planChange(snapshot, "Out3", "gain", -6).status == "UNCHANGED");
                assert(planChange(snapshot, "InA", "mute", true).status == "BLOCKED");
                """,new PrintStream(output));
        assertEquals("00 01 04 34 05 FA 00"+System.lineSeparator(),output.toString());
    }

    @Test void invalidLiteralChannelKindAndValueAreRejectedDuringPreflight() {
        for(String source:new String[]{
                "connect(); parameter(null,\"Out99\",\"gain\");",
                "connect(); planChange(null,\"Out2\",\"gaim\",0);",
                "connect(); planChange(null,\"Out2\",\"gain\",13);",
                "connect(); planChange(null,\"Out2\",\"delay\",-1);",
                "connect(); planChange(null,\"Out2\",\"mute\",2);"}) {
            var result=DspdCompiler.compile(source);
            assertFalse(result.ok(),source);
            assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("E2004")),result::toString);
        }
    }

    @Test void checkedScriptAppliesThroughRealReadFlowAndExposesVerifiedEntries() throws Exception {
        Path fixture=Path.of("src/test/resources/live/fir408-v0113p-20261031.json");
        var document=new ObjectMapper().readTree(fixture.toFile());
        var blocks=new TreeMap<>(SnapshotFiles.read(fixture).blocks());
        var writes=new AtomicInteger();var closes=new AtomicInteger();
        DspSession session=(DspSession)Proxy.newProxyInstance(DspSession.class.getClassLoader(),new Class<?>[]{DspSession.class},(p,m,args)->{
            return switch(m.getName()) {
                case "connect" -> null;
                case "status" -> new ProxyStatus(true,true,"test session");
                case "handshakeInit" -> SnapshotFiles.checkedFrame(document.at("/handshake/init"),0x10,null);
                case "deviceInfo" -> SnapshotFiles.checkedFrame(document.at("/handshake/device"),0x13,null);
                case "systemInfo" -> SnapshotFiles.checkedFrame(document.at("/handshake/system"),0x2c,null);
                case "readBlock" -> blocks.get((Integer)args[0]);
                case "sendPayload" -> {
                    assertEquals("00 01 04 34 05 FA 00",Hex.bytes((byte[])args[0]));writes.incrementAndGet();
                    byte[] changed=blocks.get(15).payload();changed[31]=(byte)250;changed[32]=0;
                    blocks.put(15,new ProxyResponse(DspFrameCodec.encode(changed),changed,true));
                    byte[] ack={1,0,1,1};yield new ProxyResponse(DspFrameCodec.encode(ack),ack,true);
                }
                case "close" -> {closes.incrementAndGet();yield null;}
                default -> throw new AssertionError("Unexpected session method: "+m.getName());
            };
        });
        Path request=temporary.resolve("apply.json");
        Files.writeString(request,"{\"device_version\":\"FIR408 V0113P\",\"changes\":[{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}]}");
        var checked=new CheckedDspScriptEngine((a,b,c,d,e)->session);
        checked.executeText("apply.dspd","connect(); let snapshot=loadSnapshot(\""+fixture+"\"); let applied=applyChanges(snapshot,\""+request+"\"); assert(applied.ok); assert(applied.entries[0].readback.value == -3);",System.out);
        assertEquals(1,writes.get());assertEquals(1,closes.get());
    }
}
