package de.drremote.dsp408.dump;

import de.drremote.dsp408.model.DeviceIdentity;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.snapshot.SnapshotFrames;

/** Reads an owned-by-caller session without resetting it or issuing setting writes. */
public final class ReadOnlySnapshotReader {
    public DumpResult identify(DspSession session) throws DumpFailedException {
        return read(session, false, null);
    }

    public DumpResult dump(DspSession session, Integer endBlock) throws DumpFailedException {
        return read(session, true, endBlock);
    }

    public static void ensureReady(DspSession session) throws Exception {
        session.connect();
        var status = session.status();
        if (!status.sessionActive() || !status.injectReady()) session.ensureSession();
    }

    private DumpResult read(DspSession session, boolean blocks, Integer override) throws DumpFailedException {
        DumpResult result = DumpResult.start();
        try {
            if (override != null && (override < 0 || override > 0x1f))
                throw new IllegalArgumentException("end block must be in 0x00..0x1F");
            ensureReady(session);
            result.handshakeInit = DumpResult.FrameJson.fromResponse(SnapshotFrames.checked(session.handshakeInit(), 0x10, null));
            var device = SnapshotFrames.checked(session.deviceInfo(), 0x13, null);
            result.deviceInfo = DumpResult.FrameJson.fromResponse(device);
            DeviceIdentity identity = DeviceIdentity.fromPayload(device.payload());
            result.deviceVersion = identity.version();
            result.deviceProfile = identity.profile();
            result.systemInfo = DumpResult.FrameJson.fromResponse(SnapshotFrames.checked(session.systemInfo(), 0x2c, null));
            if (blocks) {
                int end = override == null ? identity.lastBlock() : override;
                if (end < 0) throw new IllegalArgumentException("Unknown device profile; specify --end-block explicitly");
                for (int index = 0; index <= end; index++) {
                    var response = SnapshotFrames.checked(session.readBlock(index), 0x24, index);
                    SnapshotFrames.configurationSize(identity,index,response);
                    result.blocks.add(DumpResult.BlockJson.fromResponse(index, response));
                }
            }
            result.ok = true;
            return result;
        } catch (Exception error) {
            result.ok = false;
            result.error = error.getMessage();
            throw new DumpFailedException(result, error);
        }
    }
}
