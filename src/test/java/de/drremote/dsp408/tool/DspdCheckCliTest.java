package de.drremote.dsp408.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DspdCheckCliTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
    private final DspRaiderCli cli=new DspRaiderCli((a,b,c,d,e)->{throw new AssertionError("Check opened a session");});
    private int run(String... args){return cli.run(args,new PrintStream(out),new PrintStream(err));}
    private Path script(String source)throws Exception{Path p=temp.resolve("input.dspd");Files.writeString(p,source);return p;}

    @Test void checkingDeviceWriteSleepPauseAndSaveCallsHasNoSideEffects()throws Exception {
        Path sideEffect=temp.resolve("must-not-exist.txt");
        String source="connect(); sleep(60000); pauseForUser(); write(\"00 01 03 35 03 01\"); saveText(\""+sideEffect+"\",\"data\");";
        Path input=script(source);
        assertEquals(0,run("check","--in",input.toString()),err.toString());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("OK"));assertFalse(Files.exists(sideEffect));
        assertEquals(source,Files.readString(input));
    }

    @Test void invalidLateStatementReturnsFailureReportWithPhysicalLineAndColumn()throws Exception {
        Path input=script("write(\"00\");\n# comment\n\n  let broken = ;\n"),report=temp.resolve("invalid.json");
        assertEquals(1,run("check","--in",input.toString(),"--format","json","--out",report.toString()));
        var json=new ObjectMapper().readTree(report.toFile());
        assertFalse(json.path("ok").asBoolean());assertEquals("dspd_check",json.path("type").asText());
        assertEquals(1,json.path("schema_version").asInt());
        var diagnostic=json.path("diagnostics").get(0);assertEquals(4,diagnostic.path("line").asInt());
        assertEquals(16,diagnostic.path("column").asInt());assertTrue(diagnostic.path("offset").asInt()>20);
    }

    @Test void textErrorsUseSourceFileAndRealPosition()throws Exception {
        Path input=script("//comment\n\nmadeUp();");
        assertEquals(1,run("check","--in",input.toString()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(input+":3:1:"),out.toString());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("E2001"));
    }

    @Test void validJsonAndReportFileAreProducedWithoutChangingScriptOrExistingOutput()throws Exception {
        Path input=script("let x=1; print($x);"),report=temp.resolve("valid.json");byte[] original=Files.readAllBytes(input);
        assertEquals(0,run("check","--in",input.toString(),"--format","json","--out",report.toString()));
        var json=new ObjectMapper().readTree(report.toFile());assertTrue(json.path("ok").asBoolean());
        assertEquals(2,json.path("statement_count").asInt());assertTrue(json.path("diagnostics").isEmpty());
        byte[] saved=Files.readAllBytes(report);
        assertEquals(1,run("check","--in",input.toString(),"--out",report.toString()));
        assertArrayEquals(saved,Files.readAllBytes(report));assertArrayEquals(original,Files.readAllBytes(input));
    }

    @Test void rejectsNetworkExecutionMissingInputAndUnknownFormatsBeforeReading() {
        assertEquals(2,run("check","--in","missing","--stream-host","192.168.0.166"));
        assertEquals(2,run("check","--in","missing","--apply"));
        assertEquals(2,run("check","--in","missing","--format","yaml"));
        assertEquals(2,run("check"));
    }

    @Test void missingFileDoesNotCreateAReport() {
        Path report=temp.resolve("missing.json");
        assertEquals(1,run("check","--in",temp.resolve("missing.dspd").toString(),"--out",report.toString()));
        assertFalse(Files.exists(report));
    }
}
