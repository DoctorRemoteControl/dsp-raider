package de.drremote.dsp408.session;

import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ProxyStatus;
import java.io.IOException;

/** Device-session operations used by scripts and analysis, independent of sockets. */
public interface DspSession extends AutoCloseable {
    void connect() throws IOException;
    ProxyStatus status() throws IOException;
    void resetSession() throws IOException;
    void ensureSession() throws IOException;
    void attachSession() throws IOException;
    void attachSession(long timeoutMs, long pollMs) throws IOException;
    void clearFrames();
    void beginCapture();
    GuiCaptureResult finishCapture(long quietMs, long maxWaitMs) throws IOException;
    GuiCaptureResult captureUntilAction(long actionWindowMs, long quietMs, long maxWaitMs,
                                       Integer... ignoredCommands) throws IOException;
    ProxyResponse handshakeInit() throws IOException;
    ProxyResponse deviceInfo() throws IOException;
    ProxyResponse systemInfo() throws IOException;
    void handshake() throws IOException;
    ProxyResponse login(String pin) throws IOException;
    ProxyResponse readBlock(int blockIndex) throws IOException;
    ProxyResponse sendPayload(byte[] payload, Integer expectedCommand, boolean strictResponse, String label)
            throws IOException;
    @Override void close() throws IOException;
}
