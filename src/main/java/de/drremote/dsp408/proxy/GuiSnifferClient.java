package de.drremote.dsp408.proxy;

import de.drremote.dsp408.model.Frame;
import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.model.StreamFrameEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class GuiSnifferClient implements AutoCloseable {
    private final StreamChannel streamChannel;
    private boolean connected;

    public GuiSnifferClient(String streamHost, int streamPort, boolean verbose) {
        this.streamChannel = new StreamChannel(streamHost, streamPort, verbose);
    }

    public void connect() throws IOException {
        connect(ProxyConstants.STREAM_CONNECT_TIMEOUT_MS);
    }

    public void connect(long timeoutMs) throws IOException {
        if (connected) {
            return;
        }

        if (timeoutMs < 1 || timeoutMs > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid connection timeout");
        streamChannel.connect(timeoutMs);
        connected = true;
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

    public SniffedFrame waitForAnyFrame(long timeoutMs) throws IOException {
        requireConnected();

        StreamFrameEvent event = streamChannel.waitForAnyFrame(timeoutMs);
        if (event == null) {
            return null;
        }

        return toSniffedFrame(event);
    }

    public SniffedFrame waitForWrite(long timeoutMs, Integer... ignoredCommands) throws IOException {
        requireConnected();

        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);

        while (System.currentTimeMillis() < deadline) {
            long waitMs = Math.max(1L, deadline - System.currentTimeMillis());
            SniffedFrame frame = waitForAnyFrame(waitMs);

            if (frame == null) {
                return null;
            }
            if (!frame.isWrite()) {
                continue;
            }
            if (matchesAny(frame.command(), ignoredCommands)) {
                continue;
            }

            return frame;
        }

        return null;
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

    private void requireConnected() {
        if (!connected) {
            throw new IllegalStateException("GuiSnifferClient is not connected.");
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
            streamChannel.close();
        } catch (IOException e) {
            first = e;
        }

        connected = false;

        if (first != null) {
            throw first;
        }
    }
}
