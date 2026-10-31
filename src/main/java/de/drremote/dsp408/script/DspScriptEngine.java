package de.drremote.dsp408.script;

import de.drremote.dsp408.model.ProxyStatus;
import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.session.DspSessionFactory;
import de.drremote.dsp408.proxy.ProxyClient;
import de.drremote.dsp408.proxy.ProxyConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DspScriptEngine {
    private final String defaultStreamHost;
    private final int defaultStreamPort;
    private final String defaultControlHost;
    private final int defaultControlPort;
    private final boolean verbose;
    private final DspSessionFactory sessionFactory;

    public DspScriptEngine(
            String defaultStreamHost,
            int defaultStreamPort,
            String defaultControlHost,
            int defaultControlPort,
            boolean verbose
    ) {
        this(defaultStreamHost, defaultStreamPort, defaultControlHost, defaultControlPort, verbose,
                (streamHost, streamPort, controlHost, controlPort, logging) ->
                        new ProxyClient(ProxyConfig.of(streamHost, streamPort, controlHost, controlPort, null, logging)));
    }

    public DspScriptEngine(String defaultStreamHost, int defaultStreamPort,
                           String defaultControlHost, int defaultControlPort,
                           boolean verbose, DspSessionFactory sessionFactory) {
        this.defaultStreamHost = Objects.requireNonNull(defaultStreamHost);
        this.defaultStreamPort = defaultStreamPort;
        this.defaultControlHost = Objects.requireNonNull(defaultControlHost);
        this.defaultControlPort = defaultControlPort;
        this.verbose = verbose;
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    public void execute(Path scriptPath) throws Exception {
        if (scriptPath == null) {
            throw new IllegalArgumentException("Script file is missing.");
        }
        if (!Files.exists(scriptPath)) {
            throw new IOException("Script file not found: " + scriptPath.toAbsolutePath());
        }

        String text = Files.readString(scriptPath, StandardCharsets.UTF_8);
        executeText(text);
    }

    public void executeText(String scriptText) throws Exception {
        if (scriptText == null || scriptText.isBlank()) {
            throw new IllegalArgumentException("Script is empty.");
        }

        List<String> lines = ScriptTokenizer.splitStatements(scriptText);
        ScriptRuntime state = new ScriptRuntime(
                defaultStreamHost,
                defaultStreamPort,
                defaultControlHost,
                defaultControlPort,
                verbose,
                sessionFactory
        );
        ScriptValueResolver resolver = new ScriptValueResolver(state);
        ScriptFunctions functions = new ScriptFunctions(state, resolver);

        try (state) {
            executeBlock(lines, 0, lines.size(), state, resolver, functions);
        }
    }

    private void executeBlock(List<String> lines,
                              int start,
                              int end,
                              ScriptRuntime state,
                              ScriptValueResolver resolver,
                              ScriptFunctions functions) throws Exception {
        for (int i = start; i < end; i++) {
            String raw = ScriptTokenizer.stripComments(lines.get(i)).trim();
            if (raw.isEmpty()) {
                continue;
            }

            List<String> tokens = ScriptTokenizer.tokenize(raw);
            if (tokens.isEmpty()) {
                continue;
            }

            String first = tokens.get(0).toLowerCase(Locale.ROOT);

            if ("{".equals(first)) {
                continue;
            }

            if ("}".equals(first)) {
                return;
            }

            if ("for".equals(first)) {
                int endIndex = findMatchingEnd(lines, i + 1, end);
                executeForBlock(tokens, lines, i + 1, endIndex, state, resolver, functions, i + 1);
                i = endIndex;
                continue;
            }

            if ("if".equals(first)) {
                IfChain chain = findIfChain(lines, i, end);
                executeIfChain(chain, lines, state, resolver, functions);
                i = chain.endIndex();
                continue;
            }

            if ("else".equals(first) || "end".equals(first)) {
                return;
            }

            try {
                executeStatement(raw, tokens, state, resolver, functions);
            } catch (Exception e) {
                throw new IllegalStateException("Script error line " + (i + 1) + ": " + shortError(e), e);
            }
        }
    }

    private int findMatchingEnd(List<String> lines, int start, int end) {
        int depth = 1;

        for (int i = start; i < end; i++) {
            String raw = ScriptTokenizer.stripComments(lines.get(i)).trim();
            if (raw.isEmpty()) {
                continue;
            }

            List<String> tokens = ScriptTokenizer.tokenize(raw);
            if (tokens.isEmpty()) {
                continue;
            }

            String first = tokens.get(0).toLowerCase(Locale.ROOT);
            if ("{".equals(first)) {
                continue;
            }
            if ("for".equals(first) || "if".equals(first)) {
                depth++;
            } else if ("end".equals(first) || "}".equals(first)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        throw new IllegalStateException("Missing block terminator ('end' or '}').");
    }

    private IfChain findIfChain(List<String> lines, int ifIndex, int end) {
        List<IfBranch> branches = new ArrayList<>();

        String ifRaw = ScriptTokenizer.stripComments(lines.get(ifIndex)).trim();
        String firstCondition = stripTrailingBlockOpen(tailAfterKeyword(ifRaw, "if"));
        if (firstCondition.isBlank()) {
            throw new IllegalArgumentException("Invalid if syntax. Expected: if <condition>");
        }

        int currentBodyStart = ifIndex + 1;
        String currentCondition = firstCondition;
        int depth = 1;
        int elseStart = -1;
        int endIndex = -1;

        for (int i = ifIndex + 1; i < end; i++) {
            String raw = ScriptTokenizer.stripComments(lines.get(i)).trim();
            if (raw.isEmpty()) {
                continue;
            }

            List<String> tokens = ScriptTokenizer.tokenize(raw);
            if (tokens.isEmpty()) {
                continue;
            }

            String first = tokens.get(0).toLowerCase(Locale.ROOT);

            if ("{".equals(first)) {
                continue;
            }

            if ("}".equals(first)) {
                if (depth > 1) {
                    depth--;
                    continue;
                }
                int next = nextNonEmptyLine(lines, i + 1, end);
                if (next >= 0) {
                    List<String> nextTokens = ScriptTokenizer.tokenize(
                            ScriptTokenizer.stripComments(lines.get(next)).trim()
                    );
                    if (!nextTokens.isEmpty() && "else".equalsIgnoreCase(nextTokens.get(0))) {
                        continue;
                    }
                }
                depth--;
                if (depth == 0) {
                    if (currentCondition != null) {
                        branches.add(new IfBranch(currentCondition, currentBodyStart, i));
                    }
                    endIndex = i;
                    break;
                }
                continue;
            }

            if ("if".equals(first) || "for".equals(first)) {
                depth++;
                continue;
            }

            if ("end".equals(first)) {
                depth--;
                if (depth == 0) {
                    if (currentCondition != null) {
                        branches.add(new IfBranch(currentCondition, currentBodyStart, i));
                    }
                    endIndex = i;
                    break;
                }
                continue;
            }

            if (depth != 1) {
                continue;
            }

            if ("else".equals(first)) {
                if (tokens.size() >= 2 && "if".equalsIgnoreCase(tokens.get(1))) {
                    branches.add(new IfBranch(currentCondition, currentBodyStart, i));

                    int ifPos = raw.toLowerCase(Locale.ROOT).indexOf("if");
                    String elseIfCondition = ifPos >= 0 ? raw.substring(ifPos + 2).trim() : "";
                    elseIfCondition = stripTrailingBlockOpen(elseIfCondition);
                    if (elseIfCondition.isBlank()) {
                        throw new IllegalArgumentException("Invalid else-if syntax. Expected: else if <condition>");
                    }

                    currentCondition = elseIfCondition;
                    currentBodyStart = i + 1;
                } else {
                    branches.add(new IfBranch(currentCondition, currentBodyStart, i));
                    elseStart = i + 1;
                    currentCondition = null;
                    currentBodyStart = -1;
                }
            }
        }

        if (endIndex < 0) {
            throw new IllegalStateException("Missing block terminator ('end' or '}') for if block.");
        }

        return new IfChain(List.copyOf(branches), elseStart, endIndex);
    }

    private void executeIfChain(IfChain chain,
                                List<String> lines,
                                ScriptRuntime state,
                                ScriptValueResolver resolver,
                                ScriptFunctions functions) throws Exception {
        for (IfBranch branch : chain.branches()) {
            if (evaluateCondition(branch.condition(), resolver, functions)) {
                executeBlock(lines, branch.startIndex(), branch.endIndex(), state, resolver, functions);
                return;
            }
        }

        if (chain.elseStart() >= 0) {
            executeBlock(lines, chain.elseStart(), chain.endIndex(), state, resolver, functions);
        }
    }

    private void executeForBlock(List<String> tokens,
                                 List<String> lines,
                                 int bodyStart,
                                 int bodyEnd,
                                 ScriptRuntime state,
                                 ScriptValueResolver resolver,
                                 ScriptFunctions functions,
                                 int lineNo) throws Exception {
        ForSpec spec = parseForSpec(tokens, lineNo);
        String variable = spec.variable();
        String[] parts = spec.rangeText().split("\\.\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid range expression in for line " + lineNo);
        }

        int startValue = ScriptValueResolver.toInt(resolver.resolveValue(parts[0]));
        int endValue = ScriptValueResolver.toInt(resolver.resolveValue(parts[1]));

        Object previous = state.variables.get(variable);
        boolean hadPrevious = state.variables.containsKey(variable);

        if (startValue <= endValue) {
            for (int value = startValue; value <= endValue; value++) {
                state.variables.put(variable, value);
                state.variables.put("_", value);
                executeBlock(lines, bodyStart, bodyEnd, state, resolver, functions);
            }
        } else {
            for (int value = startValue; value >= endValue; value--) {
                state.variables.put(variable, value);
                state.variables.put("_", value);
                executeBlock(lines, bodyStart, bodyEnd, state, resolver, functions);
            }
        }

        if (hadPrevious) {
            state.variables.put(variable, previous);
        } else {
            state.variables.remove(variable);
        }
    }

    private void executeStatement(String raw,
                                  List<String> tokens,
                                  ScriptRuntime state,
                                  ScriptValueResolver resolver,
                                  ScriptFunctions functions) throws Exception {
        String command = normalizeCommandName(tokens.get(0));

        if (isFunctionCallStatement(tokens, command)) {
            Object value = functions.evaluateExpression(tokens);
            state.variables.put("_", value);
            return;
        }

        switch (command) {
            case "let" -> executeLet(tokens, state, functions);
            case "connect" -> executeConnect(tokens, state, resolver);
            case "disconnect" -> state.closeClient();
            case "gui-connect" -> executeGuiConnect(tokens, state, resolver);
            case "gui-disconnect" -> state.closeGuiSniffer();
            case "status" -> {
                ProxyStatus s = state.requireClient().status();
                state.variables.put("_", s);
                System.out.println(ScriptFunctions.displayValue(s));
            }
            case "reset-session" -> {
                state.requireClient().resetSession();
                state.variables.put("_", "ok");
            }
            case "attach-session" -> {
                state.requireClient().attachSession();
                state.variables.put("_", "ok");
            }
            case "ensure-session" -> {
                state.requireClient().ensureSession();
                state.variables.put("_", "ok");
            }
            case "clear-frames" -> {
                state.requireClient().clearFrames();
                state.variables.put("_", "ok");
            }
            case "handshake" -> {
                state.requireClient().handshake();
                state.variables.put("_", "ok");
            }
            case "print" -> {
                Object value = functions.evaluateExpression(tokens.subList(1, tokens.size()));
                System.out.println(ScriptFunctions.displayValue(value));
                state.variables.put("_", value);
            }
            case "assert" -> executeAssert(raw, state, resolver, functions);
            case "sleep" -> executeSleep(tokens, state, resolver);
            case "save-text" -> executeSaveText(tokens, state, resolver, functions);
            case "save-capture-read-blocks" -> executeSaveCaptureReadBlocks(tokens, state, resolver, functions);
            case "save-diff-report" -> executeSaveDiffReport(tokens, state, resolver, functions);
            default -> {
                if (ScriptFunctions.isExpressionStarter(command)) {
                    Object value = functions.evaluateExpression(tokens);
                    state.variables.put("_", value);
                } else {
                    throw new IllegalArgumentException("Unknown script command: " + tokens.get(0));
                }
            }
        }
    }

    private void executeLet(List<String> tokens,
                            ScriptRuntime state,
                            ScriptFunctions functions) throws Exception {
        if (tokens.size() < 4 || !"=".equals(tokens.get(2))) {
            throw new IllegalArgumentException("Invalid let syntax. Expected: let <var> = <expr>");
        }

        String variable = tokens.get(1);
        Object value = functions.evaluateExpression(tokens.subList(3, tokens.size()));
        state.variables.put(variable, value);
        state.variables.put("_", value);
    }

    private void executeConnect(List<String> tokens, ScriptRuntime state, ScriptValueResolver resolver) throws Exception {
        if (tokens.size() == 1) {
            state.connect(
                    state.defaultStreamHost,
                    state.defaultStreamPort,
                    state.defaultControlHost,
                    state.defaultControlPort
            );
            state.variables.put("_", "connected");
            return;
        }

        if (tokens.size() == 5) {
            String streamHost = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
            int streamPort = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
            String controlHost = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(3)));
            int controlPort = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(4)));

            state.connect(streamHost, streamPort, controlHost, controlPort);
            state.variables.put("_", "connected");
            return;
        }

        throw new IllegalArgumentException(
                "Invalid connect syntax. Allowed: connect | connect <streamHost> <streamPort> <controlHost> <controlPort>"
        );
    }

    private void executeGuiConnect(List<String> tokens, ScriptRuntime state, ScriptValueResolver resolver) throws Exception {
        if (tokens.size() == 1) {
            state.connectGuiSniffer(state.defaultStreamHost, state.defaultStreamPort);
            state.variables.put("_", "gui-connected");
            return;
        }

        if (tokens.size() == 3) {
            String streamHost = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
            int streamPort = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
            state.connectGuiSniffer(streamHost, streamPort);
            state.variables.put("_", "gui-connected");
            return;
        }

        throw new IllegalArgumentException(
                "Invalid gui-connect syntax. Allowed: gui-connect | gui-connect <streamHost> <streamPort>"
        );
    }

    private void executeAssert(String raw,
                               ScriptRuntime state,
                               ScriptValueResolver resolver,
                               ScriptFunctions functions) throws Exception {
        String conditionText = tailAfterKeyword(raw, "assert");
        if (conditionText.isBlank()) {
            throw new IllegalArgumentException("Invalid assert syntax. Expected: assert <condition>");
        }

        boolean ok = evaluateCondition(conditionText, resolver, functions);
        if (!ok) {
            throw new IllegalStateException("Assertion failed: " + abbreviate(conditionText, 180));
        }

        state.variables.put("_", true);
    }

    private boolean evaluateCondition(String text,
                                      ScriptValueResolver resolver,
                                      ScriptFunctions functions) throws Exception {
        String expr = stripOuterConditionParens(text.trim());
        if (expr.isBlank()) {
            throw new IllegalArgumentException("Empty condition expression.");
        }

        int orIndex = findTopLevelKeyword(expr, "or");
        if (orIndex >= 0) {
            String leftText = expr.substring(0, orIndex).trim();
            String rightText = expr.substring(orIndex + 2).trim();
            requireBinaryConditionOperands(expr, leftText, rightText, "or");
            return evaluateCondition(leftText, resolver, functions)
                    || evaluateCondition(rightText, resolver, functions);
        }

        int andIndex = findTopLevelKeyword(expr, "and");
        if (andIndex >= 0) {
            String leftText = expr.substring(0, andIndex).trim();
            String rightText = expr.substring(andIndex + 3).trim();
            requireBinaryConditionOperands(expr, leftText, rightText, "and");
            return evaluateCondition(leftText, resolver, functions)
                    && evaluateCondition(rightText, resolver, functions);
        }

        if (startsWithTopLevelKeyword(expr, "not")) {
            String rest = expr.substring(3).trim();
            if (rest.isBlank()) {
                throw new IllegalArgumentException("Invalid not condition: " + expr);
            }
            return !evaluateCondition(rest, resolver, functions);
        }

        Comparison comparison = findComparison(expr);
        if (comparison == null) {
            Object value = functions.evaluateExpression(ScriptTokenizer.tokenize(expr));
            return truthy(value);
        }

        Object left = functions.evaluateExpression(ScriptTokenizer.tokenize(comparison.left()));
        Object right = functions.evaluateExpression(ScriptTokenizer.tokenize(comparison.right()));

        return switch (comparison.operator()) {
            case "==" -> ScriptFunctions.valueEquals(left, right);
            case "!=" -> !ScriptFunctions.valueEquals(left, right);
            case ">" -> compareOrdered(left, right) > 0;
            case ">=" -> compareOrdered(left, right) >= 0;
            case "<" -> compareOrdered(left, right) < 0;
            case "<=" -> compareOrdered(left, right) <= 0;
            default -> throw new IllegalArgumentException("Unknown comparison operator: " + comparison.operator());
        };
    }

    private static void requireBinaryConditionOperands(String fullText,
                                                       String leftText,
                                                       String rightText,
                                                       String operator) {
        if (leftText.isBlank() || rightText.isBlank()) {
            throw new IllegalArgumentException("Invalid expression with '" + operator + "': " + fullText);
        }
    }

    private static int compareOrdered(Object left, Object right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Comparison with null is not allowed here.");
        }

        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue());
        }

        if (left instanceof String l && right instanceof String r) {
            return l.compareTo(r);
        }

        if (left instanceof Boolean l && right instanceof Boolean r) {
            return Boolean.compare(l, r);
        }

        throw new IllegalArgumentException("Operator not supported for types: "
                + left.getClass().getSimpleName() + " and " + right.getClass().getSimpleName());
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return Double.compare(n.doubleValue(), 0.0d) != 0;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof byte[] bytes) {
            return bytes.length > 0;
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static Comparison findComparison(String text) {
        String[] operators = {"==", "!=", ">=", "<=", ">", "<"};
        boolean inQuotes = false;
        boolean escaping = false;
        int parenDepth = 0;
        int bracketDepth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (inQuotes && c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (inQuotes) {
                continue;
            }

            if (c == '(') {
                parenDepth++;
                continue;
            }
            if (c == ')') {
                parenDepth--;
                continue;
            }
            if (c == '[') {
                bracketDepth++;
                continue;
            }
            if (c == ']') {
                bracketDepth--;
                continue;
            }

            if (parenDepth != 0 || bracketDepth != 0) {
                continue;
            }

            for (String operator : operators) {
                if (text.startsWith(operator, i)) {
                    String left = text.substring(0, i).trim();
                    String right = text.substring(i + operator.length()).trim();
                    if (left.isEmpty() || right.isEmpty()) {
                        throw new IllegalArgumentException("Invalid comparison expression: " + text);
                    }
                    return new Comparison(left, operator, right);
                }
            }
        }

        return null;
    }

    private static int findTopLevelKeyword(String text, String keyword) {
        boolean inQuotes = false;
        boolean escaping = false;
        int parenDepth = 0;
        int bracketDepth = 0;

        for (int i = 0; i <= text.length() - keyword.length(); i++) {
            char c = text.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (inQuotes && c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (inQuotes) {
                continue;
            }

            if (c == '(') {
                parenDepth++;
                continue;
            }
            if (c == ')') {
                parenDepth--;
                continue;
            }
            if (c == '[') {
                bracketDepth++;
                continue;
            }
            if (c == ']') {
                bracketDepth--;
                continue;
            }

            if (parenDepth != 0 || bracketDepth != 0) {
                continue;
            }

            if (matchesKeywordAt(text, i, keyword)) {
                return i;
            }
        }

        return -1;
    }

    private static boolean startsWithTopLevelKeyword(String text, String keyword) {
        return findTopLevelKeyword(text, keyword) == 0;
    }

    private static boolean matchesKeywordAt(String text, int index, String keyword) {
        if (!text.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }

        int before = index - 1;
        int after = index + keyword.length();

        boolean leftBoundary = before < 0 || !isIdentifierChar(text.charAt(before));
        boolean rightBoundary = after >= text.length() || !isIdentifierChar(text.charAt(after));

        return leftBoundary && rightBoundary;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c)
                || c == '_'
                || c == '-'
                || c == '.'
                || c == '$';
    }

    private static String stripOuterConditionParens(String text) {
        String current = text;
        while (hasOuterWrappingParens(current)) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static boolean hasOuterWrappingParens(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        if (text.charAt(0) != '(' || text.charAt(text.length() - 1) != ')') {
            return false;
        }

        boolean inQuotes = false;
        boolean escaping = false;
        int parenDepth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (inQuotes && c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (inQuotes) {
                continue;
            }

            if (c == '(') {
                parenDepth++;
                continue;
            }

            if (c == ')') {
                parenDepth--;
                if (parenDepth == 0 && i < text.length() - 1) {
                    return false;
                }
                if (parenDepth < 0) {
                    return false;
                }
            }
        }

        return parenDepth == 0;
    }

    private void executeSleep(List<String> tokens, ScriptRuntime state, ScriptValueResolver resolver) throws Exception {
        if (tokens.size() != 2) {
            throw new IllegalArgumentException("Invalid sleep syntax. Expected: sleep <ms>");
        }

        long ms = ScriptValueResolver.toLong(resolver.resolveValue(tokens.get(1)));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sleep interrupted.", e);
        }

        state.variables.put("_", ms);
    }

    private void executeSaveText(List<String> tokens,
                                 ScriptRuntime state,
                                 ScriptValueResolver resolver,
                                 ScriptFunctions functions) throws Exception {
        if (tokens.size() < 3) {
            throw new IllegalArgumentException("Invalid save-text syntax. Expected: save-text <path> <expr>");
        }

        String pathText = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
        Path path = Path.of(pathText);

        Object value = functions.evaluateExpression(tokens.subList(2, tokens.size()));
        String text = ScriptValueResolver.stringify(value);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(path, text, StandardCharsets.UTF_8);
        state.variables.put("_", path.toAbsolutePath().toString());
    }

    private void executeSaveCaptureReadBlocks(List<String> tokens,
                                              ScriptRuntime state,
                                              ScriptValueResolver resolver,
                                              ScriptFunctions functions) throws Exception {
        if (tokens.size() < 3) {
            throw new IllegalArgumentException(
                    "Invalid save-capture-read-blocks syntax. Expected: save-capture-read-blocks <capture> <dir>"
            );
        }

        Object captureValue = functions.evaluateExpression(tokens.subList(1, 2));
        if (!(captureValue instanceof GuiCaptureResult capture)) {
            throw new IllegalArgumentException("First argument is not a GuiCaptureResult.");
        }

        Path dir = Path.of(resolver.interpolate(tokens.get(2)));
        Files.createDirectories(dir.toAbsolutePath());

        int saved = 0;
        for (SniffedFrame frame : capture.readBlockResponses()) {
            int blockIndex = extractReadBlockIndex(frame);
            Path hexPath = dir.resolve(String.format("block-%02X.hex.txt", blockIndex));
            Path asciiPath = dir.resolve(String.format("block-%02X.ascii.txt", blockIndex));
            Files.writeString(hexPath, frame.payloadHex(), StandardCharsets.UTF_8);
            Files.writeString(asciiPath, frame.payloadAscii(), StandardCharsets.UTF_8);
            saved++;
        }

        state.variables.put("_", saved);
    }

    private void executeSaveDiffReport(List<String> tokens,
                                       ScriptRuntime state,
                                       ScriptValueResolver resolver,
                                       ScriptFunctions functions) throws Exception {
        if (tokens.size() < 4) {
            throw new IllegalArgumentException(
                    "Invalid save-diff-report syntax. Expected: save-diff-report <path> <before> <after>"
            );
        }

        Object result = functions.evaluateExpression(tokens);
        state.variables.put("_", result);
    }

    private static int extractReadBlockIndex(SniffedFrame frame) {
        if (frame == null || frame.command() == null || frame.command() != 0x24) {
            throw new IllegalArgumentException("Frame is not a read_block_response.");
        }
        try {
            byte[] payload = de.drremote.dsp408.util.HexUtil.hexToBytes(frame.payloadHex());
            if (payload.length <= 4) {
                throw new IllegalArgumentException("read_block_response without block index.");
            }
            return payload[4] & 0xFF;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid payload hex in capture.", e);
        }
    }

    private static int nextNonEmptyLine(List<String> lines, int start, int end) {
        for (int i = start; i < end; i++) {
            String raw = ScriptTokenizer.stripComments(lines.get(i)).trim();
            if (!raw.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static String stripTrailingBlockOpen(String text) {
        String trimmed = text.trim();
        if (trimmed.endsWith("{")) {
            return trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean isFunctionCallStatement(List<String> tokens, String normalizedName) {
        if (tokens.size() >= 3
                && "(".equals(tokens.get(1))
                && ScriptFunctions.isExpressionStarter(normalizedName)) {
            int close = ScriptFunctions.findMatchingParenIndex(tokens, 1);
            return close == tokens.size() - 1;
        }
        return false;
    }

    private static String normalizeCommandName(String raw) {
        return ScriptFunctions.normalizeFunctionName(raw);
    }

    private static ForSpec parseForSpec(List<String> tokens, int lineNo) {
        List<String> cleaned = new ArrayList<>();
        for (String token : tokens) {
            if (!"(".equals(token) && !")".equals(token)) {
                cleaned.add(token);
            }
        }

        if (cleaned.size() >= 2 && "for".equalsIgnoreCase(cleaned.get(0)) && "let".equalsIgnoreCase(cleaned.get(1))) {
            cleaned.remove(1);
        }

        if (cleaned.size() != 4 || !"for".equalsIgnoreCase(cleaned.get(0)) || !"in".equalsIgnoreCase(cleaned.get(2))) {
            throw new IllegalArgumentException("Invalid for syntax. Expected: for <var> in <start>..<end>");
        }

        return new ForSpec(cleaned.get(1), cleaned.get(3));
    }

    private record ForSpec(String variable, String rangeText) {
    }

    private static String tailAfterKeyword(String raw, String keyword) {
        if (raw.length() <= keyword.length()) {
            return "";
        }
        return raw.substring(keyword.length()).trim();
    }

    private static String shortError(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cur.getClass().getSimpleName();
        }
        return abbreviate(msg, 180);
    }

    static String abbreviate(String text, int maxChars) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + " ...";
    }

    private record Comparison(String left, String operator, String right) {
    }

    private record IfBranch(String condition, int startIndex, int endIndex) {
    }

    private record IfChain(List<IfBranch> branches, int elseStart, int endIndex) {
    }
}
