package de.drremote.dsp408.util;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.SniffedFrame;

public final class DspProtocol {
    private DspProtocol() {
    }

    public static Integer command(byte[] payload) {
        return de.drremote.dsp408.protocol.DspPayloads.command(payload);
    }

    public static String commandHex(Integer command) {
        return de.drremote.dsp408.protocol.DspPayloads.commandHex(command);
    }

    public static Integer readBlockIndex(byte[] payload) {
        return de.drremote.dsp408.protocol.DspPayloads.readBlockIndex(payload);
    }

    public static Integer readBlockIndex(ProxyResponse response) {
        return response == null ? null : readBlockIndex(response.payload());
    }

    public static Integer readBlockIndex(SniffedFrame frame) {
        return frame == null ? null : readBlockIndex(frame.payload());
    }

    public static boolean isReadBlockResponse(byte[] payload) {
        return readBlockIndex(payload) != null;
    }

    public static boolean isReadBlockResponse(ProxyResponse response) {
        return readBlockIndex(response) != null;
    }

    public static boolean isReadBlockResponse(SniffedFrame frame) {
        return readBlockIndex(frame) != null;
    }
}