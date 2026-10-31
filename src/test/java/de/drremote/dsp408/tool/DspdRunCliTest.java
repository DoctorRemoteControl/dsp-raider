package de.drremote.dsp408.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DspdRunCliTest {
    @TempDir Path temporary;

    @Test void runsCheckedOfflineProgramIntoProvidedOutput() throws Exception {
        Path source = temporary.resolve("offline.dspd");
        Files.writeString(source,"let message = upper(\"ready\"); print(message);\n");
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var cli = new DspRaiderCli((a,b,c,d,e) -> {throw new AssertionError("Unexpected session");});
        int code = cli.run(new String[]{"run","--in",source.toString()}, new PrintStream(output),new PrintStream(errors));
        assertEquals(0,code,errors::toString);
        assertEquals("READY"+System.lineSeparator(),output.toString());
    }

    @Test void invalidProgramReturnsFailureBeforeConnection() throws Exception {
        Path source = temporary.resolve("invalid.dspd");
        Files.writeString(source,"connect(); if(false) { unknownFunction(); }\n");
        var errors = new ByteArrayOutputStream();
        var cli = new DspRaiderCli((a,b,c,d,e) -> {throw new AssertionError("Unexpected session");});
        assertEquals(1,cli.run(new String[]{"run","--in",source.toString()},System.out,new PrintStream(errors)));
        assertTrue(errors.toString().contains("invalid.dspd:1:"));
    }
}
