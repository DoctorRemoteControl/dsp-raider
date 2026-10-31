package de.drremote.dsp408.parameters;

import java.util.OptionalInt;

public record ReadLocation(int block,int dataOffset,int width,int mask) {
    public ReadLocation {
        if(block<0 || block>255 || dataOffset<0 || (width!=1 && width!=2) || dataOffset+width>50
                || (mask!=0 && (width!=1 || mask<0 || mask>255 || Integer.bitCount(mask)!=1)))
            throw new IllegalArgumentException("Invalid parameter read location");
    }

    public OptionalInt raw(BlockData data) {
        int raw=0;
        for(int index=0;index<width;index++) {
            var value=data.byteAt(block,dataOffset+index);
            if(value.isEmpty()) return OptionalInt.empty();
            raw|=value.getAsInt()<<(index*8);
        }
        return OptionalInt.of(raw);
    }
}
