package de.drremote.dsp408.planning;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ChangeRequest(String deviceVersion, List<ParameterChange> changes) {
    public ChangeRequest {
        if (deviceVersion == null || deviceVersion.isBlank()) throw new IllegalArgumentException("Missing device_version");
        deviceVersion = deviceVersion.strip();
        changes = List.copyOf(changes);
        if (changes.size() > 36) throw new IllegalArgumentException("At most 36 channel parameters are supported");
        Set<String> keys = new HashSet<>();
        for (var change : changes) {
            if (!keys.add(change.channelIndex() + ":" + change.kind()))
                throw new IllegalArgumentException("Duplicate parameter: " + change.channel() + " " + change.kind().label());
        }
    }
}
