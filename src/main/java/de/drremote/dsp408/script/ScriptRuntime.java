package de.drremote.dsp408.script;

import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.proxy.GuiSnifferClient;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.session.DspSessionFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ScriptRuntime implements AutoCloseable {
    final String defaultStreamHost;
    final int defaultStreamPort;
    final String defaultControlHost;
    final int defaultControlPort;
    final boolean verbose;

    final Map<String, Object> variables = new LinkedHashMap<>();

    private DspSession client;
    private final DspSessionFactory sessionFactory;
    private GuiSnifferClient guiSniffer;

    ScriptRuntime(
            String defaultStreamHost,
            int defaultStreamPort,
            String defaultControlHost,
            int defaultControlPort,
            boolean verbose,
            DspSessionFactory sessionFactory
    ) {
        this.defaultStreamHost = defaultStreamHost;
        this.defaultStreamPort = defaultStreamPort;
        this.defaultControlHost = defaultControlHost;
        this.defaultControlPort = defaultControlPort;
        this.verbose = verbose;
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    void connect(String streamHost, int streamPort, String controlHost, int controlPort) throws Exception {
        closeClient();
        DspSession candidate = Objects.requireNonNull(
                sessionFactory.create(streamHost, streamPort, controlHost, controlPort, verbose));
        try {
            candidate.connect();
            client = candidate;
        } catch (Exception | Error failure) {
            try { candidate.close(); }
            catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void connectGuiSniffer(String streamHost, int streamPort) throws Exception {
        closeGuiSniffer();
        guiSniffer = new GuiSnifferClient(streamHost, streamPort, verbose);
        guiSniffer.connect();
    }

    DspSession requireClient() {
        if (client == null) {
            throw new IllegalStateException("No proxy connection active. The script must run 'connect' first.");
        }
        return client;
    }

    GuiSnifferClient requireGuiSniffer() throws Exception {
        if (guiSniffer == null) {
            connectGuiSniffer(defaultStreamHost, defaultStreamPort);
        }
        return guiSniffer;
    }

    void beginGuiCapture() throws Exception {
        if (guiSniffer != null) {
            guiSniffer.beginCapture();
            return;
        }
        if (client != null) {
            client.beginCapture();
            return;
        }
        requireGuiSniffer().beginCapture();
    }

    GuiCaptureResult finishGuiCapture(long quietMs, long maxWaitMs) throws Exception {
        if (guiSniffer != null) {
            return guiSniffer.finishCapture(quietMs, maxWaitMs);
        }
        if (client != null) {
            return client.finishCapture(quietMs, maxWaitMs);
        }
        return requireGuiSniffer().finishCapture(quietMs, maxWaitMs);
    }

    GuiCaptureResult captureGuiAction(long actionWindowMs,
                                      long quietMs,
                                      long maxWaitMs,
                                      Integer... ignoredCommands) throws Exception {
        if (guiSniffer != null) {
            return guiSniffer.captureUntilAction(actionWindowMs, quietMs, maxWaitMs, ignoredCommands);
        }
        if (client != null) {
            return client.captureUntilAction(actionWindowMs, quietMs, maxWaitMs, ignoredCommands);
        }
        return requireGuiSniffer().captureUntilAction(actionWindowMs, quietMs, maxWaitMs, ignoredCommands);
    }

    void reconnectSession(long readyTimeoutMs, long pollMs) throws Exception {
        closeGuiSniffer();
        closeClient();
        connect(defaultStreamHost, defaultStreamPort, defaultControlHost, defaultControlPort);
        requireClient().attachSession(readyTimeoutMs, pollMs);
    }

    void pauseForUser(String message) throws Exception {
        if (message != null && !message.isBlank()) {
            System.out.println(message);
        }
        System.out.println("[SCRIPT] Press ENTER to continue...");
        System.out.flush();

        InputStream in = System.in;
        byte[] one = new byte[1];
        while (true) {
            int n = in.read(one);
            if (n < 0) {
                throw new IllegalStateException("pause() requires interactive stdin, but stdin is closed.");
            }
            if (one[0] == '\n' || one[0] == '\r') {
                return;
            }
        }
    }

    void closeClient() throws Exception {
        if (client != null) {
            DspSession current = client;
            client = null;
            current.close();
        }
    }

    void closeGuiSniffer() throws Exception {
        if (guiSniffer != null) {
            GuiSnifferClient current = guiSniffer;
            guiSniffer = null;
            current.close();
        }
    }

    void closeAll() throws Exception {
        Exception first = null;

        try {
            closeClient();
        } catch (Exception e) {
            first = e;
        }

        try {
            closeGuiSniffer();
        } catch (Exception e) {
            if (first == null) {
                first = e;
            } else {
                first.addSuppressed(e);
            }
        }

        if (first != null) {
            throw first;
        }
    }

    @Override public void close() throws Exception { closeAll(); }
}
