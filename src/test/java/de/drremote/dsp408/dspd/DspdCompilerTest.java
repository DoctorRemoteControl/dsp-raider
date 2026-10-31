package de.drremote.dsp408.dspd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DspdCompilerTest {
    @Test void longFlatExpressionChainsAreDiagnosedWithoutStackOverflow() {
        String source="print("+String.join("+",java.util.Collections.nCopies(12000,"\"a\""))+");";
        var result=assertDoesNotThrow(()->DspdCompiler.compile(source));
        assertFalse(result.ok());assertNull(result.program());
    }

    @Test void legacyUnquotedPhrasesRemainStringsAndKnownBadNumericPhrasesFail() {
        var valid=DspdCompiler.compile("let text=hello world\nprint hello world\nsave-text \"never.txt\" hello world\nreadBlock(31.9); readBlock(true); sleep(0.9);");
        assertTrue(valid.ok(),valid.diagnostics().toString());
        assertFalse(DspdCompiler.compile("readBlock(1 2);").ok());
    }

    @Test void variablesWhoseNamesCollideWithFunctionsRequireExplicitReference() {
        assertFalse(DspdCompiler.compile("let raw=1; print(raw);").ok());
        assertTrue(DspdCompiler.compile("let raw=1; print($raw);").ok());
    }
    @Test void acceptsBothSyntaxStylesDynamicDeviceValuesAndRuntimeNumericCoercion() {
        var result=DspdCompiler.compile("""
                connect();
                let block = read-block "0x01"
                let value = u8(block.payload, 0);
                readBlock(value);
                if (value > 0) {
                    print("value=${value}");
                }
                for i in 0..2
                    read-block $i
                end
                """);
        assertTrue(result.ok(),result.diagnostics().toString());assertNotNull(result.program());
    }

    @Test void checksUnreachableBranchesWithoutExecutingThem() {
        var result=DspdCompiler.compile("if (false) { madeUpFunction(); } else { print(1); }");
        assertFalse(result.ok());assertNull(result.program());
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("E2001")));
    }

    @ParameterizedTest @ValueSource(strings={
            "readBlock();","readBlock(0,1);","connect(1);","slice(bytes(\"00\"),0);",
            "let x = $missing;","print(\"${missing}\");","readBlock(null);","sleep(true);",
            "readBlocks(256,256);","readBlocks(-1,0);","readBlock(\"not a number\");",
            "sleep(-1);","let x = 2147483648;",
            "if true { let x = 1; } print($x);"
    })
    void rejectsStaticallyProvableNameArityReferenceAndValueErrors(String source) {
        var result=DspdCompiler.compile(source);
        assertFalse(result.ok(),source);assertNull(result.program(),source);
        assertFalse(result.diagnostics().isEmpty(),source);
    }

    @Test void explicitVariablesAndInterpolationReportTheirActualSourcePositions() {
        var result=DspdCompiler.compile("// comment\n\nprint(\"value=${missing}\");");
        var diagnostic=result.diagnostics().getFirst();
        assertEquals(3,diagnostic.position().line());assertEquals(14,diagnostic.position().column());
    }

    @Test void variablesFromAllBranchesAndInclusiveLoopBodyRemainAvailable() {
        var result=DspdCompiler.compile("""
                if true
                  let x = 1
                else
                  let x = 2
                end
                print($x)
                for i in 1..0
                  let bodyValue = $i
                end
                print($bodyValue)
                """);
        assertTrue(result.ok(),result.diagnostics().toString());
        assertFalse(DspdCompiler.compile("for i in 0..1\nprint($i)\nend\nprint($i)").ok());
    }

    @Test void preservesUnquotedLegacyStringsAndDoesNotEvaluateWritesOrSaveCalls() {
        var result=DspdCompiler.compile("print hello\nwrite(\"00 01 03 35 03 01\");\nsaveText(\"never-created.txt\",\"value\");\npauseForUser();\nsleep(60000);");
        assertTrue(result.ok(),result.diagnostics().toString());
    }
}
