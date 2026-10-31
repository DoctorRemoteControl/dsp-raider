package de.drremote.dsp408.script;

import de.drremote.dsp408.dspd.DspdCheckReport;
import de.drremote.dsp408.dspd.DspdCompiler;
import de.drremote.dsp408.proxy.ProxyClient;
import de.drremote.dsp408.proxy.ProxyConfig;
import de.drremote.dsp408.session.DspSessionFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Compiles a complete source before constructing a runtime or executing functions. */
public final class CheckedDspScriptEngine {
    private final String streamHost, controlHost;
    private final int streamPort, controlPort;
    private final boolean verbose;
    private final DspSessionFactory sessions;

    public CheckedDspScriptEngine() {
        this((a,b,c,d,e) -> new ProxyClient(ProxyConfig.of(a,b,c,d,null,e)));
    }

    public CheckedDspScriptEngine(DspSessionFactory sessions) {
        this("127.0.0.1",19081,"127.0.0.1",19082,false,sessions);
    }

    public CheckedDspScriptEngine(String streamHost, int streamPort, String controlHost,
                                  int controlPort, boolean verbose, DspSessionFactory sessions) {
        this.streamHost = Objects.requireNonNull(streamHost);
        this.streamPort = streamPort;
        this.controlHost = Objects.requireNonNull(controlHost);
        this.controlPort = controlPort;
        this.verbose = verbose;
        this.sessions = Objects.requireNonNull(sessions);
    }

    public Map<String,Object> execute(Path path, PrintStream output) throws Exception {
        return executeText(path.toString(), Files.readString(path, StandardCharsets.UTF_8), output);
    }

    public Map<String,Object> executeText(String sourceName, String source, PrintStream output) throws Exception {
        Objects.requireNonNull(output);
        var compilation = DspdCompiler.compile(Objects.requireNonNull(source));
        if (!compilation.ok()) throw new DspdCompilationException(DspdCheckReport.text(sourceName, compilation));
        try (var runtime = new ScriptRuntime(streamHost,streamPort,controlHost,controlPort,verbose,sessions)) {
            new DspdExecutor(sourceName, runtime, output).execute(compilation.program());
            return Collections.unmodifiableMap(new LinkedHashMap<>(runtime.variables));
        }
    }
}
