package de.drremote.dsp408.parameters;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/** Immutable block data, excluding each response's five-byte payload header. */
public final class BlockData {
    private final Map<Integer,byte[]> blocks;
    private BlockData(Map<Integer,byte[]> blocks) { this.blocks=blocks; }

    public static BlockData of(Map<Integer,byte[]> input) {
        Map<Integer,byte[]> copy=new HashMap<>();
        input.forEach((index,data)->copy.put(index,data.clone()));
        return new BlockData(Map.copyOf(copy));
    }

    public static BlockData ofMemory(byte[] memory) {
        Map<Integer,byte[]> blocks=new HashMap<>();
        for(int offset=0;offset<memory.length;offset+=50)
            blocks.put(offset/50,java.util.Arrays.copyOfRange(memory,offset,Math.min(memory.length,offset+50)));
        return new BlockData(Map.copyOf(blocks));
    }

    public OptionalInt byteAt(int block,int offset) {
        byte[] data=blocks.get(block);
        return data==null || offset<0 || offset>=data.length ? OptionalInt.empty() : OptionalInt.of(data[offset]&255);
    }
}
