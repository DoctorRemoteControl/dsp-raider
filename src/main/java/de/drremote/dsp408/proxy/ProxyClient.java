package de.drremote.dsp408.proxy;

import de.drremote.dsp408.model.Frame;
import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ProxyStatus;
import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.model.StreamFrameEvent;
import de.drremote.dsp408.util.WaitUtil;
import de.drremote.dsp408.session.DspSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ProxyClient implements DspSession {
    private final ProxyConfig config;
    private final StreamChannel streamChannel;
    private final ControlChannel controlChannel;

    private boolean connected;

    public ProxyClient(ProxyConfig config) {
        this.config = config;
        this.streamChannel = new StreamChannel(config.streamHost(), config.streamPort(), config.verbose());
        this.controlChannel = new ControlChannel(config.controlHost(), config.controlPort(), config.verbose());
    }

    public void connect() throws IOException {
        if (connected) {
            return;
        }

        try {
            streamChannel.connect(ProxyConstants.STREAM_CONNECT_TIMEOUT_MS);
            controlChannel.connect();
            connected = true;
        } catch (IOException | RuntimeException failure) {
            try { close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public ProxyStatus status() throws IOException {
        requireConnected();
        return controlChannel.status();
    }

    public void resetSession() throws IOException {
        requireConnected();
        controlChannel.resetSession();
    }

    public void ensureSession() throws IOException {
        requireConnected();
        controlChannel.ensureSession();
        waitUntilInjectReady();
    }

    public void attachSession() throws IOException {
        requireConnected();
        waitUntilInjectReadyWithoutReset(
                ProxyConstants.READY_WAIT_TIMEOUT_MS,
                ProxyConstants.RETRY_DELAY_MS
        );
    }

    public void attachSession(long timeoutMs, long pollMs) throws IOException {
        requireConnected();
        waitUntilInjectReadyWithoutReset(timeoutMs, pollMs);
    }

    public void clearFrames() {
        streamChannel.clearFrames();
    }

    public void beginCapture() {
        requireConnected();
        streamChannel.clearFrames();
    }

    public GuiCaptureResult finishCapture(long quietMs, long maxWaitMs) throws IOException {
        requireConnected();

        long quiet = Math.max(50L, quietMs);
        long hard = Math.max(quiet, maxWaitMs);

        long start = System.currentTimeMillis();
        long quietDeadline = start + quiet;
        long hardDeadline = start + hard;

        List<SniffedFrame> out = new ArrayList<>();

        while (true) {
            long now = System.currentTimeMillis();
            long nextDeadline = Math.min(quietDeadline, hardDeadline);
            long waitMs = nextDeadline - now;

            if (waitMs <= 0) {
                break;
            }

            StreamFrameEvent event = streamChannel.waitForAnyFrame(waitMs);
            if (event == null) {
                if (System.currentTimeMillis() >= quietDeadline) {
                    break;
                }
                continue;
            }

            out.add(toSniffedFrame(event));
            quietDeadline = System.currentTimeMillis() + quiet;
        }

        return new GuiCaptureResult(out);
    }

    public GuiCaptureResult captureUntilAction(long actionWindowMs,
                                               long quietMs,
                                               long maxWaitMs,
                                               Integer... ignoredCommands) throws IOException {
        requireConnected();

        long actionWindow = Math.max(100L, actionWindowMs);
        long quiet = Math.max(50L, quietMs);
        long maxAfterAction = Math.max(quiet, maxWaitMs);

        long start = System.currentTimeMillis();
        long actionDeadline = start + actionWindow;
        long quietDeadline = Long.MAX_VALUE;
        long hardDeadline = Long.MAX_VALUE;
        boolean actionSeen = false;

        List<SniffedFrame> out = new ArrayList<>();

        while (true) {
            long now = System.currentTimeMillis();
            long nextDeadline = actionSeen
                    ? Math.min(quietDeadline, hardDeadline)
                    : actionDeadline;
            long waitMs = nextDeadline - now;

            if (waitMs <= 0) {
                break;
            }

            StreamFrameEvent event = streamChannel.waitForAnyFrame(waitMs);
            if (event == null) {
                if (System.currentTimeMillis() >= nextDeadline) {
                    break;
                }
                continue;
            }

            SniffedFrame frame = toSniffedFrame(event);
            out.add(frame);

            boolean ignored = matchesAny(frame.command(), ignoredCommands);
            boolean interestingWrite = frame.isWrite() && !ignored;

            if (!actionSeen) {
                if (interestingWrite) {
                    actionSeen = true;
                    long current = System.currentTimeMillis();
                    quietDeadline = current + quiet;
                    hardDeadline = current + maxAfterAction;
                }
                continue;
            }

            if (interestingWrite) {
                long current = System.currentTimeMillis();
                quietDeadline = current + quiet;
                hardDeadline = current + maxAfterAction;
            }
        }

        return new GuiCaptureResult(out);
    }

    public ProxyResponse handshakeInit() throws IOException {
        return transact(new byte[]{0x00, 0x01, 0x01, 0x10}, 0x10, true, "handshake_init");
    }

    public ProxyResponse deviceInfo() throws IOException {
        return transact(new byte[]{0x00, 0x01, 0x01, 0x13}, 0x13, true, "device_info");
    }

    public ProxyResponse systemInfo() throws IOException {
        return transact(new byte[]{0x00, 0x01, 0x01, 0x2C}, 0x2C, true, "system_info");
    }

    public void handshake() throws IOException {
        handshakeInit();
        deviceInfo();
        systemInfo();
    }

    public ProxyResponse login(String pin) throws IOException {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits.");
        }

        byte[] pinBytes = pin.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[5 + pinBytes.length];
        payload[0] = 0x00;
        payload[1] = 0x01;
        payload[2] = 0x06;
        payload[3] = 0x2D;
        payload[4] = 0x00;
        System.arraycopy(pinBytes, 0, payload, 5, pinBytes.length);

        return transact(payload, 0x2D, true, "login");
    }

    public ProxyResponse readBlock(int blockIndex) throws IOException {
        if (blockIndex < ProxyConstants.BLOCK_START || blockIndex > ProxyConstants.BLOCK_END) {
            throw new IllegalArgumentException("Block ausserhalb des Bereichs 0x00..0x1F: "
                    + String.format("0x%02X", blockIndex));
        }

        byte[] payload = new byte[]{0x00, 0x01, 0x02, 0x27, (byte) (blockIndex & 0xFF)};
        return transact(payload, 0x24, true, "read_block_" + String.format("0x%02X", blockIndex));
    }

    public ProxyResponse sendPayload(byte[] payload, Integer expectedCommand, boolean strictResponse, String label)
            throws IOException {
        return transact(payload, expectedCommand, strictResponse, label);
    }

    public void prepareSession(String pin) throws IOException {
        connect();
        resetSession();
        ensureSession();
        handshake();

        if (pin != null) {
            login(pin);
        }
    }

    private static SniffedFrame toSniffedFrame(StreamFrameEvent event) throws IOException {
        Frame frame = event.toFrame();
        Integer command = frame.command() >= 0 ? frame.command() : null;

        return new SniffedFrame(
                event.direction(),
                frame.raw(),
                frame.payload(),
                command,
                frame.payload().length,
                frame.checksumOk()
        );
    }

    private ProxyResponse transact(byte[] payload, Integer expectedCommand, boolean strictResponse, String label)
            throws IOException {
        requireConnected();

        streamChannel.clearFrames();
        controlChannel.beginTransaction(ProxyConstants.CONTROL_TX_LEASE_MS);

        try {
            controlChannel.sendPayload(payload);

            Frame frame = streamChannel.waitForResponse(
                    payload,
                    expectedCommand,
                    ProxyConstants.TRANSACTION_TIMEOUT_MS,
                    !strictResponse
            );

            if (frame == null) {
                if (strictResponse) {
                    throw new IOException("No DSP response received for " + label + ".");
                }
                return null;
            }

            return new ProxyResponse(frame.raw(), frame.payload(), frame.checksumOk());
        } finally {
            try {
                controlChannel.endTransaction();
            } catch (IOException e) {
                if (config.verbose()) {
                    System.err.println("Warning: failed to end control transaction: " + e.getMessage());
                }
            }
        }
    }

    private void waitUntilInjectReady() throws IOException {
        long deadline = System.currentTimeMillis() + ProxyConstants.READY_WAIT_TIMEOUT_MS;
        ProxyStatus last = null;

        while (System.currentTimeMillis() < deadline) {
            last = controlChannel.status();
            if (last.sessionActive() && last.injectReady()) {
                return;
            }
            WaitUtil.sleepMs(ProxyConstants.RETRY_DELAY_MS);
        }

        if (last == null) {
            last = controlChannel.status();
        }

        if (!last.sessionActive()) {
            throw new IOException("Proxy control is reachable, but no DSP session is active.");
        }

        throw new IOException("DSP session active, but injectReady did not become true in time.");
    }

    private void waitUntilInjectReadyWithoutReset(long timeoutMs, long pollMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        long sleepMs = Math.max(50L, pollMs);
        ProxyStatus last = null;

        while (System.currentTimeMillis() < deadline) {
            last = controlChannel.status();
            if (last.sessionActive() && last.injectReady()) {
                return;
            }
            WaitUtil.sleepMs(sleepMs);
        }

        if (last == null) {
            last = controlChannel.status();
        }

        if (!last.sessionActive()) {
            throw new IOException("No existing DSP session active. The original GUI must already be connected through the proxy.");
        }

        throw new IOException("Existing DSP session detected, but injectReady stayed false. The GUI connection is likely not fully ready yet.");
    }

    private void requireConnected() {
        if (!connected) {
            throw new IllegalStateException("ProxyClient is not connected.");
        }
    }

    private static boolean matchesAny(Integer command, Integer... commands) {
        if (commands == null || commands.length == 0) {
            return false;
        }
        for (Integer candidate : commands) {
            if (candidate == null ? command == null : candidate.equals(command)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;

        try {
            controlChannel.close();
        } catch (IOException e) {
            first = e;
        }

        try {
            streamChannel.close();
        } catch (IOException e) {
            if (first == null) {
                first = e;
            }
        }

        connected = false;

        if (first != null) {
            throw first;
        }
    }
}
