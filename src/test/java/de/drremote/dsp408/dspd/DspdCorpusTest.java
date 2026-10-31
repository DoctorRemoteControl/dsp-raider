package de.drremote.dsp408.dspd;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class DspdCorpusTest {
    @Test void preservedExamplesHaveOnlyTheDocumentedPreexistingErrors()throws Exception {
        Map<String,String> expected=Map.of(
                "366-auto-series-out1-iir-highpass-slope-long.dspd","E2001",
                "367-auto-series-out1-iir-lowpass-slope-long.dspd","E2001",
                "370-auto-series-out8-iir-highpass-slope-long.dspd","E2001",
                "373-auto-series-out8-iir-lowpass-slope-long.dspd","E2001",
                "412-read-current-out8-peq9-frequency.dspd","E2002");
        var actual=new TreeMap<String,String>();
        try(var files=Files.list(Path.of("script-example"))) {
            var scripts=files.filter(p->p.toString().endsWith(".dspd")).sorted().toList();
            assertFalse(scripts.isEmpty());
            for(var file:scripts) {
                var result=DspdCompiler.compile(Files.readString(file));
                if(!result.ok())actual.put(file.getFileName().toString(),result.diagnostics().getFirst().code());
            }
        }
        assertEquals(expected,actual);
    }
}
