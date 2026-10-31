package de.drremote.dsp408.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.capture.PassiveCapture;
import de.drremote.dsp408.capture.CaptureAnalysis;
import de.drremote.dsp408.dump.DumpFailedException;
import de.drremote.dsp408.dump.ReadOnlySnapshotReader;
import de.drremote.dsp408.proxy.ProxyClient;
import de.drremote.dsp408.proxy.ProxyConfig;
import de.drremote.dsp408.session.DspSessionFactory;
import de.drremote.dsp408.snapshot.SnapshotAnalysis;
import de.drremote.dsp408.snapshot.SnapshotFiles;
import de.drremote.dsp408.snapshot.SnapshotFrames;
import de.drremote.dsp408.planning.ChangePlanReport;
import de.drremote.dsp408.planning.ChangePlanner;
import de.drremote.dsp408.planning.ChangeRequestFiles;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Common entry point; legacy CLIs remain available. */
public final class DspRaiderCli {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DspSessionFactory sessions;

    public DspRaiderCli() {
        this((streamHost, streamPort, controlHost, controlPort, verbose) ->
                new ProxyClient(ProxyConfig.of(streamHost, streamPort, controlHost, controlPort, null, verbose)));
    }

    public DspRaiderCli(DspSessionFactory sessions) { this.sessions = Objects.requireNonNull(sessions); }

    public static void main(String[] args) {
        int code = new DspRaiderCli().run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public int run(String[] args, PrintStream out, PrintStream error) {
        Options options = null;
        try {
            options = Options.parse(args);
            if (options.help()) { help(out); return 0; }
            Path output = options.path("out");
            if (output != null && Files.exists(output)) throw new IOException("Output already exists: " + output);
            String text;
            switch (options.command()) {
                case "plan" -> {
                    var snapshot = SnapshotFiles.read(options.path("in"));
                    for (var block : snapshot.blocks().entrySet())
                        SnapshotFrames.configurationSize(snapshot.identity(), block.getKey(), block.getValue());
                    var request = ChangeRequestFiles.read(options.path("changes"));
                    var plan = ChangePlanner.plan(snapshot.identity().version(), snapshot.blockData(), request);
                    text = options.get("format", "text").equals("json") ? ChangePlanReport.json(plan) : ChangePlanReport.text(plan);
                }
                case "decode" -> {
                    var document = MAPPER.readTree(options.path("in").toFile());
                    text = document != null && document.path("type").asText().equals("capture")
                            ? CaptureAnalysis.decode(document,options.path("lib"))
                            : SnapshotAnalysis.decode(SnapshotFiles.read(document));
                }
                case "diff" -> text = SnapshotAnalysis.diff(SnapshotFiles.read(options.path("before")),
                        SnapshotFiles.read(options.path("after")));
                case "capture" -> text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new PassiveCapture().capture(
                        options.get("stream-host", "127.0.0.1"), options.number("stream-port",19081),
                        options.number("duration-ms",5000)));
                default -> {
                    try (var session = sessions.create(options.get("stream-host", "127.0.0.1"), options.number("stream-port",19081),
                            options.get("control-host", "127.0.0.1"), options.number("control-port",19082), options.verbose())) {
                        var reader = new ReadOnlySnapshotReader();
                        text = switch (options.command()) {
                            case "connect" -> {
                                ReadOnlySnapshotReader.ensureReady(session);
                                var status = session.status();
                                if (!status.sessionActive() || !status.injectReady())
                                    throw new IOException("Proxy has no ready DSP session");
                                yield MAPPER.writeValueAsString(Map.of("ok", status.sessionActive() && status.injectReady(),
                                        "sessionActive", status.sessionActive(), "injectReady", status.injectReady()));
                            }
                            case "identify" -> reader.identify(session).toJson();
                            case "dump" -> reader.dump(session, options.values().containsKey("end-block")
                                    ? options.number("end-block",0) : null).toJson();
                            default -> throw new IllegalArgumentException("Unknown command");
                        };
                    }
                }
            }
            emit(text, output, out);
            return 0;
        } catch (IllegalArgumentException invalid) {
            error.println("Usage error: " + invalid.getMessage());
            error.println("Use --help for commands and options.");
            return 2;
        } catch (Exception failure) {
            if (failure instanceof DumpFailedException partial && options != null) {
                try { emit(partial.getResult().toJson(), options.path("out"), out); }
                catch (Exception writeError) { failure.addSuppressed(writeError); }
            }
            error.println("Error: " + (failure instanceof DumpFailedException && failure.getCause() != null
                    ? failure.getCause().getMessage() : failure.getMessage()));
            return 1;
        }
    }

