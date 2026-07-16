package de.drremote.dsp408.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DspLibValidatorMain {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--compare-structure".equals(args[0])) {
            runStructureCompare(args);
            return;
        }

        Path jsonPath = args.length > 0 ? Path.of(args[0]) : Path.of("DspLib-408.json");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonPath.toFile());

        DspLibValidator validator = new DspLibValidator();
        List<ValidationIssue> issues = validator.validate(root);

        long errorCount = issues.stream().filter(i -> i.severity() == ValidationSeverity.ERROR).count();
        long warningCount = issues.stream().filter(i -> i.severity() == ValidationSeverity.WARNING).count();
        long infoCount = issues.stream().filter(i -> i.severity() == ValidationSeverity.INFO).count();

        System.out.println("DSP408 library validation");
        System.out.println("File     : " + jsonPath.toAbsolutePath());
        System.out.println("Errors   : " + errorCount);
        System.out.println("Warnings : " + warningCount);
        System.out.println("Infos    : " + infoCount);
        System.out.println();

        for (ValidationIssue issue : issues) {
            System.out.println(issue);
        }

        if (errorCount > 0) {
            System.exit(2);
        }
        if (warningCount > 0) {
            System.exit(1);
        }
        System.exit(0);
    }

    private static void runStructureCompare(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: DspLibValidatorMain --compare-structure <base.json> <candidate.json> [--allow-prefix <json.path>]...");
            System.exit(2);
        }

        Path basePath = Path.of(args[1]);
        Path candidatePath = Path.of(args[2]);
        Set<String> allowedPrefixes = new LinkedHashSet<>();

        for (int i = 3; i < args.length; i++) {
            if (!"--allow-prefix".equals(args[i]) || i + 1 >= args.length) {
                System.err.println("Unexpected argument: " + args[i]);
                System.err.println("Usage: DspLibValidatorMain --compare-structure <base.json> <candidate.json> [--allow-prefix <json.path>]...");
                System.exit(2);
            }
            allowedPrefixes.add(args[++i]);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode base = mapper.readTree(basePath.toFile());
        JsonNode candidate = mapper.readTree(candidatePath.toFile());

        DspLibStructureComparator comparator = new DspLibStructureComparator(allowedPrefixes);
        List<ValidationIssue> issues = comparator.compare(base, candidate);

        long errorCount = issues.stream().filter(i -> i.severity() == ValidationSeverity.ERROR).count();
        long infoCount = issues.stream().filter(i -> i.severity() == ValidationSeverity.INFO).count();

        System.out.println("DSP408 library structure comparison");
        System.out.println("Base      : " + basePath.toAbsolutePath());
        System.out.println("Candidate : " + candidatePath.toAbsolutePath());
        System.out.println("Errors    : " + errorCount);
        System.out.println("Infos     : " + infoCount);
        if (!allowedPrefixes.isEmpty()) {
            System.out.println("Allowed   : " + String.join(", ", allowedPrefixes));
        }
        System.out.println();

        for (ValidationIssue issue : issues) {
            System.out.println(issue);
        }

        System.exit(errorCount > 0 ? 2 : 0);
    }
}

final class DspLibStructureComparator {
    private final Set<String> allowedPrefixes;

    DspLibStructureComparator(Set<String> allowedPrefixes) {
        this.allowedPrefixes = Collections.unmodifiableSet(new LinkedHashSet<>(allowedPrefixes));
    }

    List<ValidationIssue> compare(JsonNode base, JsonNode candidate) {
        List<ValidationIssue> issues = new ArrayList<>();
        compareShapes(JsonShape.from(base), JsonShape.from(candidate), "$", issues);
        return issues.stream()
                .filter(issue -> !isAllowed(issue.path()))
                .sorted(Comparator
                        .comparing(ValidationIssue::severity)
                        .thenComparing(ValidationIssue::code)
                        .thenComparing(ValidationIssue::path))
                .toList();
    }

    private void compareShapes(JsonShape base, JsonShape candidate, String path, List<ValidationIssue> issues) {
        if (base.kind() != candidate.kind()) {
            issues.add(error(
                    "JSON_STRUCTURE_TYPE_MISMATCH",
                    path,
                    "Base has " + base.kind() + " but candidate has " + candidate.kind()
            ));
            return;
        }

        if (base.kind() == JsonShapeKind.OBJECT) {
            compareObjectShapes(base, candidate, path, issues);
            return;
        }

        if (base.kind() == JsonShapeKind.ARRAY) {
            JsonShape baseElement = base.elementShape();
            JsonShape candidateElement = candidate.elementShape();
            if (baseElement == null && candidateElement == null) {
                return;
            }
            if (baseElement == null || candidateElement == null) {
                issues.add(error(
                        "JSON_ARRAY_ELEMENT_STRUCTURE_MISMATCH",
                        path + "[]",
                        baseElement == null
                                ? "Base array is empty but candidate array has element structure"
                                : "Base array has element structure but candidate array is empty"
                ));
                return;
            }
            compareShapes(baseElement, candidateElement, path + "[]", issues);
        }
    }

