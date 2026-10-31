package de.drremote.dsp408.dspd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DspdReviewRegressionTest {
    private void valid(String source){var result=DspdCompiler.compile(source);assertTrue(result.ok(),result.diagnostics().toString());}
    private void invalid(String source){var result=DspdCompiler.compile(source);assertFalse(result.ok(),source);assertNull(result.program());}

    @Test void rawBlockLanguageSupportsByteRangesAndSingleReadsKeepExistingMasking() {
        valid("readBlocks(32,32); readBlocks(255,255); readConfigBlocks(255); readSaveConfig(\"unused\",32,255);");
        valid("readBlock(32); readBlock(256); readBlock(-1);");
        invalid("readBlocks(256,256);");invalid("readBlocks(2,1);");invalid("readConfigBlocks(2,1);");
    }
    @Test void loopCarriedValuesAreWidenedBeforeAnyIterationCanBeSimulated() {
        valid("let x=\"bad\"; let y=0; for i in 0..1 { let y=$x; let x=0; } sleep($y);");
        // First iteration has valid y, later iteration has bad y: do not claim a constant result.
        valid("let x=0; for i in 0..1 { sleep($x); let x=\"bad\"; }");
    }
    @Test void implicitLastValueFollowsRuntimeWrites() {
        valid("let _=\"bad\"; print(0); sleep($_);");
        valid("let _=\"bad\"; let x=0; sleep($_);");
        valid("let _=\"bad\"; for i in 0..0 { sleep($_); }");
        valid("let _=\"bad\"; status(); sleep($_);");
        invalid("let _=\"bad\"; sleep($_);");
    }
    @ParameterizedTest @ValueSource(strings={
            "let xs=bytes(\"00\"); print(\"${xs[$missing]}\");",
            "let xs=bytes(\"00\"); print(\"${xs[}\");",
            "let xs=bytes(\"00\"); print(\"${xs[]}\");",
            "let xs=bytes(\"00\"); print(\"${xs..len}\");"
    }) void interpolationPathsAreParsedAndIndexesAreChecked(String source){invalid(source);}
    @Test void interpolationIndexDiagnosticRetainsTheOriginalOffset() {
        String source="let xs=bytes(\"00\");\nprint(\"${xs[$missing]}\");";
        var result=DspdCompiler.compile(source);assertFalse(result.ok());
        var diagnostic=result.diagnostics().stream().filter(d->d.code().equals("E2003")).findFirst().orElseThrow();
        assertEquals(source.indexOf("$missing"),diagnostic.position().offset());
        valid("let xs=bytes(\"00\"); let i=0; print(\"${xs[$i]}\");");
    }
    @Test void plainIndexesUseResolverValuesInsteadOfFunctionDispatchOrArithmetic() {
        valid("let xs=bytes(\"00\"); let len=0; print($xs[len]);");
        invalid("let xs=bytes(\"00\"); let i=0; print($xs[$i+1]);");
    }
    @Test void builtinWordsInsidePlainLegacyPhrasesAreNotCalls() {
        valid("let message=please sleep; print($message); print please read-block 1;");
        valid("let message=please madeUpFunction(1); print($message);");
    }
    @ParameterizedTest @ValueSource(strings={
            "hex(bytes(\"00\"),\"bad\",1);","readBlockPayload(readBlocks(0,0),\"bad\");",
            "captureFrame(guiCapture(),\"bad\");","u16Series(writes(guiCapture()),\"bad\");",
            "writesByCommand(guiCapture(),\"bad\");","recentWrites(guiCapture(),\"bad\");",
            "guiEndCapture(\"bad\");","attachSession(\"bad\",1);"
    }) void supportedNumericPositionsRejectProvenBadCoercions(String source){invalid(source);}
    @ParameterizedTest @ValueSource(strings={"gui-capture","gui-action-capture","gui-capture 1 2 3 note","gui-action-capture 1 2 3 note"})
    void legacyGuiCaptureHasItsOwnArity(String source){invalid(source);}
    @Test void modernCaptureStillAcceptsOptionalNoteAndThreeWindows() {
        valid("guiCapture(); guiActionCapture(); guiCapture(\"note\",1,2,3); guiActionCapture(\"note\",1,2,3);");
        invalid("guiCapture(1,2,3,4);");
    }
}
