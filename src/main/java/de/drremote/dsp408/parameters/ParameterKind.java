package de.drremote.dsp408.parameters;

import java.util.Locale;

/** Scalar metadata and wire conversion shared by readers, reports and capture decoders. */
public enum ParameterKind {
    GAIN("Gain","dB",-60,12,400,0x34,2),
    MUTE("Mute","",0,1,1,0x35,1),
    DELAY("Delay","ms",0,680,65280,0x38,2);

    private final String label, unit;
    private final double minimum, maximum;
    private final int rawMaximum, command, width;

    ParameterKind(String label,String unit,double minimum,double maximum,int rawMaximum,int command,int width) {
        this.label=label;this.unit=unit;this.minimum=minimum;this.maximum=maximum;
        this.rawMaximum=rawMaximum;this.command=command;this.width=width;
    }
    public String label() { return label; }
    public String unit() { return unit; }
    public double minimum() { return minimum; }
    public double maximum() { return maximum; }
    public int rawMaximum() { return rawMaximum; }
    public int command() { return command; }
    public int width() { return width; }

    public double rawToValue(int raw) {
        if(raw<0 || raw>rawMaximum) throw new IllegalArgumentException(label+" raw outside 0.."+rawMaximum);
        return switch(this) {
            case GAIN -> raw<=80 ? (raw-120)/2.0 : (raw-280)/10.0;
            case DELAY -> raw/96.0;
            case MUTE -> raw;
        };
    }

    public int valueToRaw(double value) {
        if(!Double.isFinite(value) || value<minimum || value>maximum || (this==MUTE && value!=0 && value!=1))
            throw new IllegalArgumentException(label+" value outside supported range");
        return switch(this) {
            case GAIN -> (int)Math.round(value<=-20 ? (value+60)*2 : (value+28)*10);
            case DELAY -> (int)Math.round(value*96);
            case MUTE -> (int)value;
        };
    }

    /** Builds bytes only. No connection or transmission is performed. */
    public byte[] writePayload(int channel,double value) {
        if(channel<0 || channel>11) throw new IllegalArgumentException("Channel must be in 0..11");
        int raw=valueToRaw(value);
        byte[] payload=new byte[5+width];
        payload[1]=1;payload[2]=(byte)(payload.length-3);payload[3]=(byte)command;payload[4]=(byte)channel;
        payload[5]=(byte)raw;
        if(width==2)payload[6]=(byte)(raw>>>8);
        return payload;
    }

    public String format(double value) {
        valueToRaw(value); // Reject non-finite/out-of-range values even in display paths.
        return switch(this) {
            case GAIN -> String.format(Locale.ROOT,"%.1f dB",value);
            case DELAY -> String.format(Locale.ROOT,"%.3f ms",value);
            case MUTE -> value==1 ? "AN" : "AUS";
        };
    }
}