    private void compareObjectShapes(JsonShape base, JsonShape candidate, String path, List<ValidationIssue> issues) {
        Set<String> baseFields = base.fields().keySet();
        Set<String> candidateFields = candidate.fields().keySet();

        for (String field : baseFields) {
            if (!candidateFields.contains(field)) {
                issues.add(error(
                        "JSON_STRUCTURE_FIELD_MISSING_IN_CANDIDATE",
                        childPath(path, field),
                        "Field exists in base but not in candidate"
                ));
            }
        }

        for (String field : candidateFields) {
            if (!baseFields.contains(field)) {
                issues.add(error(
                        "JSON_STRUCTURE_FIELD_ADDED_IN_CANDIDATE",
                        childPath(path, field),
                        "Field exists in candidate but not in base"
                ));
            }
        }

        for (String field : baseFields) {
            if (candidateFields.contains(field)) {
                compareShapes(base.fields().get(field), candidate.fields().get(field), childPath(path, field), issues);
            }
        }
    }

    private boolean isAllowed(String path) {
        for (String prefix : allowedPrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "[")) {
                return true;
            }
        }
        return false;
    }

    private static String childPath(String parent, String field) {
        return parent + "." + field;
    }

    private static ValidationIssue error(String code, String path, String message) {
        return new ValidationIssue(ValidationSeverity.ERROR, code, path, message);
    }
}

enum JsonShapeKind {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    BINARY,
    POJO,
    MISSING
}

record JsonShape(
        JsonShapeKind kind,
        Map<String, JsonShape> fields,
        JsonShape elementShape
) {
    static JsonShape from(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return leaf(JsonShapeKind.MISSING);
        }
        if (node.isObject()) {
            Map<String, JsonShape> fields = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                fields.put(entry.getKey(), from(entry.getValue()));
            }
            return new JsonShape(JsonShapeKind.OBJECT, Collections.unmodifiableMap(fields), null);
        }
        if (node.isArray()) {
            JsonShape elementShape = null;
            for (JsonNode item : node) {
                elementShape = merge(elementShape, from(item));
            }
            return new JsonShape(JsonShapeKind.ARRAY, Map.of(), elementShape);
        }
        if (node.isTextual()) {
            return leaf(JsonShapeKind.STRING);
        }
        if (node.isNumber()) {
            return leaf(JsonShapeKind.NUMBER);
        }
        if (node.isBoolean()) {
            return leaf(JsonShapeKind.BOOLEAN);
        }
        if (node.isNull()) {
            return leaf(JsonShapeKind.NULL);
        }
        if (node.isBinary()) {
            return leaf(JsonShapeKind.BINARY);
        }
        if (node.isPojo()) {
            return leaf(JsonShapeKind.POJO);
        }
        return leaf(JsonShapeKind.MISSING);
    }

    private static JsonShape merge(JsonShape current, JsonShape next) {
        if (current == null) {
            return next;
        }
        if (current.kind() != next.kind()) {
            Map<String, JsonShape> variants = new LinkedHashMap<>();
            variants.put(current.kind().name(), current);
            variants.put(next.kind().name(), next);
            return new JsonShape(JsonShapeKind.OBJECT, Collections.unmodifiableMap(variants), null);
        }
        if (current.kind() == JsonShapeKind.OBJECT) {
            Map<String, JsonShape> merged = new LinkedHashMap<>(current.fields());
            for (Map.Entry<String, JsonShape> entry : next.fields().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), JsonShape::merge);
            }
            return new JsonShape(JsonShapeKind.OBJECT, Collections.unmodifiableMap(merged), null);
        }
        if (current.kind() == JsonShapeKind.ARRAY) {
            return new JsonShape(JsonShapeKind.ARRAY, Map.of(), merge(current.elementShape(), next.elementShape()));
        }
        return current;
    }

    private static JsonShape leaf(JsonShapeKind kind) {
        return new JsonShape(kind, Map.of(), null);
    }
}

final class DspLibValidator {

    /**
     * Many observed read offsets in DspLib-408.json are documented in the 0x24-response
     * coordinate system where the block payload starts at offset 5.
     * Some older/manual entries still use raw block-local offsets starting at 0.
     *
     * To avoid false positives, bounds checks accept the full documented coordinate space
     * 0 .. (5 + blockLen - 1).
     */
    private static final int BLOCK_DUMP_BASE_OFFSET = 5;

    private static final Pattern HEX_COMMAND = Pattern.compile("^0x[0-9A-Fa-f]{2}$");
    private static final Pattern STATUS_TOKEN = Pattern.compile("^[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*$");
    private static final Pattern RANGE_DOTS = Pattern.compile("^(\\d+)\\.\\.(\\d+)$");
    private static final Pattern HEX_BYTE_LIKE = Pattern.compile("^(?:0x|0X)?[0-9A-Fa-f]{2}$");

    private static final Set<String> BUILTIN_VALUE_MODELS = Set.of(
            "u8",
            "u16",
            "u16le",
            "u16le_candidate",
            "enum16",
            "bitmask",
            "bitmask_u8",
            "ascii",
            "mixed_u16le_u8_plus_bitmask",
            "mixed_u8_u16le_candidate",
            "runtime_level_drive_pair"
    );