    private static void emit(String text, Path output, PrintStream out) throws IOException {
        if (output == null) { out.println(text); return; }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, text + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        out.println("Saved: " + output.toAbsolutePath());
    }

    private static void help(PrintStream out) {
        out.println("""
                DSP Raider
                Usage: java -jar dsp-raider.jar <command> [options]

                  connect                     Check/reuse the proxy device session
                  identify                    Read device identity and system information
                  dump [--out snapshot.json]  Read the recognized configuration block range
                  decode --in snapshot-or-capture.json [--out report.md] [--lib field-library.json]
                  diff --before a.json --after b.json [--out diff.txt]
                  plan --in snapshot.json --changes changes.json [--format text|json] [--out preview.txt]
                  capture --out frames.json [--duration-ms 5000]

                Proxy options for connect/identify/dump:
                  --stream-host HOST --stream-port PORT     Default: 127.0.0.1:19081
                  --control-host HOST --control-port PORT   Default: 127.0.0.1:19082
                  --verbose
                Capture uses the stream options only; duration-ms is 1..60000.
                Dump accepts --end-block 0x1F to explicitly select a range for an unknown device.
                Output files are created once. Existing files are never replaced.
                Plan works offline and previews Gain/Mute/Delay changes without sending them.
                All device commands above read data; they do not change presets or settings.
                """);
    }

    private record Options(String command, Map<String,String> values, boolean verbose, boolean help) {
        private static final Set<String> COMMANDS = Set.of("connect","identify","dump","decode","diff","capture","plan");
        private static final Set<String> NETWORK = Set.of("stream-host","stream-port","control-host","control-port","out");

        static Options parse(String[] args) {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h"))
                return new Options("help",Map.of(),false,true);
            String command = args[0];
            if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unknown command: " + command);
            var allowed = new HashSet<String>();
            switch(command) {
                case "connect", "identify", "dump" -> allowed.addAll(NETWORK);
                case "decode" -> allowed.addAll(Set.of("in","out","lib"));
                case "diff" -> allowed.addAll(Set.of("before","after","out"));
                case "plan" -> allowed.addAll(Set.of("in","changes","format","out"));
                case "capture" -> allowed.addAll(Set.of("stream-host","stream-port","duration-ms","out"));
            }
            if (command.equals("dump")) allowed.add("end-block");
            boolean verbose = false, help = false;
            var values = new LinkedHashMap<String,String>();
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (option.equals("--help") || option.equals("-h")) { help = true; continue; }
                if (option.equals("--verbose") && Set.of("connect","identify","dump").contains(command)) { verbose = true; continue; }
                if (!option.startsWith("--") || !allowed.contains(option.substring(2)))
                    throw new IllegalArgumentException("Unknown option: " + option);
                if (++index >= args.length || args[index].startsWith("--") || args[index].isBlank())
                    throw new IllegalArgumentException("Missing value for " + option);
                if (values.putIfAbsent(option.substring(2), args[index]) != null)
                    throw new IllegalArgumentException("Duplicate option: " + option);
            }
            var result = new Options(command, Map.copyOf(values), verbose, help);
            if (help) return result;
            if (command.equals("decode")) result.require("in");
            if (command.equals("diff")) { result.require("before"); result.require("after"); }
            if (command.equals("plan")) {
                result.require("in"); result.require("changes");
                if (!Set.of("text", "json").contains(result.get("format", "text")))
                    throw new IllegalArgumentException("--format must be text or json");
            }
            if (command.equals("capture")) result.require("out");
            for (String port : List.of("stream-port","control-port"))
                if (values.containsKey(port)) result.range(port,1,65535);
            if (values.containsKey("end-block")) result.range("end-block",0,31);
            if (values.containsKey("duration-ms")) result.range("duration-ms",1,60000);
            return result;
        }

        private void require(String name) {
            if (!values.containsKey(name)) throw new IllegalArgumentException("Missing --" + name);
        }
        String get(String name, String fallback) { return values.getOrDefault(name, fallback); }
        Path path(String name) { return values.containsKey(name) ? Path.of(values.get(name)) : null; }
        int number(String name, int fallback) {
            String value = values.get(name);
            if (value == null) return fallback;
            try { return value.startsWith("0x") || value.startsWith("0X")
                    ? Integer.parseInt(value.substring(2),16) : Integer.parseInt(value); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid --" + name + ": " + value); }
        }
        private void range(String name, int min, int max) {
            int number = number(name,min);
            if (number < min || number > max) throw new IllegalArgumentException("--" + name + " must be in " + min + ".." + max);
        }
    }
}
