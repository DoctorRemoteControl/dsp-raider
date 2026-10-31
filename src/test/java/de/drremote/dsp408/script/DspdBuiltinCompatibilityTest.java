package de.drremote.dsp408.script;

import de.drremote.dsp408.dspd.DspdBuiltins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class DspdBuiltinCompatibilityTest {
    @Test void rawNameCollisionIsAnExistingRuntimeErrorWhileDollarReferenceWorks()throws Exception {
        try(var runtime=new ScriptRuntime("unused",1,"unused",2,false,(a,b,c,d,e)->{throw new AssertionError("Unexpected session");})) {
            runtime.variables.put("raw",42);
            var functions=new ScriptFunctions(runtime,new ScriptValueResolver(runtime));
            assertThrows(IllegalArgumentException.class,()->functions.evaluateExpression(java.util.List.of("raw")));
            assertEquals(42,functions.evaluateExpression(java.util.List.of("$raw")));
        }
    }
    @ParameterizedTest @CsvSource({"readBlock,read-block","READBLOCK,read-block","guiActionCapture,gui-action-capture",
            "pauseForUser,pause","lastWriteExcluding,last-write-excluding","read-block,read-block",
            "payloadSeries,payload-series","readSaveConfig,read-save-config","startsWith,starts-with","badFunction,badFunction"})
    void namesAndAliasesMatchIndependentHistoricalExpectations(String alias,String expected) {
        assertEquals(expected,DspdBuiltins.normalizeFunctionName(alias));
        assertEquals(!alias.equals("badFunction"),ScriptFunctions.isExpressionStarter(alias));
    }

    @Test void runtimeDispatchStillEvaluatesRepresentativeAliasedCalls()throws Exception {
        try(var runtime=new ScriptRuntime("unused",1,"unused",2,false,(a,b,c,d,e)->{throw new AssertionError("Unexpected session");})) {
            var functions=new ScriptFunctions(runtime,new ScriptValueResolver(runtime));
            assertEquals("01 02",functions.evaluateExpression(ScriptTokenizer.tokenize("hex(bytes(\"01 02\"))")));
            assertEquals(513,functions.evaluateExpression(ScriptTokenizer.tokenize("u16le(bytes(\"01 02\"),0)")));
            assertEquals(true,functions.evaluateExpression(ScriptTokenizer.tokenize("startsWith(\"abc\",\"a\")")));
        }
    }
}