    public List<ValidationIssue> validate(JsonNode root) {
        List<ValidationIssue> issues = new ArrayList<>();

        ValidationContext ctx = buildContext(root, issues);

        validateTopLevel(root, ctx, issues);
        walkForReferences(root, "$", ctx, issues);
        collectReadClaims(root, ctx, issues);
        walkForFieldLayouts(root, "$", issues);

        return issues.stream()
                .sorted(Comparator
                        .comparing(ValidationIssue::severity)
                        .thenComparing(ValidationIssue::code)
                        .thenComparing(ValidationIssue::path))
                .toList();
    }

    private ValidationContext buildContext(JsonNode root, List<ValidationIssue> issues) {
        Set<String> valueModels = new LinkedHashSet<>();
        collectFieldNames(root.path("value_models"), valueModels);

        Set<String> enums = new LinkedHashSet<>();
        collectFieldNames(root.path("enums"), enums);

        Set<String> allChannels = new LinkedHashSet<>();
        Set<String> inputs = new LinkedHashSet<>();
        Set<String> outputs = new LinkedHashSet<>();

        JsonNode channelsNode = root.path("channels");
        if (channelsNode.has("index_map") && channelsNode.get("index_map").isObject()) {
            collectFieldNames(channelsNode.get("index_map"), allChannels);
        }
        collectTextArray(channelsNode.get("inputs"), inputs);
        collectTextArray(channelsNode.get("outputs"), outputs);

        Map<String, Integer> blockLengths = new LinkedHashMap<>();
        JsonNode blocks = root.at("/protocol/block_dump/blocks_hex");
        int regularLen = root.at("/protocol/block_dump/observed_layout/regular_block_length").asInt(0);
        int lastLen = root.at("/protocol/block_dump/observed_layout/last_block_length").asInt(regularLen);

        if (blocks.isArray()) {
            for (int i = 0; i < blocks.size(); i++) {
                String raw = blocks.get(i).asText();
                String normalized = normalizeHexByte(raw, "$.protocol.block_dump.blocks_hex[" + i + "]", issues);
                if (normalized == null) {
                    continue;
                }
                int len = (i == blocks.size() - 1) ? lastLen : regularLen;
                if (len <= 0) {
                    len = regularLen > 0 ? regularLen : 0;
                }
                blockLengths.put(normalized, len);
            }
        }

        return new ValidationContext(valueModels, enums, allChannels, inputs, outputs, blockLengths);
    }

    private void validateTopLevel(JsonNode root, ValidationContext ctx, List<ValidationIssue> issues) {
        JsonNode channelCounts = root.at("/device/channel_counts");
        int expectedInputs = channelCounts.path("inputs").asInt(-1);
        int expectedOutputs = channelCounts.path("outputs").asInt(-1);
        int expectedTotal = channelCounts.path("total").asInt(-1);

        if (expectedInputs >= 0 && expectedInputs != ctx.inputs().size()) {
            issues.add(error(
                    "CHANNEL_INPUT_COUNT_MISMATCH",
                    "$.device.channel_counts.inputs",
                    "device.channel_counts.inputs=" + expectedInputs + " but channels.inputs has " + ctx.inputs().size()
            ));
        }

        if (expectedOutputs >= 0 && expectedOutputs != ctx.outputs().size()) {
            issues.add(error(
                    "CHANNEL_OUTPUT_COUNT_MISMATCH",
                    "$.device.channel_counts.outputs",
                    "device.channel_counts.outputs=" + expectedOutputs + " but channels.outputs has " + ctx.outputs().size()
            ));
        }

        if (expectedTotal >= 0 && expectedTotal != ctx.allChannels().size()) {
            issues.add(error(
                    "CHANNEL_TOTAL_COUNT_MISMATCH",
                    "$.device.channel_counts.total",
                    "device.channel_counts.total=" + expectedTotal + " but channels.index_map has " + ctx.allChannels().size()
            ));
        }

        if (expectedTotal >= 0 && expectedInputs >= 0 && expectedOutputs >= 0 && expectedTotal != expectedInputs + expectedOutputs) {
            issues.add(error(
                    "CHANNEL_TOTAL_NOT_SUM",
                    "$.device.channel_counts",
                    "total=" + expectedTotal + " but inputs+outputs=" + (expectedInputs + expectedOutputs)
            ));
        }

        Set<String> observedAsciiNames = new LinkedHashSet<>();
        collectTextArray(root.at("/channels/observed_ascii_channel_names"), observedAsciiNames);
        if (!observedAsciiNames.isEmpty() && !observedAsciiNames.equals(ctx.allChannels())) {
            issues.add(warning(
                    "OBSERVED_ASCII_CHANNEL_SET_MISMATCH",
                    "$.channels.observed_ascii_channel_names",
                    "observed_ascii_channel_names does not match channels.index_map keys exactly"
            ));
        }

        JsonNode blocks = root.at("/protocol/block_dump/blocks_hex");
        int blockCountObserved = root.at("/protocol/block_dump/observed_layout/block_count").asInt(-1);
        if (blocks.isArray() && blockCountObserved >= 0 && blockCountObserved != blocks.size()) {
            issues.add(error(
                    "BLOCK_COUNT_MISMATCH",
                    "$.protocol.block_dump.observed_layout.block_count",
                    "observed block_count=" + blockCountObserved + " but blocks_hex has " + blocks.size() + " entries"
            ));
        }

        JsonNode indexMap = root.at("/channels/index_map");
        JsonNode matrixOutputChannels = root.at("/channels/matrix_output_channels");
        if (indexMap.isObject() && matrixOutputChannels.isObject()) {
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            Iterator<Map.Entry<String, JsonNode>> it = matrixOutputChannels.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String channel = entry.getKey();
                if (!ctx.outputs().contains(channel)) {
                    issues.add(error(
                            "UNKNOWN_MATRIX_OUTPUT_CHANNEL",
                            "$.channels.matrix_output_channels." + channel,
                            "Unknown output channel: " + channel
                    ));
                    continue;
                }
                int fromHex = parseHexInt(entry.getValue().asText(), "$.channels.matrix_output_channels." + channel, issues);
                int fromIndexMap = indexMap.path(channel).asInt(Integer.MIN_VALUE);
                if (fromHex != Integer.MIN_VALUE && fromIndexMap != Integer.MIN_VALUE && fromHex != fromIndexMap) {
                    issues.add(error(
                            "MATRIX_OUTPUT_CHANNEL_VALUE_MISMATCH",
                            "$.channels.matrix_output_channels." + channel,
                            "hex value " + fromHex + " does not match channels.index_map value " + fromIndexMap
                    ));
                }
            }
        }

