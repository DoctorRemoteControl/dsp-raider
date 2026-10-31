package de.drremote.dsp408.script;

import de.drremote.dsp408.proxy.ProxyConstants;

import java.nio.file.Path;

public final class DspScriptCli {

    private DspScriptCli() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
            return;
        }

        if (options.help) {
            printUsage();
            return;
        }

        try {
            new DspScriptEngine(
                    options.streamHost,
                    options.streamPort,
                    options.controlHost,
                    options.controlPort,
                    options.verbose
            ).execute(options.file);
        } catch (Exception ex) {
            System.err.println("ERROR: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("DSP408 Script CLI");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar tracks-dsp-script-all.jar --file script-example\\\\21-decode-toggle-gui.dspd");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --file <path>              Path to the .dspd file");
        System.out.println("  --stream-host <host>       Stream host (default: 127.0.0.1)");
        System.out.println("  --stream-port <port>       Stream port (default: 19081)");
        System.out.println("  --control-host <host>      Control host (default: 127.0.0.1)");
        System.out.println("  --control-port <port>      Control port (default: 19082)");
        System.out.println("  --verbose                  More logs");
        System.out.println("  --help                     Help");
        System.out.println();
        System.out.println("Note:");
        System.out.println("  For GUI decode scripts, the original software must already be connected via the proxy at 127.0.0.1:9761.");
    }

    private static final class Options {
        private Path file;
        private String streamHost = ProxyConstants.DEFAULT_STREAM_HOST;
        private int streamPort = ProxyConstants.DEFAULT_STREAM_PORT;
        private String controlHost = ProxyConstants.DEFAULT_CONTROL_HOST;
        private int controlPort = ProxyConstants.DEFAULT_CONTROL_PORT;
        private boolean verbose;
        private boolean help;

        private static Options parse(String[] args) {
            Options options = new Options();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--file" -> options.file = Path.of(requireValue(args, ++i, "--file"));
                    case "--stream-host" -> options.streamHost = requireValue(args, ++i, "--stream-host");
                    case "--stream-port" -> options.streamPort = Integer.parseInt(requireValue(args, ++i, "--stream-port"));
                    case "--control-host" -> options.controlHost = requireValue(args, ++i, "--control-host");
                    case "--control-port" -> options.controlPort = Integer.parseInt(requireValue(args, ++i, "--control-port"));
                    case "--verbose" -> options.verbose = true;
                    case "--help", "-h" -> options.help = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }

            if (!options.help && options.file == null) {
                throw new IllegalArgumentException("--file is missing");
            }

            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
