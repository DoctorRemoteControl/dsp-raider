package de.drremote.dsp408.raw;

import de.drremote.dsp408.protocol.DspFrameDecoder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RawSocketClient implements AutoCloseable {
    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean verbose;

    private final DspFrameDecoder decoder = new DspFrameDecoder();
    private final Deque<byte[]> receivedFrames = new ArrayDeque<>();

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    RawSocketClient(String host, int port, int connectTimeoutMs, int readTimeoutMs, boolean verbose) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.verbose = verbose;
    }

    void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        System.out.println("Verbunden mit " + host + ":" + port);
    }

    List<byte[]> sendPayloadAndRead(String label, byte[] payload, int responses) throws IOException {
        byte[] frame = RawFrameCodec.buildFrame(payload);

        System.out.println();
        System.out.println("TX " + label + " payload: " + RawFrameCodec.toHex(payload));
        System.out.println("TX " + label + " frame:   " + RawFrameCodec.toHex(frame));

        out.write(frame);
        out.flush();

        return readAndPrintResponses(label, responses);
    }

    List<byte[]> sendFrameAndRead(String label, byte[] frame, int responses) throws IOException {
        System.out.println();
        System.out.println("TX " + label + " frame:   " + RawFrameCodec.toHex(frame));

        out.write(frame);
        out.flush();

        return readAndPrintResponses(label, responses);
    }

    private List<byte[]> readAndPrintResponses(String label, int maxFrames) throws IOException {
        if (maxFrames <= 0) {
            return List.of();
        }

        List<byte[]> frames = readFrames(maxFrames);

        if (frames.isEmpty()) {
            System.out.println("RX " + label + ": <no response within timeout>");
            return frames;
        }

        for (int i = 0; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            byte[] payload = RawFrameCodec.extractPayload(frame);
            boolean ok = RawFrameCodec.isValidFrame(frame);

            System.out.println("RX " + label + " frame[" + i + "]:    " + RawFrameCodec.toHex(frame));
            System.out.println("RX " + label + " payload[" + i + "]:  " + RawFrameCodec.toHex(payload));
            System.out.println("RX " + label + " ascii[" + i + "]:    " + RawFrameCodec.toAsciiPreview(payload));
            System.out.println("RX " + label + " checksum[" + i + "]: " + (ok ? "OK" : "INVALID"));
        }

        return frames;
    }

    private List<byte[]> readFrames(int maxFrames) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        byte[] buffer = new byte[2048];
        while (frames.size() < maxFrames) {
            while (!receivedFrames.isEmpty() && frames.size() < maxFrames) {
                frames.add(receivedFrames.removeFirst());
            }
            if (frames.size() >= maxFrames) break;
            try {
                int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                byte[] chunk = Arrays.copyOf(buffer, n);
                if (verbose) System.out.println("RX raw chunk: " + RawFrameCodec.toHex(chunk));
                for (var frame : decoder.accept(chunk)) receivedFrames.addLast(frame.raw());
            } catch (SocketTimeoutException timeout) {
                break;
            }
        }
        return frames;
    }

    @Override
    public void close() {
        closeQuietly(in);
        closeQuietly(out);
        closeQuietly(socket);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
