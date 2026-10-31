package de.drremote.dsp408.model;

import de.drremote.dsp408.protocol.Hex;

import java.io.IOException;

public final class StreamFrameEvent {
    private final String direction;
    private final String frameHexCompact;
    private final String payloadHexCompact;

    public StreamFrameEvent(String direction, String frameHexCompact, String payloadHexCompact) {
        this.direction = direction;
        this.frameHexCompact = frameHexCompact;
        this.payloadHexCompact = payloadHexCompact;
    }

    public String direction() {
        return direction;
    }

    public String frameHexCompact() {
        return frameHexCompact;
    }

    public String payloadHexCompact() {
        return payloadHexCompact;
    }

    public Frame toFrame() throws IOException {
        byte[] raw = Hex.parse(frameHexCompact);
        byte[] payload = Hex.parse(payloadHexCompact);
        return new Frame(raw, payload, true);
    }
}