        JsonNode matrixInputBits = root.at("/channels/matrix_input_bits");
        if (matrixInputBits.isObject()) {
            Set<Integer> seenBits = new HashSet<>();
            Iterator<Map.Entry<String, JsonNode>> it = matrixInputBits.fields();
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String input = entry.getKey();
                if (!ctx.inputs().contains(input)) {
                    issues.add(error(
                            "UNKNOWN_MATRIX_INPUT_NAME",
                            "$.channels.matrix_input_bits." + input,
                            "Unknown input channel: " + input
                    ));
                    continue;
                }
                int bit = parseHexInt(entry.getValue().asText(), "$.channels.matrix_input_bits." + input, issues);
                if (bit != Integer.MIN_VALUE) {
                    if (Integer.bitCount(bit) != 1) {
                        issues.add(error(
                                "MATRIX_INPUT_BIT_NOT_POWER_OF_TWO",
                                "$.channels.matrix_input_bits." + input,
                                "Expected single-bit mask, got " + entry.getValue().asText()
                        ));
                    }
                    if (!seenBits.add(bit)) {
                        issues.add(error(
                                "DUPLICATE_MATRIX_INPUT_BIT",
                                "$.channels.matrix_input_bits." + input,
                                "Duplicate input mask value " + entry.getValue().asText()
                        ));
                    }
                }
            }
        }
    }

    private void walkForReferences(JsonNode node, String path, ValidationContext ctx, List<ValidationIssue> issues) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode child = entry.getValue();
                String childPath = path + "." + key;

                switch (key) {
                    case "value_model" -> validateValueModelReference(child, childPath, ctx, issues);
                    case "enum_ref", "band_frequencies_ref", "source_enum_ref", "slope_enum_ref" ->
                            validateEnumReference(child, childPath, ctx, issues);
                    case "command", "source_command" -> validateCommand(child, childPath, issues);
                    case "status" -> validateStatus(child, childPath, issues);
                    case "block_hex" -> validateBlockHex(child, childPath, ctx, issues);
                    case "offset_hex" -> validateHexNumber(child, childPath, issues);
                    case "channel", "input", "output", "same_as_output" ->
                            validateChannelName(key, child, childPath, ctx, issues);
                    default -> {
                    }
                }

                if (("inputs".equals(key) || "outputs".equals(key)) && child.isArray()) {
                    validateChannelArray(key, child, childPath, ctx, issues);
                }

                walkForReferences(child, childPath, ctx, issues);
            }
            return;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walkForReferences(node.get(i), path + "[" + i + "]", ctx, issues);
            }
        }
    }

    private void collectReadClaims(JsonNode root, ValidationContext ctx, List<ValidationIssue> issues) {
        JsonNode parameters = root.path("parameters");
        List<ReadClaim> claims = new ArrayList<>();

        if (parameters.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = parameters.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
                String parameterName = entry.getKey();
                JsonNode readNode = entry.getValue().path("read");
                String path = "$.parameters." + parameterName + ".read";
                ReadState state = new ReadState(
                        findTypeHint(readNode, null),
                        findStorageHint(readNode, null),
                        findCommandHint(readNode, null)
                );
                walkReadNode(readNode, path, state, ctx, issues, claims);
            }
        }

        detectReadClaimConflicts(claims, issues);
    }

    private void walkReadNode(JsonNode node,
                              String path,
                              ReadState inherited,
                              ValidationContext ctx,
                              List<ValidationIssue> issues,
                              List<ReadClaim> claims) {

        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        ReadState current = new ReadState(
                findTypeHint(node, inherited.typeHint()),
                findStorageHint(node, inherited.storageHint()),
                findCommandHint(node, inherited.commandHint())
        );

        if (node.isObject()) {
            ReadClaim claim = toReadClaim(node, path, current, ctx, issues);
            if (claim != null) {
                claims.add(claim);
            }

            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                walkReadNode(entry.getValue(), path + "." + entry.getKey(), current, ctx, issues, claims);
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            }
            return;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walkReadNode(node.get(i), path + "[" + i + "]", current, ctx, issues, claims);
            }
        }
    }

    private ReadClaim toReadClaim(JsonNode node,
                                  String path,
                                  ReadState state,
                                  ValidationContext ctx,
                                  List<ValidationIssue> issues) {

        if (!node.isObject()) {
            return null;
        }

        // channel_names.read.observed_ascii_locations are only start anchors, not safe full-string storage claims
        if (path.contains(".observed_ascii_locations[")) {
            return null;
        }

        if (node.has("block_hex") && (node.has("offset_dec") || node.has("offset_hex"))) {
            String rawBlock = text(node.get("block_hex"));
            if (rawBlock == null || !HEX_BYTE_LIKE.matcher(rawBlock.trim()).matches()) {
                return null;
            }

            String blockHex = normalizeHexByte(rawBlock, path + ".block_hex", issues);
            if (blockHex == null) {
                return null;
            }

            Integer start = null;
            if (node.has("offset_dec")) {
                start = node.get("offset_dec").asInt();
            } else if (node.has("offset_hex")) {
                int parsed = parseHexInt(text(node.get("offset_hex")), path + ".offset_hex", issues);
                if (parsed != Integer.MIN_VALUE) {
                    start = parsed;
                }
            }

            if (start == null) {
                return null;
            }

            int width = inferReadWidth(node, state.typeHint(), path);
            validateBlockBounds(blockHex, start, width, path, ctx, issues);

            return new ReadClaim("block:" + blockHex, start, width, path);
        }

        String storage = state.storageHint();
        if (storage != null && (node.has("payload_offset") || node.has("offset"))) {
            int start = node.has("payload_offset") ? node.get("payload_offset").asInt() : node.get("offset").asInt();
            int width = inferReadWidth(node, state.typeHint(), path);

            return new ReadClaim("storage:" + storage, start, width, path);
        }

        return null;
    }

    private void detectReadClaimConflicts(List<ReadClaim> claims, List<ValidationIssue> issues) {
        Map<String, List<ReadClaim>> byDomain = new LinkedHashMap<>();
        for (ReadClaim claim : claims) {
            byDomain.computeIfAbsent(claim.domain(), k -> new ArrayList<>()).add(claim);
        }

        Set<String> emittedPairs = new HashSet<>();

        for (Map.Entry<String, List<ReadClaim>> entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            List<ReadClaim> domainClaims = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ReadClaim::start))
                    .toList();

            for (int i = 0; i < domainClaims.size(); i++) {
                ReadClaim a = domainClaims.get(i);
                for (int j = i + 1; j < domainClaims.size(); j++) {
                    ReadClaim b = domainClaims.get(j);

                    if (b.start() >= a.endExclusive()) {
                        break;
                    }

                    String pairKey = stablePairKey(a.path(), b.path());
                    if (!emittedPairs.add(pairKey)) {
                        continue;
                    }

                    if (a.start() == b.start() && a.width() == b.width()) {
                        issues.add(info(
                                "DUPLICATE_READ_LOCATION",
                                a.path(),
                                "Same read range as " + b.path()
                                        + " in " + domain
                                        + " @ " + formatRange(a.start(), a.width())
                        ));
                    } else {
                        issues.add(error(
                                "READ_LOCATION_OVERLAP",
                                a.path(),
                                "Overlaps with " + b.path()
                                        + " in " + domain
                                        + " (" + formatRange(a.start(), a.width())
                                        + " vs " + formatRange(b.start(), b.width()) + ")"
                        ));
                    }
                }
            }
        }
    }

    private void walkForFieldLayouts(JsonNode node, String path, List<ValidationIssue> issues) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            if (node.has("field_layout") && node.get("field_layout").isObject()) {
                validateFieldLayout(node, path, issues);
            }

            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                walkForFieldLayouts(entry.getValue(), path + "." + entry.getKey(), issues);
            }
// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            return;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walkForFieldLayouts(node.get(i), path + "[" + i + "]", issues);
            }
        }
    }

    private void validateFieldLayout(JsonNode ownerNode, String ownerPath, List<ValidationIssue> issues) {
        JsonNode fieldLayout = ownerNode.get("field_layout");
        List<FieldSpan> spans = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> it = fieldLayout.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String fieldName = entry.getKey();
            JsonNode fieldNode = entry.getValue();

// [PROBLEM2CODE] WARNING: The method fields() from the type JsonNode is deprecated
            if (!fieldNode.has("payload_offset_dec")) {
                continue;
            }

            int start = fieldNode.get("payload_offset_dec").asInt();
            int width = inferFieldLayoutWidth(fieldName, fieldNode);
            spans.add(new FieldSpan(fieldName, start, width, ownerPath + ".field_layout." + fieldName));
        }

        JsonNode fieldOrder = ownerNode.get("field_order");
        if (fieldOrder != null && fieldOrder.isArray()) {
            Set<String> layoutNames = new LinkedHashSet<>();
            for (FieldSpan span : spans) {
                layoutNames.add(span.name());
            }
            for (int i = 0; i < fieldOrder.size(); i++) {
                String orderedName = fieldOrder.get(i).asText();
                if (!layoutNames.contains(orderedName)) {
                    issues.add(warning(
                            "FIELD_ORDER_REFERENCE_MISSING",
                            ownerPath + ".field_order[" + i + "]",
                            "field_order references '" + orderedName + "' but field_layout does not contain it"
                    ));
                }
            }
        }

        spans = spans.stream().sorted(Comparator.comparingInt(FieldSpan::start)).toList();

        for (int i = 0; i < spans.size(); i++) {
            FieldSpan a = spans.get(i);
            for (int j = i + 1; j < spans.size(); j++) {
                FieldSpan b = spans.get(j);
                if (b.start() >= a.endExclusive()) {
                    break;
                }
                issues.add(error(
                        "WRITE_FIELD_LAYOUT_OVERLAP",
                        a.path(),
                        "Overlaps with " + b.path()
                                + " (" + formatRange(a.start(), a.width())
                                + " vs " + formatRange(b.start(), b.width()) + ")"
                ));
            }
        }
    }

    private int inferFieldLayoutWidth(String fieldName, JsonNode fieldNode) {
        String typeHint = fieldName.toLowerCase();
        String explicitType = text(fieldNode.get("type"));
        if (explicitType != null) {
            typeHint = typeHint + " " + explicitType.toLowerCase();
        }

        if (typeHint.contains("u16") || typeHint.contains("enum16")) {
            return 2;
        }
        if (typeHint.contains("u8") || typeHint.contains("bitmask")) {
            return 1;
        }
        return 1;
    }

   private int inferReadWidth(JsonNode node, String inheritedTypeHint, String path) {
    if (node.has("length") && node.get("length").canConvertToInt()) {
        int len = node.get("length").asInt();
        if (len > 0) {
            return len;
        }
    }

    if (node.has("offset_range")) {
        int len = parseRangeWidth(text(node.get("offset_range")));
        if (len > 0) {
            return len;
        }
    }

    String pathLower = path.toLowerCase();
    String leaf = lastPathSegment(pathLower);

    // one-byte logical leaf fields
    if (pathLower.contains(".q_raw") || leaf.endsWith("_q_raw")) {
        return 1;
    }
    if (pathLower.contains(".filter_type") || leaf.endsWith("_filter_type")) {
        return 1;
    }
    if (pathLower.contains(".bypass") || leaf.endsWith("_bypass")) {
        return 1;
    }
    if (pathLower.contains(".ratio") || leaf.endsWith("_ratio")) {
        return 1;
    }
    if (pathLower.contains(".knee") || leaf.endsWith("_knee")) {
        return 1;
    }

    // two-byte logical leaf fields
    if (pathLower.contains(".gain") || leaf.endsWith("_gain")) {
        return 2;
    }
    if (pathLower.contains(".frequency") || leaf.endsWith("_frequency")) {
        return 2;
    }
    if (pathLower.contains(".attack") || leaf.endsWith("_attack")) {
        return 2;
    }
    if (pathLower.contains(".release") || leaf.endsWith("_release")) {
        return 2;
    }
    if (pathLower.contains(".hold") || leaf.endsWith("_hold")) {
        return 2;
    }
    if (pathLower.contains(".threshold") || leaf.endsWith("_threshold")) {
        return 2;
    }
    if (pathLower.contains(".delay") || leaf.endsWith("_delay")) {
        return 2;
    }
    if (pathLower.contains(".phase") || leaf.endsWith("_phase")) {
        return 2;
    }

    String typeHint = findTypeHint(node, null);
    if (typeHint != null) {
        String lower = typeHint.toLowerCase();
        if (lower.contains("u16") || lower.contains("enum16")) {
            return 2;
        }
        if (lower.contains("u8") || lower.contains("bitmask")) {
            return 1;
        }
        if (lower.contains("ascii") && node.has("value_observed") && node.get("value_observed").isTextual()) {
            return node.get("value_observed").asText().length();
        }
    }

    // do not blindly inherit mixed_* aggregate types
    if (inheritedTypeHint != null) {
        String lower = inheritedTypeHint.toLowerCase();
        if (!lower.startsWith("mixed_")) {
            if (lower.contains("u16") || lower.contains("enum16")) {
                return 2;
            }
            if (lower.contains("u8") || lower.contains("bitmask")) {
                return 1;
            }
        }
    }

    if (node.has("name") && node.get("name").isTextual() && !node.has("channel")) {
        return node.get("name").asText().length();
    }

    return 1;
}

