package de.drremote.dsp408.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.drremote.dsp408.protocol.DspFrameCodec;
import de.drremote.dsp408.protocol.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ChangePlanCliTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private final ByteArrayOutputStream output=new ByteArrayOutputStream(),errors=new ByteArrayOutputStream();
    private final DspRaiderCli cli=new DspRaiderCli((a,b,c,d,e)->{throw new AssertionError("Plan opened a session");});

    private int run(String... args) {return cli.run(args,new PrintStream(output),new PrintStream(errors));}
    private String text() {return output.toString(StandardCharsets.UTF_8);}

    private Path reference() throws Exception {
        Path file=temp.resolve("snapshot.json");
        try(var input=getClass().getResourceAsStream("/live/fir408-v0113p-20261031.json")){Files.copy(input,file);}
        return file;
    }

    private Path changes(String version,String entries) throws Exception {
        Path file=temp.resolve("changes.json");Files.writeString(file,"{\"device_version\":\""+version+"\",\"changes\":["+entries+"]}");return file;
    }

    private Path changes(String entries) throws Exception {return changes("FIR408 V0113P",entries);}

    @Test void textPreviewShowsBaselineTargetsQuantizationAndPreparedCommandsOffline() throws Exception {
        Path snapshot=reference(),request=changes("""
                {"channel":"Out2","parameter":"gain","value":-3},
                {"channel":"Out8","parameter":"delay","value":0.001},
                {"channel":"InD","parameter":"mute","value":true},
                {"channel":"InA","parameter":"mute","value":true}
                """);
        assertEquals(0,run("plan","--in",snapshot.toString(),"--changes",request.toString()),errors.toString());
        assertTrue(text().contains("Out2 Gain [READY]: -6.0 dB -> -3.0 dB"),text());
        assertTrue(text().contains("00 01 04 34 05 FA 00"),text());
        assertTrue(text().contains("0.001 ms"),text());
        assertTrue(text().contains("rounded: yes"),text());
        assertTrue(text().contains("InA Mute [BLOCKED]"),text());
        assertTrue(text().contains("ready=2 unchanged=1 blocked=1"),text());
        assertTrue(text().contains("Storage raw value: 220"),text());
        String inferred=text().substring(text().indexOf("InA Mute [BLOCKED]"));
        assertTrue(inferred.contains("Storage raw value: 0"),inferred);
        assertTrue(inferred.contains("Read evidence: INFERRED DSP408Controller/"),inferred);
        assertTrue(inferred.contains("Write evidence: CONFIRMED tracks-dsp-raider/"),inferred);
    }

    @Test void jsonPreviewPreservesRequestedPrecisionAndOmitsPayloadsForBlockedOrNoOpEntries() throws Exception {
        Path snapshot=reference(),request=changes("""
                {"channel":"Out2","parameter":"gain","value":-3.04},
                {"channel":"Out8","parameter":"delay","value":0.001},
                {"channel":"InA","parameter":"mute","value":true},
                {"channel":"Out3","parameter":"gain","value":-3.1},
                {"channel":"Out4","parameter":"gain","value":-0.1},
                {"channel":"Out5","parameter":"gain","value":0.1}
                """);
        assertEquals(0,run("plan","--in",snapshot.toString(),"--changes",request.toString(),"--format","json"));
        var json=mapper.readTree(output.toByteArray());assertTrue(json.path("preview_only").asBoolean());
        assertEquals(1,json.path("schema_version").asInt());assertEquals("change_plan",json.path("type").asText());
        var first=json.path("entries").get(0);
        assertEquals(-6,first.path("before_value").asDouble());
        assertEquals(-3.04,first.path("requested_value").asDouble());assertEquals(-3,first.path("target_value").asDouble());
        assertTrue(first.path("rounded").asBoolean());assertEquals(250,first.path("target_write_raw").asInt());
        assertEquals("00 01 04 34 05 FA 00",first.path("payload_hex").asText());
        assertEquals("CONFIRMED",first.path("read_evidence").path("level").asText());
        assertTrue(json.path("entries").get(1).path("payload_hex").isNull());
        assertTrue(json.path("entries").get(2).path("before_value").isNull());
        assertTrue(json.path("entries").get(2).path("payload_hex").isNull());
        assertFalse(json.path("entries").get(3).path("rounded").asBoolean());
        assertFalse(json.path("entries").get(4).path("rounded").asBoolean());
        assertFalse(json.path("entries").get(5).path("rounded").asBoolean());
    }

    @Test void writesNewReportWithoutChangingInputsAndRefusesExistingOutput() throws Exception {
        Path snapshot=reference(),request=changes("{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}");
        byte[] original=Files.readAllBytes(snapshot),requested=Files.readAllBytes(request);Path report=temp.resolve("plan.json");
        assertEquals(0,run("plan","--in",snapshot.toString(),"--changes",request.toString(),"--format","json","--out",report.toString()));
        assertEquals(1,mapper.readTree(report.toFile()).path("summary").path("ready").asInt());
        byte[] first=Files.readAllBytes(report);
        assertEquals(1,run("plan","--in",snapshot.toString(),"--changes",request.toString(),"--out",report.toString()));
        assertArrayEquals(first,Files.readAllBytes(report));assertArrayEquals(original,Files.readAllBytes(snapshot));assertArrayEquals(requested,Files.readAllBytes(request));
    }

    @Test void versionMismatchAndInvalidValueProduceNoPartialReport() throws Exception {
        Path snapshot=reference(),request=changes("FIR408 V0115","{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}");
        Path report=temp.resolve("rejected.json");
        assertEquals(2,run("plan","--in",snapshot.toString(),"--changes",request.toString(),"--out",report.toString()));
        assertFalse(Files.exists(report));assertEquals("",text());
        request=changes("{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":13}");
        assertEquals(2,run("plan","--in",snapshot.toString(),"--changes",request.toString(),"--out",report.toString()));
        assertFalse(Files.exists(report));
    }

    @Test void missingRequestedBlockIsBlockedRatherThanUsingDefaultValues() throws Exception {
        Path snapshot=reference(),request=changes("{\"channel\":\"Out1\",\"parameter\":\"gain\",\"value\":-3}");
        var data=(ObjectNode)mapper.readTree(snapshot.toFile());((ArrayNode)data.path("blocks")).remove(13);
        ((ObjectNode)data.path("summary")).put("blocks_total",31);mapper.writeValue(snapshot.toFile(),data);
        assertEquals(0,run("plan","--in",snapshot.toString(),"--changes",request.toString(),"--format","json"));
        var entry=mapper.readTree(output.toByteArray()).path("entries").get(0);
        assertEquals("BLOCKED",entry.path("status").asText());assertTrue(entry.path("before_value").isNull());
        assertTrue(entry.path("payload_hex").isNull());
    }

    @Test void malformedPresentBlockCannotSupplyConfirmedPlanningValues() throws Exception {
        Path snapshot=reference(),request=changes("{\"channel\":\"Out2\",\"parameter\":\"gain\",\"value\":-3}");
        var data=(ObjectNode)mapper.readTree(snapshot.toFile());var block=(ObjectNode)data.path("blocks").get(15);
        byte[] payload=Hex.parse(block.path("payload_hex").asText());payload=Arrays.copyOf(payload,payload.length-1);payload[2]--;
        block.put("payload_hex",Hex.bytes(payload)).put("raw_hex",Hex.bytes(DspFrameCodec.encode(payload))).put("len",payload.length);
        mapper.writeValue(snapshot.toFile(),data);
        assertEquals(1,run("plan","--in",snapshot.toString(),"--changes",request.toString()));assertEquals("",text());
    }

    @Test void rejectsNetworkApplyAndUnknownFormatOptionsBeforeReadingFiles() {
        assertEquals(2,run("plan","--in","missing","--changes","missing","--stream-host","192.168.0.166"));
        assertEquals(2,run("plan","--in","missing","--changes","missing","--apply"));
        assertEquals(2,run("plan","--in","missing","--changes","missing","--format","yaml"));
        assertEquals(2,run("plan","--in","missing"));
    }
}
