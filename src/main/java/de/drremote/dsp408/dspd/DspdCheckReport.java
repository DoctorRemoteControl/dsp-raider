package de.drremote.dsp408.dspd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public final class DspdCheckReport {
    private DspdCheckReport() { }
    public static String text(String source,DspdCompiler.Compilation result) {
        if(result.ok())return "OK: "+source+" ("+count(result.program().statements())+" statements checked)\nStatic checking, no execution.\n";
        var report=new StringBuilder();
        for(var diagnostic:result.diagnostics())report.append("%s:%d:%d: error %s: %s\n".formatted(source,
                diagnostic.position().line(),diagnostic.position().column(),diagnostic.code(),diagnostic.message()));
        return report.toString();
    }
    public static String json(String source,DspdCompiler.Compilation result)throws JsonProcessingException {
        var mapper=new ObjectMapper();var root=mapper.createObjectNode().put("schema_version",1).put("type","dspd_check")
                .put("source",source).put("ok",result.ok()).put("executed",false)
                .put("statement_count",result.program()==null?0:count(result.program().statements()));
        var diagnostics=root.putArray("diagnostics");
        for(var diagnostic:result.diagnostics())diagnostics.addObject().put("code",diagnostic.code()).put("severity","error")
                .put("message",diagnostic.message()).put("offset",diagnostic.position().offset())
                .put("line",diagnostic.position().line()).put("column",diagnostic.position().column());
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
    private static int count(List<DspdAst.Statement> statements) {
        int count=statements.size();
        for(var statement:statements) {
            if(statement instanceof DspdAst.Conditional conditional) {
                for(var branch:conditional.branches())count+=count(branch.body());
                count+=count(conditional.otherwise());
            } else if(statement instanceof DspdAst.Loop loop)count+=count(loop.body());
        }
        return count;
    }
}
