package de.drremote.dsp408.decode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.parameters.ParameterKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DspLibLookup {
    private final JsonNode root;
    private final Map<Integer, String> channelByIndex;
    private final Map<Integer, String> inputByIndex;
    private final Map<Integer, String> outputByIndex;
    private final Map<Integer, String> inputMaskByValue;
    private final List<String> geqBands;
    private final List<String> peqTypes;
    private final List<String> crossoverSlopes;
    private final List<String> compressorRatios;
    private final List<String> inputCompressorTypes;
    private final Map<Integer, String> presetSlotByIndex;
    private final Map<Integer, String> testToneSourceByIndex;
    private final Map<Integer, String> testToneFreqByIndex;

    private DspLibLookup(JsonNode root) {
        this.root = root;
        this.channelByIndex = invertIndexMap(root.at("/channels/index_map"));
        this.inputByIndex = filteredByPrefix(channelByIndex, "In");
        this.outputByIndex = filteredByPrefix(channelByIndex, "Out");
        this.inputMaskByValue = invertHexValueMap(root.at("/channels/matrix_input_bits"));
        this.geqBands = textArray(root.at("/enums/geq_band_freqs_hz"));
        this.peqTypes = textArray(root.at("/enums/peq_types"));
        this.crossoverSlopes = textArray(root.at("/enums/crossover_slopes"));
        this.compressorRatios = textArray(root.at("/enums/compressor_ratios"));
        this.inputCompressorTypes = textArray(root.at("/enums/input_compressor_types"));
        this.presetSlotByIndex = invertHexKeyMap(root.at("/parameters/preset_management/write/load/preset_index_mapping"));
        this.testToneSourceByIndex = invertHexKeyMap(root.at("/parameters/test_tone_generator/write/source_index_mapping"));
        this.testToneFreqByIndex = invertSelectorMap(root.at("/value_models/test_tone_sine_frequency/selector_mapping"));
    }

    public static DspLibLookup load(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(path.toFile());
        return new DspLibLookup(root);
    }

    public JsonNode root() {
        return root;
    }

    public String channelName(int index) {
        return channelByIndex.getOrDefault(index, "ch#" + index);
    }

    public String inputName(int index) {
        return inputByIndex.getOrDefault(index, "in#" + index);
    }

    public String outputName(int index) {
        return outputByIndex.getOrDefault(index, "out#" + index);
    }

    public String inputNameFromMask(int mask) {
        return inputMaskByValue.getOrDefault(mask, "mask=0x%02X".formatted(mask));
    }

    public String inputNamesFromMask(int mask) {
        String direct = inputMaskByValue.get(mask);
        if (direct != null) {
            return direct;
        }
        if (mask == 0) {
            return "none";
        }

        List<String> names = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : inputMaskByValue.entrySet()) {
            int bit = entry.getKey();
            if (bit != 0 && (mask & bit) == bit) {
                names.add(entry.getValue());
            }
        }
        if (names.isEmpty()) {
            return "mask=0x%02X".formatted(mask);
        }
        return String.join(" + ", names);
    }

    public String geqBandName(int index) {
        return indexed(geqBands, index, "band#" + index);
    }

    public String peqType(int index) {
        return indexed(peqTypes, index, "type#" + index);
    }

    public String compressorRatio(int index) {
        return indexed(compressorRatios, index, "ratio#" + index);
    }

    public String inputCompressorType(int index) {
        return indexed(inputCompressorTypes, index, "type#" + index);
    }

    public String crossoverSlopeByRaw(int raw) {
        return raw >= 1 && raw <= crossoverSlopes.size()
                ? crossoverSlopes.get(raw - 1)
                : "mode#0x%02X".formatted(raw);
    }

    public double gainDb(int raw) {
        return ParameterKind.GAIN.rawToValue(raw);
    }

    public double matrixCrosspointGainDb(int raw) {
        return gainDb(raw);
    }

    public String testToneSource(int index) {
        return testToneSourceByIndex.getOrDefault(index, "source#" + index);
    }

    public String testToneFrequency(int index) {
        return testToneFreqByIndex.getOrDefault(index, "freq#" + index);
    }

    public String presetSlot(int index) {
        return presetSlotByIndex.getOrDefault(index, "preset#" + index);
    }

    public String statusAt(String jsonPointer) {
        JsonNode node = root.at(jsonPointer + "/status");
        return node.isTextual() ? node.asText() : "unknown";
    }

    private static Map<Integer, String> invertIndexMap(JsonNode node) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (!node.isObject()) {
            return out;
        }

// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().canConvertToInt()) {
                out.put(entry.getValue().asInt(), entry.getKey());
            } else if (entry.getValue().isTextual()) {
                try {
                    out.put(parseFlexibleInt(entry.getValue().asText()), entry.getKey());
                } catch (NumberFormatException ignored) {
                    // Some libraries may carry descriptive channel metadata here.
                }
            }
        }
        return out;
    }

    private static Map<Integer, String> filteredByPrefix(Map<Integer, String> source, String prefix) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : source.entrySet()) {
            if (entry.getValue().startsWith(prefix)) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static Map<Integer, String> invertHexValueMap(JsonNode node) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (!node.isObject()) {
            return out;
        }

        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isTextual()) {
                out.put(parseFlexibleInt(entry.getValue().asText()), entry.getKey());
            }
        }
        return out;
    }

    private static Map<Integer, String> invertHexKeyMap(JsonNode node) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (!node.isObject()) {
            return out;
        }

        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isTextual()) {
                out.put(parseFlexibleInt(entry.getKey()), entry.getValue().asText());
            }
        }
        return out;
    }

    private static Map<Integer, String> invertSelectorMap(JsonNode node) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (!node.isObject()) {
            return out;
        }

        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            if (entry.getValue().canConvertToInt()) {
                out.put(entry.getValue().asInt(), entry.getKey());
            }
        }
        return out;
    }

    private static List<String> textArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }

        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static String indexed(List<String> values, int index, String fallback) {
        return index >= 0 && index < values.size() ? values.get(index) : fallback;
    }

    private static int parseFlexibleInt(String text) {
        String value = text.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            return Integer.parseInt(value.substring(2), 16);
        }
        return Integer.parseInt(value);
    }
}
