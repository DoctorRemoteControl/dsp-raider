package de.drremote.dsp408.snapshot;

import de.drremote.dsp408.model.DeviceIdentity;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.parameters.BlockData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.NavigableMap;
import java.util.TreeMap;

public record Snapshot(DeviceIdentity identity, NavigableMap<Integer, ProxyResponse> blocks) {
    public Snapshot {
        blocks = Collections.unmodifiableNavigableMap(new TreeMap<>(blocks));
    }

    /** Keeps individual block boundaries and absent bytes for evidence-aware readers. */
    public BlockData blockData() {
        var data = new HashMap<Integer, byte[]>();
        blocks.forEach((index, response) -> {
            byte[] payload = response.payload();
            data.put(index, Arrays.copyOfRange(payload, 5, payload.length));
        });
        return BlockData.of(data);
    }

    public byte[] data() throws IOException {
        if (identity.lastBlock() < 0) throw new IOException("Unknown memory profile");
        if (blocks.size() != identity.lastBlock() + 1) throw new IOException("Incomplete configuration block set");
        var memory = new ByteArrayOutputStream();
        for (int index = 0; index <= identity.lastBlock(); index++) {
            var block = blocks.get(index);
            if (block == null) throw new IOException("Missing configuration block 0x%02X".formatted(index));
            SnapshotFrames.configurationSize(identity,index,block);
            byte[] payload = block.payload();
            memory.write(payload, 5, payload.length - 5);
        }
        return memory.toByteArray();
    }
}
