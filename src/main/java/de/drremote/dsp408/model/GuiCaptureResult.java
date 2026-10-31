package de.drremote.dsp408.model;

import de.drremote.dsp408.protocol.DspPayloads;

import java.util.ArrayList;
import java.util.List;

public final class GuiCaptureResult {
    private final List<SniffedFrame> frames;

    public GuiCaptureResult(List<SniffedFrame> frames) {
        this.frames = frames == null ? List.of() : List.copyOf(frames);
    }

    public List<SniffedFrame> frames() {
        return frames;
    }

    public int totalFrames() {
        return frames.size();
    }

    public int writeCount() {
        int count = 0;
        for (SniffedFrame frame : frames) {
            if (frame != null && frame.isWrite()) {
                count++;
            }
        }
        return count;
    }

    public int responseCount() {
        int count = 0;
        for (SniffedFrame frame : frames) {
            if (frame != null && frame.isResponse()) {
                count++;
            }
        }
        return count;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public SniffedFrame firstFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    public SniffedFrame lastFrame() {
        return frames.isEmpty() ? null : frames.get(frames.size() - 1);
    }

    public SniffedFrame firstWrite() {
        for (SniffedFrame frame : frames) {
            if (frame != null && frame.isWrite()) {
                return frame;
            }
        }
        return null;
    }

    public SniffedFrame lastWrite() {
        for (int i = frames.size() - 1; i >= 0; i--) {
            SniffedFrame frame = frames.get(i);
            if (frame != null && frame.isWrite()) {
                return frame;
            }
        }
        return null;
    }

    public SniffedFrame firstResponse() {
        for (SniffedFrame frame : frames) {
            if (frame != null && frame.isResponse()) {
                return frame;
            }
        }
        return null;
    }

    public SniffedFrame lastResponse() {
        for (int i = frames.size() - 1; i >= 0; i--) {
            SniffedFrame frame = frames.get(i);
            if (frame != null && frame.isResponse()) {
                return frame;
            }
        }
        return null;
    }

    public List<SniffedFrame> writes() {
        List<SniffedFrame> out = new ArrayList<>();
        for (SniffedFrame frame : frames) {
            if (frame != null && frame.isWrite()) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    public List<SniffedFrame> responses() {
        List<SniffedFrame> out = new ArrayList<>();
        for (SniffedFrame frame : frames) {
            if (frame != null && frame.isResponse()) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    public List<SniffedFrame> readBlockResponses() {
        List<SniffedFrame> out = new ArrayList<>();
        for (SniffedFrame frame : frames) {
            if (frame == null || !frame.isResponse()) {
                continue;
            }
            if (DspPayloads.readBlockIndex(frame.payload()) != null) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    public SniffedFrame readBlockResponse(int blockIndex) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            SniffedFrame frame = frames.get(i);
            Integer index = frame == null ? null : DspPayloads.readBlockIndex(frame.payload());
            if (index != null && index == (blockIndex & 0xFF)) {
                return frame;
            }
        }
        return null;
    }

    public SniffedFrame lastWriteExcluding(Integer... commands) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            SniffedFrame frame = frames.get(i);
            if (frame == null || !frame.isWrite()) {
                continue;
            }
            if (!matchesAny(frame.command(), commands)) {
                return frame;
            }
        }
        return null;
    }

    public List<SniffedFrame> recentWrites(int limit) {
        List<SniffedFrame> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }

        for (int i = frames.size() - 1; i >= 0 && out.size() < limit; i--) {
            SniffedFrame frame = frames.get(i);
            if (frame != null && frame.isWrite()) {
                out.add(frame);
            }
        }

        return List.copyOf(out);
    }

    public List<SniffedFrame> recentWritesExcluding(int limit, Integer... commands) {
        List<SniffedFrame> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }

        for (int i = frames.size() - 1; i >= 0 && out.size() < limit; i--) {
            SniffedFrame frame = frames.get(i);
            if (frame == null || !frame.isWrite()) {
                continue;
            }
            if (!matchesAny(frame.command(), commands)) {
                out.add(frame);
            }
        }

        return List.copyOf(out);
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

    public SniffedFrame frame(int index) {
        if (index < 0 || index >= frames.size()) {
            throw new IllegalArgumentException("Capture-Index ausserhalb des Bereichs: " + index);
        }
        return frames.get(index);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("capture{");
        sb.append("frames=").append(totalFrames());
        sb.append(", writes=").append(writeCount());
        sb.append(", responses=").append(responseCount());

        SniffedFrame firstWrite = firstWrite();
        if (firstWrite != null) {
            sb.append(", firstWrite=").append(firstWrite.commandHex());
        }

        sb.append("}");
        return sb.toString();
    }
}