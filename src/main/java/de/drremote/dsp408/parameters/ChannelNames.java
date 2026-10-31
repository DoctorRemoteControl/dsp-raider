package de.drremote.dsp408.parameters;

/** Channel addressing shared by requests and checked scripts. */
public final class ChannelNames {
    private ChannelNames() { }
    public static int index(String name) {
        if (name != null) for (int index = 0; index < 12; index++) {
            String candidate = index < 4 ? "In" + (char)('A'+index) : "Out"+(index-3);
            if (candidate.equalsIgnoreCase(name.strip())) return index;
        }
        throw new IllegalArgumentException("Unknown channel: " + name);
    }
}
