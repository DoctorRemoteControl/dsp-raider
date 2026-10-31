package de.drremote.dsp408.planning;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.parameters.ParameterKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** Strict input adapter: typos and duplicate fields must not disappear silently. */
public final class ChangeRequestFiles {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ChangeRequestFiles() { }

    public static ChangeRequest read(Path path) throws IOException {
        var root = MAPPER.readTree(path.toFile());
        fields(root, Set.of("device_version", "changes"));
        String version = text(root, "device_version");
        var changes = root.path("changes");
        if (!changes.isArray()) throw new IllegalArgumentException("changes must be an array");
        var result = new ArrayList<ParameterChange>();
        for (var entry : changes) {
            fields(entry, Set.of("channel", "parameter", "value"));
            int channel = channel(text(entry, "channel"));
            String name = text(entry, "parameter");
            ParameterKind kind;
            try { kind = ParameterKind.valueOf(name.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown parameter: " + name); }
            var value = entry.path("value");
            double scalar;
            if (kind == ParameterKind.MUTE && value.isBoolean()) scalar = value.asBoolean() ? 1 : 0;
            else if (value.isNumber()) scalar = value.doubleValue();
            else throw new IllegalArgumentException("value must be numeric (or boolean for mute)");
            result.add(new ParameterChange(channel, kind, scalar));
        }
        return new ChangeRequest(version, result);
    }

    private static void fields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()) throw new IllegalArgumentException("Expected a request object");
        var names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown request field: " + name);
        }
    }

    private static String text(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing/invalid " + field);
        return value.asText().strip();
    }

    private static int channel(String name) {
        for (int index = 0; index < 12; index++) {
            String candidate = index < 4 ? "In" + (char) ('A' + index) : "Out" + (index - 3);
            if (candidate.equalsIgnoreCase(name)) return index;
        }
        throw new IllegalArgumentException("Unknown channel: " + name);
    }
}
