package de.drremote.dsp408.script;

import de.drremote.dsp408.dump.ByteDiff;
import de.drremote.dsp408.fir.Fir408Memory;
import de.drremote.dsp408.fir.Fir408Diagnostics;
import de.drremote.dsp408.fir.Fir408TestFilter;
import de.drremote.dsp408.dump.DumpByteReaders;
import de.drremote.dsp408.dump.DumpDiffUtil;
import de.drremote.dsp408.model.GuiCaptureResult;
import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.model.ReadBlockSet;
import de.drremote.dsp408.model.SniffedFrame;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.util.DspProtocol;
import de.drremote.dsp408.util.HexUtil;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class ScriptFunctions {
    private final ScriptRuntime state;
    private final ScriptValueResolver resolver;

    ScriptFunctions(ScriptRuntime state, ScriptValueResolver resolver) {
        this.state = Objects.requireNonNull(state);
        this.resolver = Objects.requireNonNull(resolver);
    }

    Object evaluateExpression(List<String> tokens) throws Exception {
        List<String> normalized = normalizeTokens(tokens);
        if (normalized.isEmpty()) {
            return "";
        }

        if (isWrappedExpression(normalized)) {
            return evaluateExpression(normalized.subList(1, normalized.size() - 1));
        }

        if (isFunctionCallExpression(normalized)) {
            return evaluateCallExpression(normalized);
        }

        return evaluateLegacyExpression(normalized);
    }

    private Object evaluateLegacyExpression(List<String> tokens) throws Exception {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }

        String first = normalizeFunctionName(tokens.get(0));

        return switch (first) {
            case "status" -> state.requireClient().status();
            case "handshake-init" -> state.requireClient().handshakeInit();
            case "device-info" -> state.requireClient().deviceInfo();
            case "system-info" -> state.requireClient().systemInfo();
            case "login" -> {
                requireArgs(tokens, 2, "login <pin>");
                yield state.requireClient().login(
                        ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)))
                );
            }
            case "read-block" -> {
                requireArgs(tokens, 2, "read-block <block>");
                int block = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(1)));
                yield state.requireClient().readBlock(block);
            }
            case "read-blocks" -> {
                if (tokens.size() != 3) {
                    throw new IllegalArgumentException("Invalid read-blocks syntax. Expected: read-blocks <start> <end>");
                }
                int start = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(1)));
                int end = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield readBlocks(start, end);
            }
            case "read-config-blocks" -> {
                if (tokens.size() > 3) {
                    throw new IllegalArgumentException("Invalid read-config-blocks syntax. Expected: read-config-blocks [end] or read-config-blocks <start> <end>");
                }
                int start = 0x00;
                int end = 0x1F;
                if (tokens.size() == 2) {
                    end = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(1)));
                } else if (tokens.size() == 3) {
                    start = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(1)));
                    end = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                }
                yield readBlocks(start, end);
            }
            case "read-save-config" -> {
                if (tokens.size() < 2 || tokens.size() > 4) {
                    throw new IllegalArgumentException("Invalid read-save-config syntax. Expected: read-save-config <dir> [end] or read-save-config <dir> <start> <end>");
                }
                String dir = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
                int start = 0x00;
                int end = 0x1F;
                if (tokens.size() == 3) {
                    end = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                } else if (tokens.size() == 4) {
                    start = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                    end = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(3)));
                }
                ReadBlockSet blocks = readBlocks(start, end);
                saveReadBlocks(blocks, dir);
                yield blocks;
            }
            case "read-block-index" -> {
                requireArgs(tokens, 2, "read-block-index <value>");
                yield readBlockIndexOf(evaluateSingle(tokens.get(1)));
            }
            case "read-block-payload" -> {
                requireArgs(tokens, 3, "read-block-payload <capture> <block>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int block = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                SniffedFrame frame = capture.readBlockResponse(block);
                yield frame == null ? null : frame.payload();
            }
            case "cmd" -> {
                requireArgs(tokens, 2, "cmd <value>");
                yield commandOf(evaluateSingle(tokens.get(1)));
            }
            case "payload" -> {
                requireArgs(tokens, 2, "payload <value>");
                yield payloadOf(evaluateSingle(tokens.get(1)));
            }
            case "raw" -> {
                requireArgs(tokens, 2, "raw <value>");
                yield rawOf(evaluateSingle(tokens.get(1)));
            }
            case "payload-hex" -> {
                requireArgs(tokens, 2, "payload-hex <value>");
                yield HexUtil.toHex(payloadOf(evaluateSingle(tokens.get(1))));
            }
            case "payload-ascii" -> {
                requireArgs(tokens, 2, "payload-ascii <value>");
                yield HexUtil.payloadAscii(payloadOf(evaluateSingle(tokens.get(1))));
            }
            case "diff-bytes" -> {
                requireArgs(tokens, 3, "diff-bytes <before> <after>");
                byte[] before = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                byte[] after = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(2)));
                yield DumpDiffUtil.formatByteDiffs(before, after);
            }
            case "diff-u16le" -> {
                requireArgs(tokens, 3, "diff-u16le <before> <after>");
                byte[] before = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                byte[] after = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(2)));
                yield DumpDiffUtil.formatU16Diffs(before, after);
            }
            case "diff-report" -> {
                requireArgs(tokens, 3, "diff-report <before> <after>");
                byte[] before = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                byte[] after = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(2)));
                yield DumpDiffUtil.formatDiffReport(before, after);
            }
            case "changed-offsets" -> {
                requireArgs(tokens, 3, "changed-offsets <before> <after>");
                byte[] before = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                byte[] after = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(2)));
                yield changedOffsets(before, after);
            }
            case "save-diff-report" -> {
                requireArgs(tokens, 4, "save-diff-report <path> <before> <after>");
                String pathText = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
                byte[] before = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(2)));
                byte[] after = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(3)));
                yield saveDiffReport(pathText, before, after);
            }
            case "save-read-blocks" -> {
                requireArgs(tokens, 3, "save-read-blocks <blocks> <dir>");
                Object blocks = evaluateSingle(tokens.get(1));
                String dir = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(2)));
                yield saveReadBlocks(blocks, dir);
            }
            case "assembled-data" -> {
                requireArgs(tokens, 2, "assembled-data <blocks>");
                yield assembledData(evaluateSingle(tokens.get(1)));
            }
            case "decode-fir408-config" -> {
                requireArgs(tokens, 2, "decode-fir408-config <blocks>");
                yield decodeFir408Config(evaluateSingle(tokens.get(1)));
            }
            case "fir408-safe-pings" -> {
                requireArgs(tokens, 2, "fir408-safe-pings <blocks>");
                yield fir408SafePings(evaluateSingle(tokens.get(1)));
            }
            case "fir408-cmd56-readonly-sweep" -> {
                if (tokens.size() != 2 && tokens.size() != 7) {
                    throw new IllegalArgumentException(
                            "Invalid fir408-cmd56-readonly-sweep syntax. Expected: "
                                    + "fir408-cmd56-readonly-sweep <dir> [selectorStart selectorEnd offsetStart offsetEnd step]"
                    );
                }
                String dir = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
                int selectorStart = tokens.size() == 7 ? ScriptValueResolver.toInt(evaluateSingle(tokens.get(2))) : 0x00;
                int selectorEnd = tokens.size() == 7 ? ScriptValueResolver.toInt(evaluateSingle(tokens.get(3))) : 0x03;
                int offsetStart = tokens.size() == 7 ? ScriptValueResolver.toInt(evaluateSingle(tokens.get(4))) : 0;
                int offsetEnd = tokens.size() == 7 ? ScriptValueResolver.toInt(evaluateSingle(tokens.get(5))) : 511;
                int step = tokens.size() == 7 ? ScriptValueResolver.toInt(evaluateSingle(tokens.get(6))) : 13;
                yield fir408Cmd56ReadonlySweep(dir, selectorStart, selectorEnd, offsetStart, offsetEnd, step);
            }
            case "fir408-cmd56-readonly-offsets" -> {
                if (tokens.size() != 4 && tokens.size() != 5) {
                    throw new IllegalArgumentException(
                            "Invalid fir408-cmd56-readonly-offsets syntax. Expected: "
                                    + "fir408-cmd56-readonly-offsets <dir> <selector> <offsets> [attempts]"
                    );
                }
                String dir = ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(1)));
                int selector = ScriptValueResolver.toInt(evaluateSingle(tokens.get(2)));
                String offsets = ScriptValueResolver.stringify(evaluateSingle(tokens.get(3)));
                int attempts = tokens.size() == 5 ? ScriptValueResolver.toInt(evaluateSingle(tokens.get(4))) : 1;
                yield fir408Cmd56ReadonlyOffsets(dir, selector, offsets, attempts);
            }
            case "fir408-upload-test-fir" -> {
                if (tokens.size() < 3 || tokens.size() > 4) {
                    throw new IllegalArgumentException(
                            "Invalid fir408-upload-test-fir syntax. Expected: fir408-upload-test-fir <selector> <pattern> [name8]"
                    );
                }
                int selector = ScriptValueResolver.toInt(evaluateSingle(tokens.get(1)));
                String pattern = ScriptValueResolver.stringify(evaluateSingle(tokens.get(2)));
                String name8 = tokens.size() >= 4
                        ? ScriptValueResolver.stringify(evaluateSingle(tokens.get(3)))
                        : Fir408TestFilter.defaultName(pattern);
                yield fir408UploadTestFir(selector, pattern, name8);
            }
            case "pause" -> {
                String note = tokens.size() >= 2
                        ? ScriptValueResolver.stringify(evaluateExpression(tokens.subList(1, tokens.size())))
                        : null;
                state.pauseForUser(note);
                yield "continued";
            }
            case "reconnect-session" -> {
                long timeoutMs = tokens.size() >= 2
                        ? ScriptValueResolver.toLong(evaluateSingle(tokens.get(1)))
                        : 120000L;
                long pollMs = tokens.size() >= 3
                        ? ScriptValueResolver.toLong(evaluateSingle(tokens.get(2)))
                        : 250L;
                state.reconnectSession(timeoutMs, pollMs);
                yield "reconnected";
            }
            case "send-payload", "tx", "write" -> evaluateSendPayload(tokens, state.requireClient());

            case "gui-capture" -> evaluateGuiCapture(tokens);
            case "gui-action-capture" -> evaluateGuiActionCapture(tokens);
            case "gui-begin-capture" -> {
                state.beginGuiCapture();
                yield "capture-begun";
            }
            case "gui-end-capture" -> {
                if (tokens.size() < 1 || tokens.size() > 3) {
                    throw new IllegalArgumentException(
                            "Invalid gui-end-capture syntax. Expected: gui-end-capture [quietMs] [maxWaitMs]"
                    );
                }
                long quietMs = tokens.size() >= 2
                        ? ScriptValueResolver.toLong(resolver.resolveValue(tokens.get(1)))
                        : 350L;
                long maxWaitMs = tokens.size() >= 3
                        ? ScriptValueResolver.toLong(resolver.resolveValue(tokens.get(2)))
                        : 3000L;
                yield state.finishGuiCapture(quietMs, maxWaitMs);
            }

            case "capture-count" -> {
                requireArgs(tokens, 2, "capture-count <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).totalFrames();
            }
            case "capture-write-count" -> {
                requireArgs(tokens, 2, "capture-write-count <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).writeCount();
            }
            case "capture-response-count" -> {
                requireArgs(tokens, 2, "capture-response-count <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).responseCount();
            }
            case "capture-frame" -> {
                requireArgs(tokens, 3, "capture-frame <capture> <index>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int index = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield capture.frame(index);
            }
            case "first-write" -> {
                requireArgs(tokens, 2, "first-write <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).firstWrite();
            }
            case "last-write" -> {
                requireArgs(tokens, 2, "last-write <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).lastWrite();
            }
            case "first-response" -> {
                requireArgs(tokens, 2, "first-response <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).firstResponse();
            }
            case "last-response" -> {
                requireArgs(tokens, 2, "last-response <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).lastResponse();
            }
            case "last-write-excluding" -> {
                requireArgs(tokens, 2, "last-write-excluding <capture> [cmd...]");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                yield capture.lastWriteExcluding(parseCommands(tokens, 2));
            }
            case "writes-by-command" -> {
                requireArgs(tokens, 3, "writes-by-command <capture> <cmd>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int command = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield writesByCommand(capture, command);
            }
            case "frames-by-command" -> {
                requireArgs(tokens, 3, "frames-by-command <capture> <cmd>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int command = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield framesByCommand(capture, command);
            }
            case "responses-by-command" -> {
                requireArgs(tokens, 3, "responses-by-command <capture> <cmd>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int command = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield responsesByCommand(capture, command);
            }
            case "writes-by-command-and-channel" -> {
                requireArgs(tokens, 4, "writes-by-command-and-channel <capture> <cmd> <channel>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int command = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                int channel = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(3)));
                yield writesByCommandAndChannel(capture, command, channel);
            }
            case "recent-writes" -> {
                requireArgs(tokens, 3, "recent-writes <capture> <limit>");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int limit = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield capture.recentWrites(limit);
            }
            case "recent-writes-excluding" -> {
                requireArgs(tokens, 3, "recent-writes-excluding <capture> <limit> [cmd...]");
                GuiCaptureResult capture = requireCapture(resolver.resolveValue(tokens.get(1)));
                int limit = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield capture.recentWritesExcluding(limit, parseCommands(tokens, 3));
            }
            case "writes" -> {
                requireArgs(tokens, 2, "writes <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).writes();
            }
            case "responses" -> {
                requireArgs(tokens, 2, "responses <capture>");
                yield requireCapture(resolver.resolveValue(tokens.get(1))).responses();
            }
            case "payload-series" -> {
                requireArgs(tokens, 2, "payload-series <frames>");
                yield payloadSeries(evaluateSingle(tokens.get(1)));
            }
            case "u16-series" -> {
                requireArgs(tokens, 3, "u16-series <frames> <offset>");
                Object frames = evaluateSingle(tokens.get(1));
                int offset = ScriptValueResolver.toInt(evaluateSingle(tokens.get(2)));
                yield u16Series(frames, offset);
            }
            case "changing-offsets-across-writes" -> {
                requireArgs(tokens, 2, "changing-offsets-across-writes <frames>");
                yield changingOffsetsAcrossWrites(evaluateSingle(tokens.get(1)));
            }

            case "len" -> {
                requireArgs(tokens, 2, "len <value>");
                Object value = evaluateExpression(tokens.subList(1, tokens.size()));
                yield lengthOf(value);
            }

            case "contains" -> {
                requireArgs(tokens, 3, "contains <value> <part>");
                Object value = evaluateSingle(tokens.get(1));
                Object part = evaluateSingle(tokens.get(2));
                yield contains(value, part);
            }
            case "starts-with" -> {
                requireArgs(tokens, 3, "starts-with <value> <prefix>");
                Object value = evaluateSingle(tokens.get(1));
                Object prefix = evaluateSingle(tokens.get(2));
                yield startsWith(value, prefix);
            }
            case "ends-with" -> {
                requireArgs(tokens, 3, "ends-with <value> <suffix>");
                Object value = evaluateSingle(tokens.get(1));
                Object suffix = evaluateSingle(tokens.get(2));
                yield endsWith(value, suffix);
            }
            case "upper" -> {
                requireArgs(tokens, 2, "upper <text>");
                Object value = evaluateSingle(tokens.get(1));
                yield ScriptValueResolver.stringify(value).toUpperCase(Locale.ROOT);
            }
            case "lower" -> {
                requireArgs(tokens, 2, "lower <text>");
                Object value = evaluateSingle(tokens.get(1));
                yield ScriptValueResolver.stringify(value).toLowerCase(Locale.ROOT);
            }
            case "trim" -> {
                requireArgs(tokens, 2, "trim <text>");
                Object value = evaluateSingle(tokens.get(1));
                yield ScriptValueResolver.stringify(value).trim();
            }
            case "join" -> {
                requireArgs(tokens, 2, "join <list> [separator]");
                Object value = evaluateSingle(tokens.get(1));
                String separator = tokens.size() >= 3
                        ? ScriptValueResolver.stringify(evaluateSingle(tokens.get(2)))
                        : ", ";
                yield join(value, separator);
            }
            case "at" -> {
                requireArgs(tokens, 3, "at <value> <index>");
                Object value = evaluateSingle(tokens.get(1));
                int index = ScriptValueResolver.toInt(evaluateSingle(tokens.get(2)));
                yield at(value, index);
            }
            case "split" -> {
                requireArgs(tokens, 3, "split <text> <separator>");
                String text = ScriptValueResolver.stringify(evaluateSingle(tokens.get(1)));
                String separator = ScriptValueResolver.stringify(evaluateSingle(tokens.get(2)));
                yield split(text, separator);
            }
            case "replace" -> {
                requireArgs(tokens, 4, "replace <text> <search> <replacement>");
                String text = ScriptValueResolver.stringify(evaluateSingle(tokens.get(1)));
                String search = ScriptValueResolver.stringify(evaluateSingle(tokens.get(2)));
                String replacement = ScriptValueResolver.stringify(evaluateSingle(tokens.get(3)));
                yield text.replace(search, replacement);
            }

            case "u8" -> {
                requireArgs(tokens, 3, "u8 <bytesExpr> <offset>");
                byte[] bytes = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                int offset = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield DumpByteReaders.u8(bytes, offset);
            }
            case "u16le" -> {
                requireArgs(tokens, 3, "u16le <bytesExpr> <offset>");
                byte[] bytes = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                int offset = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield DumpByteReaders.u16le(bytes, offset);
            }
            case "u32le" -> {
                requireArgs(tokens, 3, "u32le <bytesExpr> <offset>");
                byte[] bytes = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                int offset = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                yield DumpByteReaders.u32le(bytes, offset);
            }
            case "ascii" -> {
                requireArgs(tokens, 4, "ascii <bytesExpr> <offset> <len> [trimzero]");
                byte[] bytes = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                int offset = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                int length = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(3)));
                boolean trimZero = tokens.size() >= 5 && "trimzero".equalsIgnoreCase(tokens.get(4));
                yield DumpByteReaders.ascii(bytes, offset, length, trimZero);
            }
            case "hex" -> evaluateHex(tokens);
            case "slice" -> {
                requireArgs(tokens, 4, "slice <bytesExpr> <offset> <len>");
                byte[] bytes = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));
                int offset = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
                int length = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(3)));
                yield DumpByteReaders.slice(bytes, offset, length);
            }
            case "bytes" -> {
                requireArgs(tokens, 2, "bytes <hex>");
                yield HexUtil.hexToBytes(resolver.joinResolvedTokens(tokens.subList(1, tokens.size())));
            }

            default -> {
                if (tokens.size() == 1) {
                    yield resolver.resolveValue(tokens.get(0));
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tokens.size(); i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(ScriptValueResolver.stringify(resolver.resolveValue(tokens.get(i))));
                }
                yield sb.toString();
            }
        };
    }

    private Object evaluateCallExpression(List<String> tokens) throws Exception {
        String name = normalizeFunctionName(tokens.get(0));
        int close = findMatchingParenIndex(tokens, 1);
        List<ScriptArgument> args = splitArguments(tokens, 2, close).stream()
                .map(argument -> (ScriptArgument) () -> evaluateExpression(argument)).toList();
        return invoke(name, args);
    }

    Object invoke(String name, List<ScriptArgument> args) throws Exception {
        name = normalizeFunctionName(name);
        return switch (name) {
            case "status" -> {
                requireCallArgCount(name, args, 0);
                yield state.requireClient().status();
            }
            case "connect" -> {
                if (args.isEmpty()) {
                    state.connect(state.defaultStreamHost, state.defaultStreamPort, state.defaultControlHost, state.defaultControlPort);
                    yield "connected";
                }
                if (args.size() == 4) {
                    String streamHost = ScriptValueResolver.stringify(args.get(0).evaluate());
                    int streamPort = ScriptValueResolver.toInt(args.get(1).evaluate());
                    String controlHost = ScriptValueResolver.stringify(args.get(2).evaluate());
                    int controlPort = ScriptValueResolver.toInt(args.get(3).evaluate());
                    state.connect(streamHost, streamPort, controlHost, controlPort);
                    yield "connected";
                }
                throw new IllegalArgumentException(
                        "Invalid connect syntax. Expected: connect() or connect(streamHost, streamPort, controlHost, controlPort)"
                );
            }
            case "disconnect" -> {
                requireCallArgCount(name, args, 0);
                state.closeClient();
                yield "ok";
            }
            case "gui-connect" -> {
                if (args.isEmpty()) {
                    state.connectGuiSniffer(state.defaultStreamHost, state.defaultStreamPort);
                    yield "gui-connected";
                }
                if (args.size() == 2) {
                    String streamHost = ScriptValueResolver.stringify(args.get(0).evaluate());
                    int streamPort = ScriptValueResolver.toInt(args.get(1).evaluate());
                    state.connectGuiSniffer(streamHost, streamPort);
                    yield "gui-connected";
                }
                throw new IllegalArgumentException(
                        "Invalid gui-connect syntax. Expected: guiConnect() or guiConnect(streamHost, streamPort)"
                );
            }
            case "gui-disconnect" -> {
                requireCallArgCount(name, args, 0);
                state.closeGuiSniffer();
                yield "ok";
            }
            case "reset-session" -> {
                requireCallArgCount(name, args, 0);
                state.requireClient().resetSession();
                yield "ok";
            }
            case "ensure-session" -> {
                requireCallArgCount(name, args, 0);
                state.requireClient().ensureSession();
                yield "ok";
            }
            case "clear-frames" -> {
                requireCallArgCount(name, args, 0);
                state.requireClient().clearFrames();
                yield "ok";
            }
            case "handshake" -> {
                requireCallArgCount(name, args, 0);
                state.requireClient().handshake();
                yield "ok";
            }
            case "sleep" -> {
                requireCallArgCount(name, args, 1);
                long ms = ScriptValueResolver.toLong(args.get(0).evaluate());
                Thread.sleep(ms);
                yield ms;
            }
            case "pause" -> {
                if (args.size() > 1) {
                    throw new IllegalArgumentException("pause() expects 0 or 1 argument.");
                }
                String note = args.size() == 1
                        ? ScriptValueResolver.stringify(args.get(0).evaluate())
                        : null;
                state.pauseForUser(note);
                yield "continued";
            }
            case "save-text" -> {
                requireCallArgCount(name, args, 2);
                String pathText = ScriptValueResolver.stringify(args.get(0).evaluate());
                Path path = Path.of(pathText);
                Object value = args.get(1).evaluate();
                String text = ScriptValueResolver.stringify(value);
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, text, StandardCharsets.UTF_8);
                yield path.toAbsolutePath().toString();
            }
            case "save-capture-read-blocks" -> {
                requireCallArgCount(name, args, 2);
                Object captureValue = args.get(0).evaluate();
                if (!(captureValue instanceof GuiCaptureResult capture)) {
                    throw new IllegalArgumentException("First argument is not a GuiCaptureResult.");
                }
                Path dir = Path.of(ScriptValueResolver.stringify(args.get(1).evaluate()));
                Files.createDirectories(dir.toAbsolutePath());
                int saved = 0;
                for (SniffedFrame frame : capture.readBlockResponses()) {
                    Integer blockIndex = frame.readBlockIndex();
                    if (blockIndex == null) {
                        continue;
                    }
                    Path hexPath = dir.resolve(String.format("block-%02X.hex.txt", blockIndex));
                    Path asciiPath = dir.resolve(String.format("block-%02X.ascii.txt", blockIndex));
                    Files.writeString(hexPath, frame.payloadHex(), StandardCharsets.UTF_8);
                    Files.writeString(asciiPath, frame.payloadAscii(), StandardCharsets.UTF_8);
                    saved++;
                }
                yield saved;
            }
            case "save-diff-report" -> {
                requireCallArgCount(name, args, 3);
                String pathText = ScriptValueResolver.stringify(args.get(0).evaluate());
                byte[] before = ScriptValueResolver.toBytes(args.get(1).evaluate());
                byte[] after = ScriptValueResolver.toBytes(args.get(2).evaluate());
                yield saveDiffReport(pathText, before, after);
            }
            case "save-read-blocks" -> {
                requireCallArgCount(name, args, 2);
                Object blocks = args.get(0).evaluate();
                String dir = ScriptValueResolver.stringify(args.get(1).evaluate());
                yield saveReadBlocks(blocks, dir);
            }
            case "assembled-data" -> {
                requireCallArgCount(name, args, 1);
                yield assembledData(args.get(0).evaluate());
            }
            case "decode-fir408-config" -> {
                requireCallArgCount(name, args, 1);
                yield decodeFir408Config(args.get(0).evaluate());
            }
            case "fir408-safe-pings" -> {
                requireCallArgCount(name, args, 1);
                yield fir408SafePings(args.get(0).evaluate());
            }
            case "fir408-cmd56-readonly-sweep" -> {
                if (args.size() != 1 && args.size() != 6) {
                    throw new IllegalArgumentException(
                            "Invalid fir408Cmd56ReadonlySweep syntax. Expected: "
                                    + "fir408Cmd56ReadonlySweep(dir) or "
                                    + "fir408Cmd56ReadonlySweep(dir, selectorStart, selectorEnd, offsetStart, offsetEnd, step)"
                    );
                }
                String dir = ScriptValueResolver.stringify(args.get(0).evaluate());
                int selectorStart = args.size() == 6 ? ScriptValueResolver.toInt(args.get(1).evaluate()) : 0x00;
                int selectorEnd = args.size() == 6 ? ScriptValueResolver.toInt(args.get(2).evaluate()) : 0x03;
                int offsetStart = args.size() == 6 ? ScriptValueResolver.toInt(args.get(3).evaluate()) : 0;
                int offsetEnd = args.size() == 6 ? ScriptValueResolver.toInt(args.get(4).evaluate()) : 511;
                int step = args.size() == 6 ? ScriptValueResolver.toInt(args.get(5).evaluate()) : 13;
                yield fir408Cmd56ReadonlySweep(dir, selectorStart, selectorEnd, offsetStart, offsetEnd, step);
            }
            case "fir408-cmd56-readonly-offsets" -> {
                if (args.size() != 3 && args.size() != 4) {
                    throw new IllegalArgumentException(
                            "Invalid fir408Cmd56ReadonlyOffsets syntax. Expected: "
                                    + "fir408Cmd56ReadonlyOffsets(dir, selector, offsets, [attempts])"
                    );
                }
                String dir = ScriptValueResolver.stringify(args.get(0).evaluate());
                int selector = ScriptValueResolver.toInt(args.get(1).evaluate());
                String offsets = ScriptValueResolver.stringify(args.get(2).evaluate());
                int attempts = args.size() == 4 ? ScriptValueResolver.toInt(args.get(3).evaluate()) : 1;
                yield fir408Cmd56ReadonlyOffsets(dir, selector, offsets, attempts);
            }
            case "fir408-upload-test-fir" -> {
                if (args.size() < 2 || args.size() > 3) {
                    throw new IllegalArgumentException(
                            "Invalid fir408UploadTestFir syntax. Expected: fir408UploadTestFir(selector, pattern, [name8])"
                    );
                }
                int selector = ScriptValueResolver.toInt(args.get(0).evaluate());
                String pattern = ScriptValueResolver.stringify(args.get(1).evaluate());
                String name8 = args.size() >= 3
                        ? ScriptValueResolver.stringify(args.get(2).evaluate())
                        : Fir408TestFilter.defaultName(pattern);
                yield fir408UploadTestFir(selector, pattern, name8);
            }
            case "attach-session" -> {
                if (args.isEmpty()) {
                    state.requireClient().attachSession();
                    yield "ok";
                }
                if (args.size() == 2) {
                    long timeoutMs = ScriptValueResolver.toLong(args.get(0).evaluate());
                    long pollMs = ScriptValueResolver.toLong(args.get(1).evaluate());
                    state.requireClient().attachSession(timeoutMs, pollMs);
                    yield "ok";
                }
                throw new IllegalArgumentException(
                        "Invalid attach-session syntax. Expected: attachSession() or attachSession(timeoutMs, pollMs)"
                );
            }
            case "reconnect-session" -> {
                if (args.size() > 2) {
                    throw new IllegalArgumentException("reconnectSession() expects 0..2 numeric arguments.");
                }
                long timeoutMs = args.size() >= 1
                        ? ScriptValueResolver.toLong(args.get(0).evaluate())
                        : 120000L;
                long pollMs = args.size() >= 2
                        ? ScriptValueResolver.toLong(args.get(1).evaluate())
                        : 250L;
                state.reconnectSession(timeoutMs, pollMs);
                yield "reconnected";
            }
            case "pause-for-user" -> {
                if (args.size() > 1) {
                    throw new IllegalArgumentException("pauseForUser() expects 0 or 1 argument.");
                }
                String note = args.size() == 1
                        ? ScriptValueResolver.stringify(args.get(0).evaluate())
                        : null;
                state.pauseForUser(note);
                yield "ok";
            }
            case "handshake-init" -> {
                requireCallArgCount(name, args, 0);
                yield state.requireClient().handshakeInit();
            }
            case "device-info" -> {
                requireCallArgCount(name, args, 0);
                yield state.requireClient().deviceInfo();
            }
            case "system-info" -> {
                requireCallArgCount(name, args, 0);
                yield state.requireClient().systemInfo();
            }
            case "login" -> {
                requireCallArgCount(name, args, 1);
                yield state.requireClient().login(
                        ScriptValueResolver.stringify(args.get(0).evaluate())
                );
            }
            case "read-block" -> {
                requireCallArgCount(name, args, 1);
                yield state.requireClient().readBlock(
                        ScriptValueResolver.toInt(args.get(0).evaluate())
                );
            }
            case "read-blocks" -> {
                requireCallArgCount(name, args, 2);
                int start = ScriptValueResolver.toInt(args.get(0).evaluate());
                int end = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield readBlocks(start, end);
            }
            case "read-config-blocks" -> {
                if (args.size() > 2) {
                    throw new IllegalArgumentException("Invalid readConfigBlocks syntax. Expected: readConfigBlocks([end]) or readConfigBlocks(start, end)");
                }
                int start = 0x00;
                int end = 0x1F;
                if (args.size() == 1) {
                    end = ScriptValueResolver.toInt(args.get(0).evaluate());
                } else if (args.size() == 2) {
                    start = ScriptValueResolver.toInt(args.get(0).evaluate());
                    end = ScriptValueResolver.toInt(args.get(1).evaluate());
                }
                yield readBlocks(start, end);
            }
            case "read-save-config" -> {
                if (args.isEmpty() || args.size() > 3) {
                    throw new IllegalArgumentException("Invalid readSaveConfig syntax. Expected: readSaveConfig(dir, [end]) or readSaveConfig(dir, start, end)");
                }
                String dir = ScriptValueResolver.stringify(args.get(0).evaluate());
                int start = 0x00;
                int end = 0x1F;
                if (args.size() == 2) {
                    end = ScriptValueResolver.toInt(args.get(1).evaluate());
                } else if (args.size() == 3) {
                    start = ScriptValueResolver.toInt(args.get(1).evaluate());
                    end = ScriptValueResolver.toInt(args.get(2).evaluate());
                }
                ReadBlockSet blocks = readBlocks(start, end);
                saveReadBlocks(blocks, dir);
                yield blocks;
            }
            case "read-block-index" -> {
                requireCallArgCount(name, args, 1);
                yield readBlockIndexOf(args.get(0).evaluate());
            }
            case "read-block-payload" -> {
                requireCallArgCount(name, args, 2);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int block = ScriptValueResolver.toInt(args.get(1).evaluate());
                SniffedFrame frame = capture.readBlockResponse(block);
                yield frame == null ? null : frame.payload();
            }
            case "cmd" -> {
                requireCallArgCount(name, args, 1);
                yield commandOf(args.get(0).evaluate());
            }
            case "payload" -> {
                requireCallArgCount(name, args, 1);
                yield payloadOf(args.get(0).evaluate());
            }
            case "raw" -> {
                requireCallArgCount(name, args, 1);
                yield rawOf(args.get(0).evaluate());
            }
            case "payload-hex" -> {
                requireCallArgCount(name, args, 1);
                yield HexUtil.toHex(payloadOf(args.get(0).evaluate()));
            }
            case "payload-ascii" -> {
                requireCallArgCount(name, args, 1);
                yield HexUtil.payloadAscii(payloadOf(args.get(0).evaluate()));
            }
            case "diff-bytes" -> {
                requireCallArgCount(name, args, 2);
                byte[] before = ScriptValueResolver.toBytes(args.get(0).evaluate());
                byte[] after = ScriptValueResolver.toBytes(args.get(1).evaluate());
                yield DumpDiffUtil.formatByteDiffs(before, after);
            }
            case "diff-u16le" -> {
                requireCallArgCount(name, args, 2);
                byte[] before = ScriptValueResolver.toBytes(args.get(0).evaluate());
                byte[] after = ScriptValueResolver.toBytes(args.get(1).evaluate());
                yield DumpDiffUtil.formatU16Diffs(before, after);
            }
            case "diff-report" -> {
                requireCallArgCount(name, args, 2);
                byte[] before = ScriptValueResolver.toBytes(args.get(0).evaluate());
                byte[] after = ScriptValueResolver.toBytes(args.get(1).evaluate());
                yield DumpDiffUtil.formatDiffReport(before, after);
            }
            case "changed-offsets" -> {
                requireCallArgCount(name, args, 2);
                byte[] before = ScriptValueResolver.toBytes(args.get(0).evaluate());
                byte[] after = ScriptValueResolver.toBytes(args.get(1).evaluate());
                yield changedOffsets(before, after);
            }
            case "send-payload", "tx", "write" -> evaluateCallSendPayload(args, state.requireClient());

            case "gui-capture" -> evaluateCallGuiCapture(args);
            case "gui-action-capture" -> evaluateCallGuiActionCapture(args);
            case "gui-begin-capture" -> {
                requireCallArgCount(name, args, 0);
                state.beginGuiCapture();
                yield "capture-begun";
            }
            case "gui-end-capture" -> {
                if (args.size() > 2) {
                    throw new IllegalArgumentException(
                            "Invalid gui-end-capture syntax. Expected: gui-end-capture([quietMs], [maxWaitMs])"
                    );
                }
                long quietMs = args.size() >= 1
                        ? ScriptValueResolver.toLong(args.get(0).evaluate())
                        : 350L;
                long maxWaitMs = args.size() >= 2
                        ? ScriptValueResolver.toLong(args.get(1).evaluate())
                        : 3000L;
                yield state.finishGuiCapture(quietMs, maxWaitMs);
            }

            case "capture-count" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).totalFrames();
            }
            case "capture-write-count" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).writeCount();
            }
            case "capture-response-count" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).responseCount();
            }
            case "capture-frame" -> {
                requireCallArgCount(name, args, 2);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int index = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield capture.frame(index);
            }
            case "first-write" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).firstWrite();
            }
            case "last-write" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).lastWrite();
            }
            case "first-response" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).firstResponse();
            }
            case "last-response" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).lastResponse();
            }
            case "last-write-excluding" -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("Too few arguments. Expected: last-write-excluding(<capture>, [cmd...])");
                }
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                yield capture.lastWriteExcluding(parseCommandsFromArgLists(args, 1));
            }
            case "writes-by-command" -> {
                requireCallArgCount(name, args, 2);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int command = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield writesByCommand(capture, command);
            }
            case "frames-by-command" -> {
                requireCallArgCount(name, args, 2);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int command = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield framesByCommand(capture, command);
            }
            case "responses-by-command" -> {
                requireCallArgCount(name, args, 2);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int command = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield responsesByCommand(capture, command);
            }
            case "writes-by-command-and-channel" -> {
                requireCallArgCount(name, args, 3);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int command = ScriptValueResolver.toInt(args.get(1).evaluate());
                int channel = ScriptValueResolver.toInt(args.get(2).evaluate());
                yield writesByCommandAndChannel(capture, command, channel);
            }
            case "recent-writes" -> {
                requireCallArgCount(name, args, 2);
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int limit = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield capture.recentWrites(limit);
            }
            case "recent-writes-excluding" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException(
                            "Too few arguments. Expected: recent-writes-excluding(<capture>, <limit>, [cmd...])"
                    );
                }
                GuiCaptureResult capture = requireCapture(args.get(0).evaluate());
                int limit = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield capture.recentWritesExcluding(limit, parseCommandsFromArgLists(args, 2));
            }
            case "writes" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).writes();
            }
            case "responses" -> {
                requireCallArgCount(name, args, 1);
                yield requireCapture(args.get(0).evaluate()).responses();
            }
            case "payload-series" -> {
                requireCallArgCount(name, args, 1);
                yield payloadSeries(args.get(0).evaluate());
            }
            case "u16-series" -> {
                requireCallArgCount(name, args, 2);
                Object frames = args.get(0).evaluate();
                int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield u16Series(frames, offset);
            }
            case "changing-offsets-across-writes" -> {
                requireCallArgCount(name, args, 1);
                yield changingOffsetsAcrossWrites(args.get(0).evaluate());
            }

            case "len" -> {
                requireCallArgCount(name, args, 1);
                yield lengthOf(args.get(0).evaluate());
            }
            case "contains" -> {
                requireCallArgCount(name, args, 2);
                yield contains(args.get(0).evaluate(), args.get(1).evaluate());
            }
            case "starts-with" -> {
                requireCallArgCount(name, args, 2);
                yield startsWith(args.get(0).evaluate(), args.get(1).evaluate());
            }
            case "ends-with" -> {
                requireCallArgCount(name, args, 2);
                yield endsWith(args.get(0).evaluate(), args.get(1).evaluate());
            }
            case "upper" -> {
                requireCallArgCount(name, args, 1);
                yield ScriptValueResolver.stringify(args.get(0).evaluate()).toUpperCase(Locale.ROOT);
            }
            case "lower" -> {
                requireCallArgCount(name, args, 1);
                yield ScriptValueResolver.stringify(args.get(0).evaluate()).toLowerCase(Locale.ROOT);
            }
            case "trim" -> {
                requireCallArgCount(name, args, 1);
                yield ScriptValueResolver.stringify(args.get(0).evaluate()).trim();
            }
            case "join" -> {
                if (args.isEmpty() || args.size() > 2) {
                    throw new IllegalArgumentException("Invalid join syntax. Expected: join(<list>, [separator])");
                }
                Object value = args.get(0).evaluate();
                String separator = args.size() >= 2
                        ? ScriptValueResolver.stringify(args.get(1).evaluate())
                        : ", ";
                yield join(value, separator);
            }
            case "at" -> {
                requireCallArgCount(name, args, 2);
                Object value = args.get(0).evaluate();
                int index = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield at(value, index);
            }
            case "split" -> {
                requireCallArgCount(name, args, 2);
                String text = ScriptValueResolver.stringify(args.get(0).evaluate());
                String separator = ScriptValueResolver.stringify(args.get(1).evaluate());
                yield split(text, separator);
            }
            case "replace" -> {
                requireCallArgCount(name, args, 3);
                String text = ScriptValueResolver.stringify(args.get(0).evaluate());
                String search = ScriptValueResolver.stringify(args.get(1).evaluate());
                String replacement = ScriptValueResolver.stringify(args.get(2).evaluate());
                yield text.replace(search, replacement);
            }

            case "u8" -> {
                requireCallArgCount(name, args, 2);
                byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
                int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield DumpByteReaders.u8(bytes, offset);
            }
            case "u16le" -> {
                requireCallArgCount(name, args, 2);
                byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
                int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield DumpByteReaders.u16le(bytes, offset);
            }
            case "u32le" -> {
                requireCallArgCount(name, args, 2);
                byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
                int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
                yield DumpByteReaders.u32le(bytes, offset);
            }
            case "ascii" -> {
                if (args.size() < 3 || args.size() > 4) {
                    throw new IllegalArgumentException("Invalid ascii syntax. Expected: ascii(<bytes>, <offset>, <len>, [trimzero])");
                }
                byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
                int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
                int length = ScriptValueResolver.toInt(args.get(2).evaluate());
                boolean trimZero = args.size() >= 4 && truthy(args.get(3).evaluate());
                yield DumpByteReaders.ascii(bytes, offset, length, trimZero);
            }
            case "hex" -> evaluateCallHex(args);
            case "slice" -> {
                requireCallArgCount(name, args, 3);
                byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
                int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
                int length = ScriptValueResolver.toInt(args.get(2).evaluate());
                yield DumpByteReaders.slice(bytes, offset, length);
            }
            case "bytes" -> {
                requireCallArgCount(name, args, 1);
                Object value = args.get(0).evaluate();
                yield toBytesFlexible(value);
            }

            default -> throw new IllegalArgumentException("Unknown function call: " + name);
        };
    }

    private Object evaluateSingle(String token) throws Exception {
        return evaluateExpression(List.of(token));
    }

    private ProxyResponse evaluateSendPayload(List<String> tokens, DspSession client) throws Exception {
        if (tokens.size() < 2) {
            throw new IllegalArgumentException("Invalid send-payload syntax.");
        }

        Integer expectedCommand = null;
        boolean strict = false;

        int expectIndex = -1;
        for (int i = 1; i < tokens.size(); i++) {
            if ("expect".equalsIgnoreCase(tokens.get(i))) {
                expectIndex = i;
                break;
            }
        }

        List<String> payloadTokens;
        if (expectIndex >= 0) {
            payloadTokens = tokens.subList(1, expectIndex);
            if (expectIndex + 1 >= tokens.size()) {
                throw new IllegalArgumentException("Missing value after 'expect'.");
            }
            expectedCommand = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(expectIndex + 1)));
            strict = true;
        } else {
            payloadTokens = tokens.subList(1, tokens.size());
        }

        if (payloadTokens.isEmpty()) {
            throw new IllegalArgumentException("Missing payload for send-payload.");
        }

        String hex = resolver.joinResolvedTokens(payloadTokens);
        byte[] payload = HexUtil.hexToBytes(hex);
        return client.sendPayload(payload, expectedCommand, strict, "script_send_payload");
    }

    private ProxyResponse evaluateCallSendPayload(List<ScriptArgument> args, DspSession client) throws Exception {
        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalArgumentException("Invalid write syntax. Expected: write(<payload>, [expectedCommand])");
        }

        byte[] payload = toBytesFlexible(args.get(0).evaluate());
        Integer expectedCommand = null;
        boolean strict = false;

        if (args.size() >= 2) {
            expectedCommand = ScriptValueResolver.toInt(args.get(1).evaluate());
            strict = true;
        }

        return client.sendPayload(payload, expectedCommand, strict, "script_send_payload");
    }

    private GuiCaptureResult evaluateGuiCapture(List<String> tokens) throws Exception {
        if (tokens.size() < 2 || tokens.size() > 4) {
            throw new IllegalArgumentException(
                    "Invalid gui-capture syntax. Expected: gui-capture [actionWindowMs] [quietMs] [maxWaitMs]"
            );
        }

        CaptureWindowOptions options = parseCaptureWindowTokenOptions(tokens.subList(1, tokens.size()));
        state.beginGuiCapture();
        printCaptureWindowInfo(options);
        sleep(options.actionWindowMs());
        return state.finishGuiCapture(options.quietMs(), options.maxWaitMs());
    }

    private GuiCaptureResult evaluateCallGuiCapture(List<ScriptArgument> args) throws Exception {
        if (args.size() > 4) {
            throw new IllegalArgumentException(
                    "Invalid gui-capture syntax. Expected: gui-capture([actionWindowMs], [quietMs], [maxWaitMs])"
                );
        }

        CaptureWindowOptions options = parseCaptureWindowCallOptions(args);
        state.beginGuiCapture();
        printCaptureWindowInfo(options);
        sleep(options.actionWindowMs());
        return state.finishGuiCapture(options.quietMs(), options.maxWaitMs());
    }

    private GuiCaptureResult evaluateGuiActionCapture(List<String> tokens) throws Exception {
        if (tokens.size() < 2 || tokens.size() > 4) {
            throw new IllegalArgumentException(
                    "Invalid gui-action-capture syntax. Expected: gui-action-capture [actionWindowMs] [quietMs] [maxWaitMs]"
            );
        }

        CaptureWindowOptions options = parseCaptureWindowTokenOptions(tokens.subList(1, tokens.size()));
        printGuiActionCaptureInfo(options);
        return state.captureGuiAction(options.actionWindowMs(), options.quietMs(), options.maxWaitMs(), 0x40);
    }

    private GuiCaptureResult evaluateCallGuiActionCapture(List<ScriptArgument> args) throws Exception {
        if (args.size() > 4) {
            throw new IllegalArgumentException(
                    "Invalid gui-action-capture syntax. Expected: gui-action-capture([actionWindowMs], [quietMs], [maxWaitMs])"
            );
        }

        CaptureWindowOptions options = parseCaptureWindowCallOptions(args);
        printGuiActionCaptureInfo(options);
        return state.captureGuiAction(options.actionWindowMs(), options.quietMs(), options.maxWaitMs(), 0x40);
    }

    private CaptureWindowOptions parseCaptureWindowTokenOptions(List<String> argTokens) throws Exception {
        long actionWindowMs = 15000L;
        long quietMs = 1500L;
        long maxWaitMs = 12000L;
        String note = null;

        int numericIndex = 0;
        for (String token : argTokens) {
            Object value = resolver.resolveValue(token);
            if (value instanceof Number number) {
                long numericValue = number.longValue();
                if (numericIndex == 0) {
                    actionWindowMs = numericValue;
                } else if (numericIndex == 1) {
                    quietMs = numericValue;
                } else if (numericIndex == 2) {
                    maxWaitMs = numericValue;
                } else {
                    throw new IllegalArgumentException("Too many numeric gui-capture arguments.");
                }
                numericIndex++;
            } else if (note == null) {
                note = ScriptValueResolver.stringify(value);
            } else {
                throw new IllegalArgumentException("Invalid gui-capture arguments.");
            }
        }

        return new CaptureWindowOptions(note, actionWindowMs, quietMs, maxWaitMs);
    }

    private CaptureWindowOptions parseCaptureWindowCallOptions(List<ScriptArgument> args) throws Exception {
        long actionWindowMs = 15000L;
        long quietMs = 1500L;
        long maxWaitMs = 12000L;
        String note = null;

        int numericIndex = 0;
        for (ScriptArgument arg : args) {
            Object value = arg.evaluate();
            if (value instanceof Number number) {
                long numericValue = number.longValue();
                if (numericIndex == 0) {
                    actionWindowMs = numericValue;
                } else if (numericIndex == 1) {
                    quietMs = numericValue;
                } else if (numericIndex == 2) {
                    maxWaitMs = numericValue;
                } else {
                    throw new IllegalArgumentException("Too many numeric gui-capture arguments.");
                }
                numericIndex++;
            } else if (note == null) {
                note = ScriptValueResolver.stringify(value);
            } else {
                throw new IllegalArgumentException("Invalid gui-capture arguments.");
            }
        }

        return new CaptureWindowOptions(note, actionWindowMs, quietMs, maxWaitMs);
    }

    private static void printGuiActionCaptureInfo(CaptureWindowOptions options) {
        if (options.note() != null && !options.note().isBlank()) {
            System.out.println("[GUI] " + options.note());
        }
        System.out.println("[GUI] Action capture armed. Waiting for first non-0x40 GUI write. Window: "
                + options.actionWindowMs() + " ms");
    }

    private static void printCaptureWindowInfo(CaptureWindowOptions options) {
        if (options.note() != null && !options.note().isBlank()) {
            System.out.println("[GUI] " + options.note());
        }
        System.out.println("[GUI] Capture armed. Action window: " + options.actionWindowMs() + " ms");
        System.out.flush();
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private Object evaluateHex(List<String> tokens) throws Exception {
        requireArgs(tokens, 2, "hex <bytesExpr> [offset len]");
        byte[] bytes = ScriptValueResolver.toBytes(evaluateSingle(tokens.get(1)));

        if (tokens.size() == 2) {
            return HexUtil.toHex(bytes);
        }

        if (tokens.size() != 4) {
            throw new IllegalArgumentException("hex expects either 1 or 3 arguments.");
        }

        int offset = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(2)));
        int length = ScriptValueResolver.toInt(resolver.resolveValue(tokens.get(3)));
        return DumpByteReaders.hex(bytes, offset, length);
    }

    private Object evaluateCallHex(List<ScriptArgument> args) throws Exception {
        if (args.size() == 1) {
            byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
            return HexUtil.toHex(bytes);
        }

        if (args.size() == 3) {
            byte[] bytes = ScriptValueResolver.toBytes(args.get(0).evaluate());
            int offset = ScriptValueResolver.toInt(args.get(1).evaluate());
            int length = ScriptValueResolver.toInt(args.get(2).evaluate());
            return DumpByteReaders.hex(bytes, offset, length);
        }

        throw new IllegalArgumentException("hex expects either 1 or 3 arguments.");
    }

    private static GuiCaptureResult requireCapture(Object value) {
        if (value instanceof GuiCaptureResult capture) {
            return capture;
        }
        throw new IllegalArgumentException("Value is not a GuiCaptureResult: " + value);
    }

    private static Integer commandOf(Object value) throws Exception {
        if (value instanceof ProxyResponse response) {
            return response.command();
        }
        if (value instanceof SniffedFrame frame) {
            return frame.command();
        }
        byte[] payload = ScriptValueResolver.toBytes(value);
        return DspProtocol.command(payload);
    }

    private static Integer readBlockIndexOf(Object value) throws Exception {
        if (value instanceof ProxyResponse response) {
            return response.readBlockIndex();
        }
        if (value instanceof SniffedFrame frame) {
            return frame.readBlockIndex();
        }
        byte[] payload = ScriptValueResolver.toBytes(value);
        return DspProtocol.readBlockIndex(payload);
    }

    private static byte[] payloadOf(Object value) throws Exception {
        if (value instanceof ProxyResponse response) {
            return response.payload();
        }
        if (value instanceof SniffedFrame frame) {
            return frame.payload();
        }
        return ScriptValueResolver.toBytes(value);
    }

    private static byte[] rawOf(Object value) throws Exception {
        if (value instanceof ProxyResponse response) {
            return response.raw();
        }
        if (value instanceof SniffedFrame frame) {
            return frame.frame();
        }
        return ScriptValueResolver.toBytes(value);
    }

    private static List<Integer> changedOffsets(byte[] before, byte[] after) {
        List<Integer> out = new ArrayList<>();
        for (ByteDiff diff : DumpDiffUtil.diffBytes(before, after)) {
            out.add(diff.offset());
        }
        return List.copyOf(out);
    }

    private static String saveDiffReport(String pathText, byte[] before, byte[] after) throws Exception {
        Path path = Path.of(pathText);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String report = DumpDiffUtil.formatDiffReport(before, after);
        Files.writeString(path, report, StandardCharsets.UTF_8);
        return path.toAbsolutePath().toString();
    }

    private ReadBlockSet readBlocks(int start, int end) throws Exception {
        if (start < 0 || start > 0xFF || end < 0 || end > 0xFF) {
            throw new IllegalArgumentException("Block range must be inside 0x00..0xFF.");
        }
        if (end < start) {
            throw new IllegalArgumentException("Block range end is smaller than start.");
        }

        DspSession client = state.requireClient();
        List<ProxyResponse> responses = new ArrayList<>();
        for (int block = start; block <= end; block++) {
            ProxyResponse response = client.sendPayload(
                    readBlockPayload(block),
                    0x24,
                    true,
                    "read_config_block_" + String.format("%02X", block)
            );
            if (response == null) {
                throw new IllegalStateException("No response for read block " + String.format("0x%02X", block));
            }
            Integer actual = response.readBlockIndex();
            if (actual == null || actual != (block & 0xFF)) {
                throw new IllegalStateException(
                        "Unexpected read block response. Expected "
                                + String.format("0x%02X", block)
                                + ", got "
                                + (actual == null ? "null" : String.format("0x%02X", actual))
                );
            }
            responses.add(response);
        }
        return new ReadBlockSet(responses);
    }

    private static byte[] readBlockPayload(int block) {
        return new byte[]{0x00, 0x01, 0x02, 0x27, (byte) (block & 0xFF)};
    }

    private static int saveReadBlocks(Object value, String dirText) throws Exception {
        List<BlockPayload> blocks = blockPayloadsFrom(value);
        Path dir = Path.of(dirText);
        Files.createDirectories(dir.toAbsolutePath());

        List<String> allLines = new ArrayList<>();
        int saved = 0;
        for (BlockPayload block : blocks) {
            Path hexPath = dir.resolve(String.format("block-%02X.hex.txt", block.index()));
            Path asciiPath = dir.resolve(String.format("block-%02X.ascii.txt", block.index()));
            String payloadHex = HexUtil.toHex(block.payload());
            Files.writeString(hexPath, payloadHex, StandardCharsets.UTF_8);
            Files.writeString(asciiPath, HexUtil.payloadAscii(block.payload()), StandardCharsets.UTF_8);
            allLines.add(String.format("block-%02X: %s", block.index(), payloadHex));
            saved++;
        }
        Files.writeString(dir.resolve("all-blocks.hex.txt"), String.join(System.lineSeparator(), allLines), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("assembled-data.hex.txt"), HexUtil.toHex(assembledDataFromBlocks(blocks)), StandardCharsets.UTF_8);
        return saved;
    }

    private static byte[] assembledData(Object value) throws Exception {
        if (value instanceof ReadBlockSet set) {
            return set.data();
        }
        return assembledDataFromBlocks(blockPayloadsFrom(value));
    }

    private String fir408SafePings(Object value) throws Exception {
        return new Fir408Diagnostics(state.requireClient()).sameValuePings(assembledData(value));
    }

    private static String decodeFir408Config(Object value) throws Exception {
        return Fir408Memory.decode(assembledData(value));
    }

    private String fir408Cmd56ReadonlySweep(String dir, int selectorStart, int selectorEnd,
                                           int offsetStart, int offsetEnd, int step) throws Exception {
        return new Fir408Diagnostics(state.requireClient())
                .readOnlySweep(dir, selectorStart, selectorEnd, offsetStart, offsetEnd, step);
    }

    private String fir408Cmd56ReadonlyOffsets(String dir, int selector, String offsets, int attempts) throws Exception {
        return new Fir408Diagnostics(state.requireClient()).readOnlyOffsets(dir, selector, offsets, attempts);
    }

    private String fir408UploadTestFir(int selector, String pattern, String name) throws Exception {
        return new Fir408TestFilter(state.requireClient()).upload(selector, pattern, name);
    }

    private static byte[] assembledDataFromBlocks(List<BlockPayload> blocks) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (BlockPayload block : blocks) {
            byte[] payload = block.payload();
            if (payload.length <= 5) {
                continue;
            }
            out.write(payload, 5, payload.length - 5);
        }
        return out.toByteArray();
    }

    private static List<BlockPayload> blockPayloadsFrom(Object value) throws Exception {
        if (value instanceof ReadBlockSet set) {
            return blockPayloadsFrom(set.responses());
        }
        if (value instanceof GuiCaptureResult capture) {
            return blockPayloadsFrom(capture.readBlockResponses());
        }
        if (value instanceof List<?> list) {
            List<BlockPayload> out = new ArrayList<>();
            for (Object item : list) {
                BlockPayload block = blockPayloadFrom(item);
                if (block != null) {
                    out.add(block);
                }
            }
            return List.copyOf(out);
        }

        BlockPayload single = blockPayloadFrom(value);
        if (single != null) {
            return List.of(single);
        }
        throw new IllegalArgumentException("Value is not a read-block response/list/set: " + value);
    }

    private static BlockPayload blockPayloadFrom(Object value) throws Exception {
        byte[] payload;
        if (value instanceof ProxyResponse response) {
            payload = response.payload();
        } else if (value instanceof SniffedFrame frame) {
            payload = frame.payload();
        } else if (value instanceof byte[] bytes) {
            payload = bytes;
        } else if (value instanceof String s) {
            payload = HexUtil.hexToBytes(s);
        } else {
            return null;
        }

        Integer index = DspProtocol.readBlockIndex(payload);
        if (index == null) {
            return null;
        }
        Integer command = DspProtocol.command(payload);
        if (command == null || command != 0x24) {
            return null;
        }
        return new BlockPayload(index, payload);
    }

    private static Integer[] parseCommands(List<String> tokens, int startIndex) {
        List<Integer> out = new ArrayList<>();
        for (int i = startIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.matches("0[xX][0-9A-Fa-f]+")) {
                out.add(Integer.parseInt(token.substring(2), 16));
            } else {
                out.add(Integer.parseInt(token));
            }
        }
        return out.toArray(new Integer[0]);
    }

    private Integer[] parseCommandsFromArgLists(List<ScriptArgument> args, int startIndex) throws Exception {
        List<Integer> out = new ArrayList<>();
        for (int i = startIndex; i < args.size(); i++) {
            out.add(ScriptValueResolver.toInt(args.get(i).evaluate()));
        }
        return out.toArray(new Integer[0]);
    }

    private static int lengthOf(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        if (value instanceof String s) {
            return s.length();
        }
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof GuiCaptureResult capture) {
            return capture.totalFrames();
        }
        return 1;
    }

    private static boolean contains(Object value, Object part) {
        if (value == null) {
            return false;
        }

        if (value instanceof String s) {
            return s.contains(ScriptValueResolver.stringify(part));
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (valueEquals(item, part)) {
                    return true;
                }
            }
            return false;
        }

        if (value instanceof byte[] bytes) {
            if (part instanceof Number n) {
                int needle = n.intValue() & 0xFF;
                for (byte b : bytes) {
                    if ((b & 0xFF) == needle) {
                        return true;
                    }
                }
                return false;
            }

            if (part instanceof byte[] needle) {
                return containsSubsequence(bytes, needle);
            }
        }

        return ScriptValueResolver.stringify(value).contains(ScriptValueResolver.stringify(part));
    }

    private static boolean startsWith(Object value, Object prefix) {
        if (value == null) {
            return false;
        }

        if (value instanceof String s) {
            return s.startsWith(ScriptValueResolver.stringify(prefix));
        }

        if (value instanceof byte[] bytes && prefix instanceof byte[] needle) {
            if (needle.length > bytes.length) {
                return false;
            }
            for (int i = 0; i < needle.length; i++) {
                if (bytes[i] != needle[i]) {
                    return false;
                }
            }
            return true;
        }

        return ScriptValueResolver.stringify(value).startsWith(ScriptValueResolver.stringify(prefix));
    }

    private static boolean endsWith(Object value, Object suffix) {
        if (value == null) {
            return false;
        }

        if (value instanceof String s) {
            return s.endsWith(ScriptValueResolver.stringify(suffix));
        }

        if (value instanceof byte[] bytes && suffix instanceof byte[] needle) {
            if (needle.length > bytes.length) {
                return false;
            }
            int start = bytes.length - needle.length;
            for (int i = 0; i < needle.length; i++) {
                if (bytes[start + i] != needle[i]) {
                    return false;
                }
            }
            return true;
        }

        return ScriptValueResolver.stringify(value).endsWith(ScriptValueResolver.stringify(suffix));
    }

    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        if (needle == null || needle.length == 0) {
            return true;
        }
        if (haystack == null || needle.length > haystack.length) {
            return false;
        }

        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }

        return false;
    }

    private static String join(Object value, String separator) {
        if (value == null) {
            return "";
        }

        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(separator);
                }
                sb.append(ScriptValueResolver.stringify(list.get(i)));
            }
            return sb.toString();
        }

        if (value instanceof byte[] bytes) {
            List<String> hex = new ArrayList<>();
            for (byte b : bytes) {
                hex.add(String.format("%02X", b & 0xFF));
            }
            return String.join(separator, hex);
        }

        return ScriptValueResolver.stringify(value);
    }

    private static Object at(Object value, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index < 0: " + index);
        }

        if (value instanceof List<?> list) {
            if (index >= list.size()) {
                throw new IllegalArgumentException("List index out of range: " + index);
            }
            return list.get(index);
        }

        if (value instanceof byte[] bytes) {
            if (index >= bytes.length) {
                throw new IllegalArgumentException("Byte index out of range: " + index);
            }
            return Byte.toUnsignedInt(bytes[index]);
        }

        if (value instanceof String s) {
            if (index >= s.length()) {
                throw new IllegalArgumentException("String index out of range: " + index);
            }
            return String.valueOf(s.charAt(index));
        }

        throw new IllegalArgumentException("at is not supported for this type: "
                + value.getClass().getName());
    }

    private static List<String> split(String text, String separator) {
        if (text == null) {
            return List.of();
        }
        if (separator == null) {
            separator = "";
        }

        if (separator.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                out.add(String.valueOf(text.charAt(i)));
            }
            return List.copyOf(out);
        }

        return List.of(text.split(java.util.regex.Pattern.quote(separator), -1));
    }

    private static byte[] toBytesFlexible(Object value) throws Exception {
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
            return HexUtil.hexToBytes(s);
        }
        throw new IllegalArgumentException("Value is not a payload/hex: " + value);
    }

    static boolean truthy(Object value) {
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

    private static List<String> normalizeTokens(List<String> tokens) {
        List<String> out = new ArrayList<>();
        if (tokens == null) {
            return out;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    private static boolean isWrappedExpression(List<String> tokens) {
        return tokens.size() >= 2
                && "(".equals(tokens.get(0))
                && findMatchingParenIndex(tokens, 0) == tokens.size() - 1;
    }

    private static boolean isFunctionCallExpression(List<String> tokens) {
        return tokens.size() >= 3
                && isExpressionStarter(tokens.get(0).toLowerCase(Locale.ROOT))
                && "(".equals(tokens.get(1))
                && findMatchingParenIndex(tokens, 1) == tokens.size() - 1;
    }

    static int findMatchingParenIndex(List<String> tokens, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("(".equals(token)) {
                depth++;
            } else if (")".equals(token)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Missing closing parenthesis.");
    }

    private static List<List<String>> splitArguments(List<String> tokens, int startInclusive, int endExclusive) {
        List<List<String>> args = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int depth = 0;

        for (int i = startInclusive; i < endExclusive; i++) {
            String token = tokens.get(i);

            if ("(".equals(token)) {
                depth++;
                current.add(token);
                continue;
            }

            if (")".equals(token)) {
                depth--;
                current.add(token);
                continue;
            }

            if (",".equals(token) && depth == 0) {
                args.add(List.copyOf(current));
                current.clear();
                continue;
            }

            current.add(token);
        }

        if (!current.isEmpty()) {
            args.add(List.copyOf(current));
        }

        if (args.size() == 1 && args.get(0).isEmpty()) {
            return List.of();
        }

        return args;
    }

    private static void requireCallArgCount(String name, List<ScriptArgument> args, int expected) {
        if (args.size() != expected) {
            throw new IllegalArgumentException(
                    "Wrong number of arguments for " + name + ": expected " + expected + ", got " + args.size()
            );
        }
    }

    static boolean isExpressionStarter(String command) {
        return de.drremote.dsp408.dspd.DspdBuiltins.isExpressionStarter(command);
    }

    static String normalizeFunctionName(String name) {
        return de.drremote.dsp408.dspd.DspdBuiltins.normalizeFunctionName(name);
    }

    private static List<SniffedFrame> writesByCommand(GuiCaptureResult capture, int command) {
        List<SniffedFrame> out = new ArrayList<>();
        int expectedCommand = command & 0xFF;
        for (SniffedFrame frame : capture.frames()) {
            if (frame != null && frame.isWrite() && frame.command() != null && frame.command() == expectedCommand) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    private static List<SniffedFrame> framesByCommand(GuiCaptureResult capture, int command) {
        List<SniffedFrame> out = new ArrayList<>();
        int expectedCommand = command & 0xFF;
        for (SniffedFrame frame : capture.frames()) {
            if (frame != null && frame.command() != null && frame.command() == expectedCommand) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    private static List<SniffedFrame> responsesByCommand(GuiCaptureResult capture, int command) {
        List<SniffedFrame> out = new ArrayList<>();
        int expectedCommand = command & 0xFF;
        for (SniffedFrame frame : capture.frames()) {
            if (frame != null && frame.isResponse() && frame.command() != null && frame.command() == expectedCommand) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    private static List<SniffedFrame> writesByCommandAndChannel(GuiCaptureResult capture, int command, int channel) {
        List<SniffedFrame> out = new ArrayList<>();
        int expectedCommand = command & 0xFF;
        int expectedChannel = channel & 0xFF;
        for (SniffedFrame frame : capture.frames()) {
            if (frame == null || !frame.isWrite() || frame.command() == null || frame.command() != expectedCommand) {
                continue;
            }
            byte[] payload = frame.payload();
            if (payload.length > 4 && (payload[4] & 0xFF) == expectedChannel) {
                out.add(frame);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> payloadSeries(Object value) {
        List<SniffedFrame> frames = toFrameList(value);
        List<String> out = new ArrayList<>();
        for (SniffedFrame frame : frames) {
            out.add(frame.payloadHex());
        }
        return List.copyOf(out);
    }

    private static List<Integer> u16Series(Object value, int offset) {
        List<SniffedFrame> frames = toFrameList(value);
        List<Integer> out = new ArrayList<>();
        for (SniffedFrame frame : frames) {
            byte[] payload = frame.payload();
            if (offset >= 0 && offset + 1 < payload.length) {
                out.add(DumpByteReaders.u16le(payload, offset));
            }
        }
        return List.copyOf(out);
    }

    private static List<Integer> changingOffsetsAcrossWrites(Object value) {
        List<SniffedFrame> frames = toFrameList(value);
        if (frames.size() < 2) {
            return List.of();
        }

        int minLen = Integer.MAX_VALUE;
        for (SniffedFrame frame : frames) {
            minLen = Math.min(minLen, frame.payload().length);
        }
        if (minLen <= 0 || minLen == Integer.MAX_VALUE) {
            return List.of();
        }

        List<Integer> out = new ArrayList<>();
        for (int offset = 0; offset < minLen; offset++) {
            int first = frames.get(0).payload()[offset] & 0xFF;
            boolean changed = false;
            for (int i = 1; i < frames.size(); i++) {
                if ((frames.get(i).payload()[offset] & 0xFF) != first) {
                    changed = true;
                    break;
                }
            }
            if (changed) {
                out.add(offset);
            }
        }
        return List.copyOf(out);
    }

    private static List<SniffedFrame> toFrameList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Value is not a frame list: " + value);
        }

        List<SniffedFrame> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof SniffedFrame frame)) {
                throw new IllegalArgumentException("List item is not a SniffedFrame: " + item);
            }
            out.add(frame);
        }
        return List.copyOf(out);
    }

    static boolean valueEquals(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return Double.compare(l.doubleValue(), r.doubleValue()) == 0;
        }

        if (left instanceof byte[] lb && right instanceof byte[] rb) {
            if (lb.length != rb.length) {
                return false;
            }
            for (int i = 0; i < lb.length; i++) {
                if (lb[i] != rb[i]) {
                    return false;
                }
            }
            return true;
        }

        return Objects.equals(left, right);
    }

    static String displayValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[] bytes) {
            return HexUtil.toHexPreview(bytes, 16);
        }
        if (value instanceof GuiCaptureResult capture) {
            return capture.toString();
        }
        if (value instanceof ReadBlockSet blocks) {
            return blocks.toString();
        }
        if (value instanceof SniffedFrame frame) {
            return frame.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append("list[size=").append(list.size()).append("]");
            int preview = Math.min(5, list.size());
            for (int i = 0; i < preview; i++) {
                sb.append("\n  [").append(i).append("] ")
                        .append(DspScriptEngine.abbreviate(String.valueOf(list.get(i)), 160));
            }
            if (list.size() > preview) {
                sb.append("\n  ...");
            }
            return sb.toString();
        }
        return DspScriptEngine.abbreviate(String.valueOf(value), 200);
    }

    private static void requireArgs(List<String> tokens, int minSize, String syntax) {
        if (tokens.size() < minSize) {
            throw new IllegalArgumentException("Too few arguments. Expected: " + syntax);
        }
    }

    private record CaptureWindowOptions(String note, long actionWindowMs, long quietMs, long maxWaitMs) {
    }

    private record BlockPayload(int index, byte[] payload) {
        private BlockPayload {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }


}
