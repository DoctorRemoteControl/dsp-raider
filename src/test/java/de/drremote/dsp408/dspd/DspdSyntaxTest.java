package de.drremote.dsp408.dspd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DspdSyntaxTest {
    @Test void parsesMultilineStringConcatenationInsideCalls() {
        var parsed=DspdParser.parse("""
                let x = 1;
                saveText("out.txt",
                  "first=${x}\\n"
                  + "second="
                  + hex(bytes("00"))
                );
                """);
        assertTrue(parsed.diagnostics().isEmpty(),parsed.diagnostics().toString());
    }

    @Test void lexerKeepsActualOffsetsAcrossCommentsCrLfSemicolonsAndMultilineCalls() {
        String source="// comment\r\n\r\nprint(\"# // ; }\"); readBlock(\r\n  0x01\r\n);";
        var lexed=DspdLexer.lex(source);
        assertTrue(lexed.diagnostics().isEmpty());
        var read=lexed.tokens().stream().filter(t->t.text().equals("readBlock")).findFirst().orElseThrow();
        assertEquals(new SourcePosition(33,3,20),read.position());
        var hex=lexed.tokens().stream().filter(t->t.text().equals("0x01")).findFirst().orElseThrow();
        assertEquals(new SourcePosition(47,4,3),hex.position());
    }

    @Test void parsesNestedModernAndLegacyBlocksAndProducesImmutableAst() {
        var result=DspdParser.parse("""
                let x = 1;
                if (x == 1) {
                    for (let i in 0..2) {
                        print(readBlock(i).payloadHex);
                    }
                } else if (not false) {
                    print("other");
                } else {
                    print("last");
                }
                for j in 2..0
                  if $j != 1
                    print $j
                  else
                    print "middle"
                  end
                end
                """);
        assertTrue(result.diagnostics().isEmpty(),result.diagnostics().toString());
        assertEquals(3,result.program().statements().size());
        assertThrows(UnsupportedOperationException.class,()->result.program().statements().clear());
    }

    @Test void parsesLegacyNestedCallsAndIndexedMemberPaths() {
        var result=DspdParser.parse("let a = read-block 0\nlet b = hex $a.payload\nprint($a.payload[0]);\n");
        assertTrue(result.diagnostics().isEmpty(),result.diagnostics().toString());
        assertEquals(3,result.program().statements().size());
    }

    @ParameterizedTest @ValueSource(strings={
            "print(\"unterminated)","print((1);","print(1]);","readBlock(,)",
            "let = 1;","let x 1;","let x = ;","if true { print(1);",
            "else { print(1); }","end","}","for i in 0.. { print(i); }",
            "if true { end","if true\n print(1)\n}\n","if true {} else {} else {}",
            "print(1,,2);","if {}","for x 0..1\nend"
    })
    void malformedSyntaxNeverReturnsAProgram(String source) {
        var parsed=DspdParser.parse(source);
        assertNull(parsed.program(),source);
        assertFalse(parsed.diagnostics().isEmpty(),source);
        assertTrue(parsed.diagnostics().getFirst().position().line()>=1);
    }

    @Test void errorsPointToPhysicalSourceInsteadOfStatementNumber() {
        var parsed=DspdParser.parse("# first\r\n\r\nprint(1); print(2);\r\n  let x = ;\r\n");
        var error=parsed.diagnostics().getFirst();
        assertEquals(4,error.position().line());assertEquals(11,error.position().column());
    }

    @Test void deeplyNestedInputReturnsADiagnosticInsteadOfOverflowingTheStack() {
        String source="print("+"(".repeat(400)+"1"+")".repeat(400)+");";
        assertFalse(DspdParser.parse(source).diagnostics().isEmpty());
    }
}
