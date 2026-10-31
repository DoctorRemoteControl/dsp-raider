package de.drremote.dsp408.parameters;

import java.util.List;

public record ParameterProfile(String version,List<ParameterDefinition> definitions) {
    public ParameterProfile { definitions=List.copyOf(definitions); }
    public ParameterDefinition definition(int channel,ParameterKind kind) {
        return definitions.stream().filter(d->d.channelIndex()==channel && d.kind()==kind).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown channel parameter"));
    }
    public List<ParameterValue> values(BlockData data) { return definitions.stream().map(d->d.read(data)).toList(); }
}
