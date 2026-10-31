package de.drremote.dsp408.session;

import java.io.IOException;

/** Creates an unconnected session; its caller owns connection and cleanup. */
@FunctionalInterface
public interface DspSessionFactory {
    DspSession create(String streamHost, int streamPort, String controlHost, int controlPort, boolean verbose)
            throws IOException;
}
