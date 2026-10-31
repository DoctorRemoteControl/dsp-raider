package de.drremote.dsp408.script;

import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ProxyStatus;
import de.drremote.dsp408.model.ReadBlockSet;
import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.util.DspProtocol;
import de.drremote.dsp408.util.HexUtil;
import de.drremote.dsp408.parameters.ParameterValue;
import de.drremote.dsp408.planning.ChangePlan;
import de.drremote.dsp408.execution.ApplyResult;
import de.drremote.dsp408.snapshot.Snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScriptValueResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final ScriptRuntime state;

    ScriptValueResolver(ScriptRuntime state) {
        this.state = Objects.requireNonNull(state);
    }

    Object resolveValue(String token) throws Exception {
        if (token == null) {
            return null;
        }

        if (isQuotedString(token)) {
            String inner = token.substring(1, token.length() - 1);
            inner = interpolate(inner);
            return unescapeQuotedString(inner);
        }

        String interpolated = interpolate(token);

        if ("null".equalsIgnoreCase(interpolated)) {
            return null;
        }
        if ("true".equalsIgnoreCase(interpolated)) {
            return true;
        }
        if ("false".equalsIgnoreCase(interpolated)) {
            return false;
        }

        if (interpolated.startsWith("$") && interpolated.length() > 1) {
            return resolvePath(interpolated.substring(1));
        }

        if (state.variables.containsKey(interpolated)) {
            return state.variables.get(interpolated);
        }

        if (looksLikePath(interpolated)) {
            return resolvePath(interpolated);
        }

        if (interpolated.matches("0[xX][0-9A-Fa-f]+")) {
            return Integer.parseInt(interpolated.substring(2), 16);
        }

        if (interpolated.matches("-?\\d+")) {
            return Integer.parseInt(interpolated);
        }

        if (interpolated.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(interpolated);
        }

        return interpolated;
    }

    private static boolean isQuotedString(String token) {
        return token.length() >= 2
                && token.charAt(0) == '"'
                && token.charAt(token.length() - 1) == '"';
    }

    private static String unescapeQuotedString(String input) {
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    default -> {
                        sb.append('\\');
                        sb.append(c);
                    }
                }
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                continue;
            }

            sb.append(c);
        }

        if (escaping) {
            sb.append('\\');
        }

        return sb.toString();
    }

    Object resolvePath(String path) throws Exception {
        List<String> segments = splitPath(path);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Invalid variable path: " + path);
        }

        Object current = resolveFirstSegment(segments.get(0));

        for (int i = 1; i < segments.size(); i++) {
            current = resolveNextSegment(current, segments.get(i));
        }

        return current;
    }

    Object resolveProperty(Object current, String property) {
        if(current instanceof Snapshot snapshot) return switch(property) {
            case "deviceVersion" -> snapshot.identity().version();
            case "deviceProfile" -> snapshot.identity().profile();
            default -> throw new IllegalArgumentException("Unknown snapshot property: "+property);
        };
        if(current instanceof ParameterValue value) return switch(property) {
            case "value" -> value.value();
            case "raw" -> value.raw();
            case "status" -> value.status().name();
            case "channel" -> value.definition().channel();
            case "unit" -> value.definition().kind().unit();
            case "detail" -> value.detail();
            default -> throw new IllegalArgumentException("Unknown parameter property: "+property);
        };
        if(current instanceof ChangePlan.Entry entry) return switch(property) {
            case "status" -> entry.status().name();
            case "before" -> entry.before();
            case "targetValue" -> entry.change().targetValue();
            case "requestedValue" -> entry.change().value();
            case "rounded" -> entry.change().value()!=entry.change().targetValue();
            case "payloadHex" -> entry.payloadHex();
            case "reason" -> entry.reason();
            default -> throw new IllegalArgumentException("Unknown change property: "+property);
        };
        if(current instanceof ApplyResult result) return switch(property) {
            case "ok" -> result.ok();
            case "deviceVersion" -> result.deviceVersion();
            case "entries" -> result.entries();
            case "error" -> result.error();
            default -> throw new IllegalArgumentException("Unknown apply property: "+property);
        };
        if(current instanceof ApplyResult.Entry entry) return switch(property) {
            case "status" -> entry.status().name();
            case "readback" -> entry.readback();
            case "writeAttempted" -> entry.writeAttempted();
            case "detail" -> entry.detail();
            default -> throw new IllegalArgumentException("Unknown apply entry property: "+property);
        };
        if (current instanceof ProxyResponse response) {
            return switch (property) {
                case "raw" -> response.raw();
                case "payload" -> response.payload();
                case "checksumOk" -> response.checksumOk();
                case "command" -> response.command();
                case "commandHex" -> response.commandHex();
                case "readBlockIndex" -> response.readBlockIndex();
                case "rawHex" -> response.rawHex();
                case "payloadHex" -> response.payloadHex();
                case "payloadAscii" -> response.payloadAscii();
                case "rawLen" -> response.rawLen();
                case "payloadLen" -> response.payloadLen();
                default -> throw new IllegalArgumentException("Unknown response property: " + property);
            };
        }

        if (current instanceof ProxyStatus status) {
            return switch (property) {
                case "sessionActive" -> status.sessionActive();
                case "injectReady" -> status.injectReady();
                case "rawResponse" -> status.rawResponse();
                default -> throw new IllegalArgumentException("Unknown status property: " + property);
            };
        }

        if (current instanceof GuiCaptureResult capture) {
            return switch (property) {
                case "frames" -> capture.frames();
                case "totalFrames" -> capture.totalFrames();
                case "writeCount" -> capture.writeCount();
                case "responseCount" -> capture.responseCount();
                case "isEmpty" -> capture.isEmpty();
                case "firstFrame" -> capture.firstFrame();
                case "lastFrame" -> capture.lastFrame();
                case "firstWrite" -> capture.firstWrite();
                case "lastWrite" -> capture.lastWrite();
                case "firstResponse" -> capture.firstResponse();
                case "lastResponse" -> capture.lastResponse();
                case "writes" -> capture.writes();
                case "responses" -> capture.responses();
                case "readBlockResponses" -> capture.readBlockResponses();
                default -> throw new IllegalArgumentException("Unknown capture property: " + property);
            };
        }

        if (current instanceof ReadBlockSet blocks) {
            return switch (property) {
                case "responses", "blocks" -> blocks.responses();
                case "count", "size", "len" -> blocks.count();
                case "isEmpty" -> blocks.isEmpty();
                case "first" -> blocks.first();
                case "last" -> blocks.last();
                case "blockIndices" -> blocks.blockIndices();
                case "data" -> blocks.data();
                case "dataHex" -> blocks.dataHex();
                case "dataLen" -> blocks.dataLen();
                case "allBlocksHex" -> blocks.allBlocksHex();
                default -> throw new IllegalArgumentException("Unknown ReadBlockSet property: " + property);
            };
        }

        if (current instanceof SniffedFrame frame) {
            return switch (property) {
                case "direction" -> frame.direction();
                case "frame" -> frame.frame();
                case "payload" -> frame.payload();
                case "frameHex" -> frame.frameHex();
                case "payloadHex" -> frame.payloadHex();
                case "payloadAscii" -> frame.payloadAscii();
                case "command" -> frame.command();
                case "commandHex" -> frame.commandHex();
                case "readBlockIndex" -> frame.readBlockIndex();
                case "payloadLen" -> frame.payloadLen();
                case "checksumOk" -> frame.checksumOk();
                default -> throw new IllegalArgumentException("Unknown SniffedFrame property: " + property);
            };
        }

        if (current instanceof byte[] bytes) {
            return switch (property) {
                case "hex" -> HexUtil.toHex(bytes);
                case "ascii" -> HexUtil.payloadAscii(bytes);
                case "len" -> bytes.length;
                case "command" -> DspProtocol.command(bytes);
                case "commandHex" -> DspProtocol.commandHex(DspProtocol.command(bytes));
                case "readBlockIndex" -> DspProtocol.readBlockIndex(bytes);
                default -> throw new IllegalArgumentException("Unknown byte[] property: " + property);
            };
        }

        if (current instanceof List<?> list) {
            return switch (property) {
                case "size", "len" -> list.size();
                case "isEmpty" -> list.isEmpty();
                case "first" -> list.isEmpty() ? null : list.get(0);
                case "last" -> list.isEmpty() ? null : list.get(list.size() - 1);
                default -> throw new IllegalArgumentException("Unknown list property: " + property);
            };
        }

        throw new IllegalArgumentException("Property access not supported for type: "
                + current.getClass().getName());
    }

    boolean looksLikePath(String token) {
        String root = extractRootName(token);
        return root != null && state.variables.containsKey(root);
    }

    String interpolate(String input) throws Exception {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            Object value = resolvePath(expr);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    String joinResolvedTokens(List<String> tokens) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            Object value = resolveValue(tokens.get(i));
            sb.append(stringify(value));
        }
        return sb.toString();
    }

    private Object resolveFirstSegment(String segment) throws Exception {
        String name = extractSegmentName(segment);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Invalid variable path: " + segment);
        }

        Object current = state.variables.get(name);
        if (!state.variables.containsKey(name)) {
            throw new IllegalArgumentException("Unknown variable: " + name);
        }

        return applyIndexes(current, segment.substring(name.length()));
    }

    private Object resolveNextSegment(Object current, String segment) throws Exception {
        String name = extractSegmentName(segment);

        if (name != null && !name.isBlank()) {
            current = resolveProperty(current, name);
            return applyIndexes(current, segment.substring(name.length()));
        }

        return applyIndexes(current, segment);
    }

    private Object applyIndexes(Object current, String tail) throws Exception {
        int pos = 0;
        while (pos < tail.length()) {
            while (pos < tail.length() && Character.isWhitespace(tail.charAt(pos))) {
                pos++;
            }

            if (pos >= tail.length()) {
                break;
            }

            if (tail.charAt(pos) != '[') {
                throw new IllegalArgumentException("Invalid index expression: " + tail);
            }

            int end = findClosingBracket(tail, pos);
            String indexText = tail.substring(pos + 1, end).trim();
            int index = toInt(resolveValue(indexText));
            current = applySingleIndex(current, index);
            pos = end + 1;
        }

        return current;
    }

    static Object applySingleIndex(Object current, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index < 0: " + index);
        }

        if (current instanceof List<?> list) {
            if (index >= list.size()) {
                throw new IllegalArgumentException("List index out of range: " + index);
            }
            return list.get(index);
        }

        if (current instanceof byte[] bytes) {
            if (index >= bytes.length) {
                throw new IllegalArgumentException("Byte index out of range: " + index);
            }
            return Byte.toUnsignedInt(bytes[index]);
        }

        if (current instanceof String s) {
            if (index >= s.length()) {
                throw new IllegalArgumentException("String index out of range: " + index);
            }
            return String.valueOf(s.charAt(index));
        }

        throw new IllegalArgumentException("Index access not supported for type: "
                + current.getClass().getName());
    }

    private static int findClosingBracket(String text, int openPos) {
        int depth = 0;
        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Fehlende schliessende Klammer in: " + text);
    }

    private static List<String> splitPath(String path) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);

            if (c == '.' && bracketDepth == 0) {
                if (current.length() == 0) {
                    throw new IllegalArgumentException("Invalid variable path: " + path);
                }
                out.add(current.toString());
                current.setLength(0);
                continue;
            }

            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth--;
                if (bracketDepth < 0) {
                    throw new IllegalArgumentException("Invalid variable path: " + path);
                }
            }

            current.append(c);
        }

        if (bracketDepth != 0) {
            throw new IllegalArgumentException("Invalid variable path: " + path);
        }

        if (current.length() > 0) {
            out.add(current.toString());
        }

        return out;
    }

    private static String extractRootName(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.' || c == '[') {
                break;
            }
            sb.append(c);
        }

        String root = sb.toString();
        return root.isBlank() ? null : root;
    }

    private static String extractSegmentName(String segment) {
        if (segment == null || segment.isBlank()) {
            return null;
        }
        if (segment.charAt(0) == '[') {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '[') {
                break;
            }
            sb.append(c);
        }

        return sb.toString().trim();
    }

    static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[] bytes) {
            return HexUtil.toHex(bytes);
        }
        return String.valueOf(value);
    }

    static byte[] toBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof ProxyResponse response) {
            return response.payload();
        }
        if (value instanceof SniffedFrame frame) {
            return frame.payload();
        }
        if (value instanceof String s) {
            try {
                return HexUtil.hexToBytes(s);
            } catch (IOException e) {
                throw new IllegalArgumentException("String is not valid hex: " + s, e);
            }
        }
        throw new IllegalArgumentException("Value is not a byte array: " + value);
    }

    static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof String s) {
            if (s.matches("0[xX][0-9A-Fa-f]+")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        }
        throw new IllegalArgumentException("Value is not an int: " + value);
    }

    static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            return Long.parseLong(s);
        }
        throw new IllegalArgumentException("Value is not a long: " + value);
    }
}
