package de.drremote.dsp408.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CheckedDspScriptEngineTest {
    @TempDir Path temporary;

    @Test void rejectsEntireProgramBeforeAnyFunctionCanRun() throws Exception {
        var connections = new AtomicInteger();
        var engine = new CheckedDspScriptEngine((a,b,c,d,e) -> {
            connections.incrementAndGet();
            throw new AssertionError("Preflight must precede session creation");
        });
        Path output = temporary.resolve("must-not-exist.txt");
        String source = "saveText(\"" + output + "\", \"bad\"); connect(); if (false) { unknownFunction(); }";
        var failure = assertThrows(Exception.class, () -> engine.executeText("invalid.dspd", source, System.out));
        assertTrue(failure.getMessage().contains("invalid.dspd:1:"));
        assertEquals(0, connections.get());
        assertFalse(Files.exists(output));
    }

    @Test void executesNestedCallsPathsInterpolationAndLegacyPhrases() throws Exception {
        var output = new ByteArrayOutputStream();
        var variables = offline().executeText("values.dspd", """
                let bytesValue = bytes("00 01 FF");
                let part = slice(bytesValue, 1, 2);
                assert(u16le(part, 0) == 65281);
                let message = hello len world;
                print("${message}: ${part[1]}");
                assert(true or status());
                assert(not false and contains(message, "len"));
                """, new PrintStream(output));
        assertEquals("hello len world: 255" + System.lineSeparator(), output.toString());
        assertEquals("hello len world", variables.get("message"));
        assertEquals(true, variables.get("_"));
    }

    @Test void loopsAreInclusiveDescendingAndRestoreTheirVariables() throws Exception {
        Path output = temporary.resolve("last.txt");
        var variables = offline().executeText("loops.dspd", """
                let channel = "outside";
                for (let channel in 2..0) {
                    let last = channel;
                }
                assert(channel == "outside");
                assert(last == 0);
                for index in 4..4
                    let single = index
                end
                assert(single == 4);
                """ + "saveText(\"" + output + "\", last);", System.out);
        assertEquals("0", Files.readString(output));
        assertEquals("outside", variables.get("channel"));
        assertFalse(variables.containsKey("index"));
    }

    @Test void failuresRetainPhysicalPositionAndNestedCause() {
        var failure = assertThrows(Exception.class, () -> offline().executeText("physical.dspd", """
                // first physical line

                let value = bytes("00"); assert(u8(value, 9) == 0);
                """, System.out));
        assertTrue(failure.getMessage().contains("physical.dspd:3:33"), failure::getMessage);
        assertNotNull(failure.getCause());
    }

    @Test void modernAndLegacyControlFlowHaveTheSameResults() throws Exception {
        String source = """
                let message = "original";
                if false
                    let message = "wrong"
                else if true
                    let message = upper("chosen")
                else
                    let message = "wrong"
                end
                assert $message == "CHOSEN"
                print $message
                """;
        var out = new ByteArrayOutputStream();
        var variables = offline().executeText("legacy.dspd", source, new PrintStream(out));
        assertEquals("CHOSEN", variables.get("message"));
        assertEquals("CHOSEN" + System.lineSeparator(), out.toString());
    }

    @Test void loopUpdatesLastResultBeforeEachIteration() throws Exception {
        var variables=offline().executeText("last-result.dspd", """
                let previous = "old";
                for (let channel in 1..2) {
                    let current = $_;
                    assert(current == channel);
                }
                """,System.out);
        assertEquals(2,variables.get("current"));
    }

    @Test void chainedMembersAfterCallsAndIndexesResolveIndividually() throws Exception {
        var output=new ByteArrayOutputStream();
        offline().executeText("members.dspd", """
                let snapshot = loadSnapshot("src/test/resources/live/fir408-v0113p-20261031.json");
                print(planChange(snapshot, "Out2", "gain", -3).before.value);
                """,new PrintStream(output));
        assertEquals("-6.0"+System.lineSeparator(),output.toString());
    }

    @Test void invalidMemberAndLegacyWriteSyntaxNeverReachesConnection() {
        for(String source:new String[]{"connect(); let bad = bytes(\"01\").;", "connect(); write 00 expect;",
                "connect(); write expect 1;", "connect(); write 00 expect bogus;", "connect(); write 00 expect 1 extra;"}) {
            assertThrows(DspdCompilationException.class,()->offline().executeText("invalid.dspd",source,System.out),source);
        }
    }

    @Test void legacyNumericBytesRemainHexAndMatchExistingInterpreter() throws Exception {
        Path legacy=temporary.resolve("legacy-bytes.txt");
        Path checked=temporary.resolve("checked-bytes.txt");
        String source="let number = 10; let first = bytes 10; let second = bytes $number; assert hex(first) == hex(second); ";
        new DspScriptEngine("unused",1,"unused",2,false).executeText(source+"saveText(\""+legacy+"\",hex(first));");
        offline().executeText("bytes.dspd",source+"saveText(\""+checked+"\",hex(first));",System.out);
        assertEquals("10",Files.readString(legacy));assertEquals("10",Files.readString(checked));
    }

    private CheckedDspScriptEngine offline() {
        return new CheckedDspScriptEngine((a,b,c,d,e) -> { throw new AssertionError("Unexpected connection"); });
    }
}
