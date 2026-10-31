package de.drremote.dsp408.proxy;

import de.drremote.dsp408.model.Frame;
import de.drremote.dsp408.model.StreamFrameEvent;
import de.drremote.dsp408.util.HexUtil;
import de.drremote.dsp408.util.JsonUtil;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class StreamChannel implements AutoCloseable {
    private static final int LOG_HEX_MAX_BYTES = 12;

    private final String host;
    private final int port;
    private final boolean verbose;

    private Socket socket;
    private BufferedReader reader;
    private Thread readerThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private record StreamRead(StreamFrameEvent frame, IOException failure) { }
    private final LinkedBlockingQueue<StreamRead> frames = new LinkedBlockingQueue<>();
    private volatile IOException terminalFailure;
    private volatile long generation;

    StreamChannel(String host, int port, boolean verbose) {
        this.host = host;
        this.port = port;
        this.verbose = verbose;
    }

    void connect(long timeoutMs) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) timeoutMs);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        long currentGeneration = ++generation;
        BufferedReader input = reader;
        terminalFailure = null;
        frames.clear();
        running.set(true);
        readerThread = Thread.ofVirtual().name("proxy-stream-reader").start(() -> readerLoop(currentGeneration,input));
        log("Connected to proxy stream " + host + ":" + port);
    }

    void clearFrames() {
        frames.clear();
    }

    Frame waitForResponse(byte[] sentPayload, Integer expectedCommand, long timeoutMs, boolean allowTimeout)
            throws IOException {
        String sentPayloadCompact = HexUtil.toHexCompact(sentPayload);
        String sentPreview = HexUtil.toHexPreview(sentPayload, LOG_HEX_MAX_BYTES);
        boolean matchingOutboundSeen = false;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            long waitMs = Math.max(1, deadline - System.currentTimeMillis());

            StreamFrameEvent ev = waitForAnyFrame(waitMs);

            if (ev == null) {
                continue;
            }

            if (verbose) {
                System.err.println("STREAM " + ev.direction() + " payload="
                        + previewCompactHex(ev.payloadHexCompact(), LOG_HEX_MAX_BYTES));
            }

            if (!matchingOutboundSeen) {
                if ("PC_TO_DSP".equalsIgnoreCase(ev.direction())
                        && sentPayloadCompact.equalsIgnoreCase(ev.payloadHexCompact())) {
                    matchingOutboundSeen = true;
                }
                continue;
            }

            if (!"DSP_TO_PC".equalsIgnoreCase(ev.direction())) {
                continue;
            }

            Frame frame = ev.toFrame();
            if (expectedCommand == null || frame.command() == expectedCommand) {
                return frame;
            }

            if (verbose) {
                System.err.println("Ignoring DSP response cmd="
                        + commandText(frame.command())
                        + " expected=" + commandText(expectedCommand));
            }
        }

        if (allowTimeout) {
            return null;
        }

        throw new IOException("Timeout waiting for DSP response after payload " + sentPreview);
    }

    StreamFrameEvent waitForAnyFrame(long timeoutMs) throws IOException {
        try {
            if (terminalFailure != null && frames.isEmpty()) throw terminalFailure;
            StreamRead read = frames.poll(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (read == null) {
                if (terminalFailure != null) throw terminalFailure;
                return null;
            }
            if (read.failure() != null) throw read.failure();
            return read.frame();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for streamed frame", e);
        }
    }

    private void readerLoop(long currentGeneration, BufferedReader input) {
        IOException failure = new EOFException("Proxy stream disconnected");
        try {
            String line;
            while (running.get() && generation == currentGeneration && (line = input.readLine()) != null) {
                StreamFrameEvent ev = parseFrameEvent(line);
                if (ev != null && generation == currentGeneration) {
                    frames.offer(new StreamRead(ev,null));
                }
            }
        } catch (IOException e) {
            failure = new IOException("Proxy stream failed: " + e.getMessage(),e);
            if (running.get()) {
                log("Stream reader stopped: " + abbreviate(e.getMessage(), 160));
            }
        } finally {
            if (generation == currentGeneration) {
                terminalFailure = failure;
                running.set(false);
                frames.offer(new StreamRead(null,failure));
            }
        }
    }

    private StreamFrameEvent parseFrameEvent(String line) {
        String type = JsonUtil.jsonString(line, "type");
        if (!"frame".equals(type)) {
            return null;
        }

        String direction = JsonUtil.jsonString(line, "direction");
        String frameHex = JsonUtil.jsonString(line, "frameHex");
        String payloadHex = JsonUtil.jsonString(line, "payloadHex");
        Boolean validFrame = JsonUtil.jsonBoolean(line, "validFrame");

        if (direction == null || frameHex == null || payloadHex == null || !Boolean.TRUE.equals(validFrame)) {
            return null;
        }

        return new StreamFrameEvent(direction, HexUtil.compactHex(frameHex), HexUtil.compactHex(payloadHex));
    }

    private static String previewCompactHex(String compactHex, int maxBytes) {
        if (compactHex == null || compactHex.isEmpty()) {
            return "";
        }

        int maxChars = Math.max(0, maxBytes) * 2;
        int usedChars = Math.min(compactHex.length(), maxChars);
        StringBuilder sb = new StringBuilder(usedChars + usedChars / 2 + 4);

        for (int i = 0; i < usedChars; i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            int end = Math.min(i + 2, usedChars);
            sb.append(compactHex, i, end);
        }

        if (compactHex.length() > usedChars) {
            sb.append(" ...");
        }

        return sb.toString();
    }

    private static String commandText(Integer command) {
        if (command == null || command < 0) {
            return "<none>";
        }
        return "0x" + String.format("%02X", command);
    }

    private static String abbreviate(String text, int maxChars) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + " ...";
    }

    private void log(String msg) {
        if (verbose) {
            System.err.println(msg);
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);

        IOException first = null;

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                first = e;
            }
        }

        if (readerThread != null) {
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (first != null) {
            throw first;
        }
    }
}
