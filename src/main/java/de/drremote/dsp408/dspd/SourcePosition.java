package de.drremote.dsp408.dspd;

/** Zero-based UTF16 offset, one-based physical line and column. */
public record SourcePosition(int offset, int line, int column) {
    public static SourcePosition at(String source, int offset) {
        int line=1,column=1;
        for(int i=0;i<offset;i++) {
            char c=source.charAt(i);
            if(c=='\r') {line++;column=1;if(i+1<offset && source.charAt(i+1)=='\n')i++;}
            else if(c=='\n'){line++;column=1;} else column++;
        }
        return new SourcePosition(offset,line,column);
    }
}
