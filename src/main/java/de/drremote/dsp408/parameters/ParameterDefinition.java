package de.drremote.dsp408.parameters;

import java.util.Locale;
import java.util.Objects;

public record ParameterDefinition(int channelIndex,ParameterKind kind,ReadLocation readLocation,
                                  Evidence readEvidence,Evidence writeEvidence) {
    public ParameterDefinition {
        if(channelIndex<0 || channelIndex>11)throw new IllegalArgumentException("Invalid channel");
        Objects.requireNonNull(kind);Objects.requireNonNull(readEvidence);Objects.requireNonNull(writeEvidence);
    }
    public String channel() { return channelIndex<4 ? "In"+(char)('A'+channelIndex) : "Out"+(channelIndex-3); }
    public String id() { return channel()+"."+kind.name().toLowerCase(Locale.ROOT); }

    public ParameterValue read(BlockData data) {
        if(readLocation==null || readEvidence.level()==Evidence.Level.UNKNOWN)
            return new ParameterValue(this,null,null,ParameterValue.Status.UNKNOWN,"Leseposition unbekannt");
        var stored=readLocation.raw(data);
        if(stored.isEmpty())return new ParameterValue(this,null,null,ParameterValue.Status.UNKNOWN,"Block/Bytes fehlen");
        int raw=stored.getAsInt();
        if(readEvidence.level()!=Evidence.Level.CONFIRMED)
            return new ParameterValue(this,raw,null,ParameterValue.Status.INFERRED,"unbestätigte Lesezuordnung");
        int valueRaw=readLocation.mask()==0 ? raw : (raw&readLocation.mask())==0 ? 0 : 1;
        try {
            return new ParameterValue(this,raw,kind.rawToValue(valueRaw),ParameterValue.Status.CONFIRMED,"bestätigt");
        } catch(IllegalArgumentException invalid) {
            return new ParameterValue(this,raw,null,ParameterValue.Status.INVALID,"Rohwert außerhalb Wertebereich");
        }
    }
}