private String lastPathSegment(String path) {
    int dot = path.lastIndexOf('.');
    if (dot >= 0 && dot + 1 < path.length()) {
        return path.substring(dot + 1);
    }
    return path;
}

    private String findTypeHint(JsonNode node, String inherited) {
        String directType = text(node.get("type"));
        if (directType != null && !directType.isBlank()) {
            return directType;
        }

        String encoding = text(node.get("encoding"));
        if (encoding != null && !encoding.isBlank()) {
            return encoding;
        }

        String valueModel = text(node.get("value_model"));
        if (valueModel != null && BUILTIN_VALUE_MODELS.contains(valueModel)) {
            return valueModel;
        }

        return inherited;
    }

    private String findStorageHint(JsonNode node, String inherited) {
        String directStorage = text(node.get("storage"));
        return directStorage != null ? directStorage : inherited;
    }

    private String findCommandHint(JsonNode node, String inherited) {
        String sourceCommand = text(node.get("source_command"));
        if (sourceCommand != null) {
            return sourceCommand;
        }
        String command = text(node.get("command"));
        return command != null ? command : inherited;
    }

    private void validateValueModelReference(JsonNode node, String path, ValidationContext ctx, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }
        String ref = node.asText();
        if (BUILTIN_VALUE_MODELS.contains(ref)) {
            return;
        }
        if (!ctx.valueModels().contains(ref)) {
            issues.add(error(
                    "UNKNOWN_VALUE_MODEL",
                    path,
                    "Unknown value_model reference: " + ref
            ));
        }
    }

    private void validateEnumReference(JsonNode node, String path, ValidationContext ctx, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }
        String ref = node.asText();
        if (!ctx.enums().contains(ref)) {
            issues.add(error(
                    "UNKNOWN_ENUM_REFERENCE",
                    path,
                    "Unknown enum reference: " + ref
            ));
        }
    }

    private void validateCommand(JsonNode node, String path, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }
        String command = node.asText();
        if (!HEX_COMMAND.matcher(command).matches()) {
            issues.add(warning(
                    "COMMAND_FORMAT_UNEXPECTED",
                    path,
                    "Expected command like 0x33, got '" + command + "'"
            ));
        }
    }

    private void validateStatus(JsonNode node, String path, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }
        String status = node.asText();
        if (!STATUS_TOKEN.matcher(status).matches()) {
            issues.add(warning(
                    "STATUS_FORMAT_UNEXPECTED",
                    path,
                    "Expected status token with letters/digits/underscores, got '" + status + "'"
            ));
        }
    }

    private void validateBlockHex(JsonNode node, String path, ValidationContext ctx, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }

        String raw = node.asText().trim();

        // formulas like "base_block + floor(slot / 25)" are documentation, not literal block refs
        if (!HEX_BYTE_LIKE.matcher(raw).matches()) {
            return;
        }

        String normalized = normalizeHexByte(raw, path, issues);
        if (normalized != null && !ctx.blockLengthsByHex().containsKey(normalized)) {
            issues.add(error(
                    "UNKNOWN_BLOCK_HEX",
                    path,
                    "Block '" + raw + "' is not present in protocol.block_dump.blocks_hex"
            ));
        }
    }

    private void validateHexNumber(JsonNode node, String path, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }
        parseHexInt(node.asText(), path, issues);
    }

    private void validateChannelName(String key, JsonNode node, String path, ValidationContext ctx, List<ValidationIssue> issues) {
        if (!node.isTextual()) {
            return;
        }
        String value = node.asText();

        if ("input".equals(key) && !ctx.inputs().contains(value)) {
            issues.add(error("UNKNOWN_INPUT_CHANNEL", path, "Unknown input channel: " + value));
            return;
        }

        if ("output".equals(key) && !ctx.outputs().contains(value)) {
            issues.add(error("UNKNOWN_OUTPUT_CHANNEL", path, "Unknown output channel: " + value));
            return;
        }

        if ("same_as_output".equals(key) && !ctx.outputs().contains(value)) {
            issues.add(error("UNKNOWN_OUTPUT_CHANNEL", path, "Unknown output channel: " + value));
            return;
        }

        if ("channel".equals(key) && !ctx.allChannels().contains(value)) {
            issues.add(error("UNKNOWN_CHANNEL", path, "Unknown channel: " + value));
        }
    }

    private void validateChannelArray(String key, JsonNode node, String path, ValidationContext ctx, List<ValidationIssue> issues) {
        if (!node.isArray()) {
            return;
        }

        Set<String> allowed = "inputs".equals(key) ? ctx.inputs() : ctx.outputs();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText();
            if (!allowed.contains(value)) {
                issues.add(error(
                        "UNKNOWN_CHANNEL_IN_ARRAY",
                        path + "[" + i + "]",
                        "Unexpected channel '" + value + "' in " + key + " array"
                ));
            }
            if (!seen.add(value)) {
                issues.add(warning(
                        "DUPLICATE_CHANNEL_IN_ARRAY",
                        path + "[" + i + "]",
                        "Duplicate channel '" + value + "' in " + key + " array"
                ));
            }
        }
    }

    private void validateBlockBounds(String blockHex,
                                     int start,
                                     int width,
                                     String path,
                                     ValidationContext ctx,
                                     List<ValidationIssue> issues) {
        Integer blockLen = ctx.blockLengthsByHex().get(blockHex);
        if (blockLen == null || blockLen <= 0) {
            return;
        }

        int maxExclusive = BLOCK_DUMP_BASE_OFFSET + blockLen;

        if (start < 0) {
            issues.add(error(
                    "NEGATIVE_OFFSET",
                    path,
                    "Negative offset " + start + " in block " + blockHex
            ));
            return;
        }

        if (start >= maxExclusive) {
            issues.add(error(
                    "OFFSET_OUT_OF_BLOCK",
                    path,
                    "Offset " + start + " is outside block " + blockHex
                            + " allowed coordinate range [0.." + (maxExclusive - 1) + "]"
            ));
            return;
        }

        if (start + width > maxExclusive) {
            issues.add(error(
                    "FIELD_EXCEEDS_BLOCK_LENGTH",
                    path,
                    "Range " + formatRange(start, width)
                            + " exceeds block " + blockHex
                            + " allowed coordinate range [0.." + (maxExclusive - 1) + "]"
            ));
        }
    }

    private static void collectFieldNames(JsonNode node, Collection<String> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            out.add(it.next());
        }
    }

    private static void collectTextArray(JsonNode node, Collection<String> out) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
    }

    private static String normalizeHexByte(String raw, String path, List<ValidationIssue> issues) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (!s.matches("^[0-9A-Fa-f]{2}$")) {
            issues.add(error(
                    "HEX_BYTE_FORMAT_INVALID",
                    path,
                    "Expected 2-digit hex byte, got '" + raw + "'"
            ));
            return null;
        }
        return s.toUpperCase();
    }

    private static int parseHexInt(String raw, String path, List<ValidationIssue> issues) {
        if (raw == null) {
            return Integer.MIN_VALUE;
        }
        String s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (!s.matches("^[0-9A-Fa-f]+$")) {
            issues.add(error(
                    "HEX_NUMBER_FORMAT_INVALID",
                    path,
                    "Expected hex number, got '" + raw + "'"
            ));
            return Integer.MIN_VALUE;
        }
        return Integer.parseInt(s, 16);
    }

    private static int parseRangeWidth(String raw) {
        if (raw == null) {
            return -1;
        }
        Matcher m = RANGE_DOTS.matcher(raw.trim());
        if (!m.matches()) {
            return -1;
        }
        int from = Integer.parseInt(m.group(1));
        int to = Integer.parseInt(m.group(2));
        if (to < from) {
            return -1;
        }
        return (to - from) + 1;
    }

    private static String formatRange(int start, int width) {
        return "[" + start + ".." + (start + width - 1) + "]";
    }

    private static String stablePairKey(String a, String b) {
        return (a.compareTo(b) <= 0) ? a + " <-> " + b : b + " <-> " + a;
    }

    private static String text(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    private static ValidationIssue error(String code, String path, String message) {
        return new ValidationIssue(ValidationSeverity.ERROR, code, path, message);
    }

    private static ValidationIssue warning(String code, String path, String message) {
        return new ValidationIssue(ValidationSeverity.WARNING, code, path, message);
    }

    private static ValidationIssue info(String code, String path, String message) {
        return new ValidationIssue(ValidationSeverity.INFO, code, path, message);
    }
}

