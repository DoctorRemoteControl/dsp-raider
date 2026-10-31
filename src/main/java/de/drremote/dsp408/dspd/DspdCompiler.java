package de.drremote.dsp408.dspd;

import java.util.List;

/** Compile/check the whole script without constructing a device runtime. */
public final class DspdCompiler {
    public record Compilation(DspdAst.Program program,List<Diagnostic> diagnostics) {
        public Compilation {diagnostics=List.copyOf(diagnostics);}
        public boolean ok(){return diagnostics.isEmpty();}
    }
    private DspdCompiler() { }
    public static Compilation compile(String source) {
        var parsed=DspdParser.parse(source);
        if(parsed.program()==null)return new Compilation(null,parsed.diagnostics());
        var diagnostics=DspdAnalyzer.analyze(source,parsed.program());
        return new Compilation(diagnostics.isEmpty()?parsed.program():null,diagnostics);
    }
}