enum ValidationSeverity {
    ERROR,
    WARNING,
    INFO
}

record ValidationIssue(
        ValidationSeverity severity,
        String code,
        String path,
        String message
) {
    @Override
    public String toString() {
        return "[" + severity + "] " + code + " @ " + path + " -> " + message;
    }
}

record ValidationContext(
        Set<String> valueModels,
        Set<String> enums,
        Set<String> allChannels,
        Set<String> inputs,
        Set<String> outputs,
        Map<String, Integer> blockLengthsByHex
) {
    ValidationContext {
        valueModels = Collections.unmodifiableSet(new LinkedHashSet<>(valueModels));
        enums = Collections.unmodifiableSet(new LinkedHashSet<>(enums));
        allChannels = Collections.unmodifiableSet(new LinkedHashSet<>(allChannels));
        inputs = Collections.unmodifiableSet(new LinkedHashSet<>(inputs));
        outputs = Collections.unmodifiableSet(new LinkedHashSet<>(outputs));
        blockLengthsByHex = Collections.unmodifiableMap(new LinkedHashMap<>(blockLengthsByHex));
    }
}

record ReadState(
        String typeHint,
        String storageHint,
        String commandHint
) {
}

record ReadClaim(
        String domain,
        int start,
        int width,
        String path
) {
    int endExclusive() {
        return start + width;
    }
}

record FieldSpan(
        String name,
        int start,
        int width,
        String path
) {
    int endExclusive() {
        return start + width;
    }
}
