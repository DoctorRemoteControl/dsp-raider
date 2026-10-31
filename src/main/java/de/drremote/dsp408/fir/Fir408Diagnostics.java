package de.drremote.dsp408.fir;

import de.drremote.dsp408.model.ProxyResponse;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.util.DspProtocol;
import de.drremote.dsp408.util.HexUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static de.drremote.dsp408.fir.Fir408Memory.*;

/** Device probes and diagnostic capture files, independent of DSPD syntax. */
public final class Fir408Diagnostics {
    private final DspSession session;

    public Fir408Diagnostics(DspSession session) { this.session = Objects.requireNonNull(session); }

    public String sameValuePings(byte[] memory) throws Exception {
        DspSession client = session;
        List<PingSpec> pings = Fir408Memory.sameValuePings(memory);
        List<String> lines = new ArrayList<>();
        lines.add("# FIR408 DSPD Safe Same-Value Pings");
        lines.add("");
        lines.add("- Source: current `readSaveConfig`/`ReadBlockSet` memory");
        lines.add("- Writes: current values only");
        lines.add("- Skipped deliberately: mute writes, crossover remembered-state writes, and extended dynamics");
        lines.add("");
        lines.add("| Test | Payload | Response |");
        lines.add("| --- | --- | --- |");

        for (PingSpec ping : pings) {
            ProxyResponse response = client.sendPayload(ping.payload(), 0x01, true, ping.name());
            lines.add("| " + ping.name() + " | `" + HexUtil.toHex(ping.payload()) + "` | `"
                    + (response == null ? "null" : response.payloadHex()) + "` |");
        }

        lines.add("");
        lines.add("- Total pings: " + pings.size());
        return String.join(System.lineSeparator(), lines);
    }

    public String readOnlySweep(String dirText,
                                            int selectorStart,
                                            int selectorEnd,
                                            int offsetStart,
                                            int offsetEnd,
                                            int step) throws Exception {
        if (selectorStart < 0 || selectorStart > 0xFF || selectorEnd < 0 || selectorEnd > 0xFF) {
            throw new IllegalArgumentException("Selector range must be inside 0x00..0xFF.");
        }
        if (offsetStart < 0 || offsetStart > 0xFFFF || offsetEnd < 0 || offsetEnd > 0xFFFF) {
            throw new IllegalArgumentException("Offset range must be inside 0..65535.");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("Sweep step must be greater than zero.");
        }

        DspSession client = session;
        Path dir = Path.of(dirText);
        Path responseDir = dir.resolve("responses");
        Files.createDirectories(responseDir.toAbsolutePath());

        List<Cmd56SweepRow> rows = new ArrayList<>();
        List<String> csv = new ArrayList<>();
        csv.add("selector,offset,request_hex,response_hex,payload_len,data_len,non_ff_bytes,non_ff_float_slots,first_float,second_float,class,prefix_hex");

        int selectorStep = selectorStart <= selectorEnd ? 1 : -1;
        int offsetStep = offsetStart <= offsetEnd ? step : -step;

        for (int selector = selectorStart; ; selector += selectorStep) {
            for (int offset = offsetStart; ; offset += offsetStep) {
                byte[] request = payload(0x00, 0x01, 0x04, 0x56, selector, lo(offset), hi(offset));
                String name = "cmd56_sel" + String.format("%02X", selector) + "_off" + String.format("%04d", offset);
                ProxyResponse response = null;
                Exception lastError = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        response = client.sendPayload(request, 0x56, true, name + "_try" + attempt);
                        lastError = null;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        Thread.sleep(150L * attempt);
                    }
                }
                Cmd56SweepRow row = cmd56SweepRow(selector, offset, request, response);
                rows.add(row);
                csv.add(cmd56Csv(row));

                String file = String.format("sel-%02X-off-%04d.hex.txt", selector, offset);
                Files.writeString(
                        responseDir.resolve(file),
                        "request=" + HexUtil.toHex(request) + System.lineSeparator()
                                + "response=" + row.responseHex() + System.lineSeparator()
                                + "data_prefix=" + row.prefixHex() + System.lineSeparator()
                                + "class=" + row.classification() + System.lineSeparator(),
                        StandardCharsets.UTF_8
                );

                if (offset == offsetEnd) {
                    break;
                }
                if ((offsetStep > 0 && offset + offsetStep > offsetEnd)
                        || (offsetStep < 0 && offset + offsetStep < offsetEnd)) {
                    break;
                }
            }

            if (selector == selectorEnd) {
                break;
            }
        }

        String summary = cmd56SweepSummary(rows, selectorStart, selectorEnd, offsetStart, offsetEnd, step);
        Files.writeString(dir.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("results.csv"), String.join(System.lineSeparator(), csv), StandardCharsets.UTF_8);
        return summary;
    }

    public String readOnlyOffsets(String dirText,
                                              int selector,
                                              String offsetsText,
                                              int attempts) throws Exception {
        if (selector < 0 || selector > 0xFF) {
            throw new IllegalArgumentException("Selector must be inside 0x00..0xFF.");
        }
        if (attempts <= 0 || attempts > 5) {
            throw new IllegalArgumentException("Attempts must be inside 1..5.");
        }

        List<Integer> offsets = parseOffsetList(offsetsText);
        DspSession client = session;
        Path dir = Path.of(dirText);
        Path responseDir = dir.resolve("responses");
        Files.createDirectories(responseDir.toAbsolutePath());

        List<Cmd56SweepRow> rows = new ArrayList<>();
        List<String> csv = new ArrayList<>();
        csv.add("selector,offset,request_hex,response_hex,payload_len,data_len,non_ff_bytes,non_ff_float_slots,first_float,second_float,class,prefix_hex");

        for (int offset : offsets) {
            byte[] request = payload(0x00, 0x01, 0x04, 0x56, selector, lo(offset), hi(offset));
            String name = "cmd56_sel" + String.format("%02X", selector) + "_off" + String.format("%04d", offset);
            ProxyResponse response = null;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    response = client.sendPayload(request, 0x56, true, name + "_try" + attempt);
                    break;
                } catch (Exception e) {
                    if (attempt < attempts) {
                        Thread.sleep(150L * attempt);
                    }
                }
            }

            Cmd56SweepRow row = cmd56SweepRow(selector, offset, request, response);
            rows.add(row);
            csv.add(cmd56Csv(row));

            String file = String.format("sel-%02X-off-%04d.hex.txt", selector, offset);
            Files.writeString(
                    responseDir.resolve(file),
                    "request=" + HexUtil.toHex(request) + System.lineSeparator()
                            + "response=" + row.responseHex() + System.lineSeparator()
                            + "data_prefix=" + row.prefixHex() + System.lineSeparator()
                            + "class=" + row.classification() + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
        }

        String summary = cmd56OffsetsSummary(rows, selector, offsetsText, attempts);
        Files.writeString(dir.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("results.csv"), String.join(System.lineSeparator(), csv), StandardCharsets.UTF_8);
        return summary;
    }

    private static Cmd56SweepRow cmd56SweepRow(int selector, int offset, byte[] request, ProxyResponse response) {
        byte[] responsePayload = response == null ? new byte[0] : response.payload();
        String responseHex = response == null ? "NO RESPONSE" : response.payloadHex();
        boolean valid = response != null
                && responsePayload.length >= 7
                && DspProtocol.command(responsePayload) != null
                && DspProtocol.command(responsePayload) == 0x56;
        byte[] data = valid ? Arrays.copyOfRange(responsePayload, 7, responsePayload.length) : new byte[0];

        int nonFfBytes = countNonFfBytes(data);
        int nonFfSlots = countNonFfFloatSlots(data);
        String firstFloat = nonFfBytes == 0 ? "" : float32LeString(data, 0);
        String secondFloat = nonFfBytes == 0 ? "" : float32LeString(data, 4);
        String classification;
        if (response == null) {
            classification = "no_response";
        } else if (!valid) {
            classification = "unexpected_response";
        } else if (data.length == 0) {
            classification = "empty_data";
        } else if (nonFfBytes == 0) {
            classification = "ff_empty_or_unavailable";
        } else if (isAllZero(data)) {
            classification = "zero_filled_data";
        } else if (nonFfBytes < data.length) {
            classification = "partial_data";
        } else {
            classification = "data";
        }

        return new Cmd56SweepRow(
                selector,
                offset,
                HexUtil.toHex(request),
                responseHex,
                responsePayload.length,
                data.length,
                nonFfBytes,
                nonFfSlots,
                firstFloat,
                secondFloat,
                classification,
                HexUtil.toHexPreview(data, 20)
        );
    }

    private static String cmd56SweepSummary(List<Cmd56SweepRow> rows,
                                            int selectorStart,
                                            int selectorEnd,
                                            int offsetStart,
                                            int offsetEnd,
                                            int step) {
        int dataRows = 0;
        int zeroRows = 0;
        int ffRows = 0;
        int partialRows = 0;
        int noResponseRows = 0;
        for (Cmd56SweepRow row : rows) {
            switch (row.classification()) {
                case "data" -> dataRows++;
                case "zero_filled_data" -> zeroRows++;
                case "ff_empty_or_unavailable" -> ffRows++;
                case "partial_data" -> partialRows++;
                case "no_response" -> noResponseRows++;
                default -> {
                }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# FIR408 0x56 Read-only Sweep");
        lines.add("");
        lines.add("- Read-only probe. No DSP setting write commands are sent.");
        lines.add("- Selectors: " + String.format("0x%02X..0x%02X", selectorStart, selectorEnd));
        lines.add("- Offsets: " + offsetStart + ".." + offsetEnd + " step " + step);
        lines.add("- Requests: " + rows.size());
        lines.add("- Data rows: " + dataRows + ", zero rows: " + zeroRows
                + ", partial rows: " + partialRows + ", FF rows: " + ffRows
                + ", no response rows: " + noResponseRows);
        lines.add("");
        lines.add("| Selector | Offset | Bytes | Non-FF bytes | Non-FF slots | Prefix | Float[0] | Float[1] | Class |");
        lines.add("| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |");
        for (Cmd56SweepRow row : rows) {
            lines.add("| " + String.format("0x%02X", row.selector())
                    + " | " + row.offset()
                    + " | " + row.dataLen()
                    + " | " + row.nonFfBytes()
                    + " | " + row.nonFfFloatSlots()
                    + " | `" + row.prefixHex() + "`"
                    + " | " + row.firstFloat()
                    + " | " + row.secondFloat()
                    + " | " + row.classification()
                    + " |");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static String cmd56OffsetsSummary(List<Cmd56SweepRow> rows,
                                              int selector,
                                              String offsetsText,
                                              int attempts) {
        int dataRows = 0;
        int zeroRows = 0;
        int ffRows = 0;
        int partialRows = 0;
        int noResponseRows = 0;
        for (Cmd56SweepRow row : rows) {
            switch (row.classification()) {
                case "data" -> dataRows++;
                case "zero_filled_data" -> zeroRows++;
                case "ff_empty_or_unavailable" -> ffRows++;
                case "partial_data" -> partialRows++;
                case "no_response" -> noResponseRows++;
                default -> {
                }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("# FIR408 0x56 Read-only Offset Probe");
        lines.add("");
        lines.add("- Read-only probe. No DSP setting write commands are sent.");
        lines.add("- Selector: " + String.format("0x%02X", selector));
        lines.add("- Offsets: `" + offsetsText + "`");
        lines.add("- Attempts per offset: " + attempts);
        lines.add("- Requests: " + rows.size());
        lines.add("- Data rows: " + dataRows + ", zero rows: " + zeroRows
                + ", partial rows: " + partialRows + ", FF rows: " + ffRows
                + ", no response rows: " + noResponseRows);
        lines.add("");
        lines.add("| Selector | Offset | Bytes | Non-FF bytes | Non-FF slots | Prefix | Float[0] | Float[1] | Class |");
        lines.add("| ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |");
        for (Cmd56SweepRow row : rows) {
            lines.add("| " + String.format("0x%02X", row.selector())
                    + " | " + row.offset()
                    + " | " + row.dataLen()
                    + " | " + row.nonFfBytes()
                    + " | " + row.nonFfFloatSlots()
                    + " | `" + row.prefixHex() + "`"
                    + " | " + row.firstFloat()
                    + " | " + row.secondFloat()
                    + " | " + row.classification()
                    + " |");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static List<Integer> parseOffsetList(String offsetsText) {
        if (offsetsText == null || offsetsText.trim().isEmpty()) {
            throw new IllegalArgumentException("Offset list must not be empty.");
        }
        String[] parts = offsetsText.trim().split("[,;\\s]+");
        List<Integer> offsets = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            int value = Integer.decode(part);
            if (value < 0 || value > 0xFFFF) {
                throw new IllegalArgumentException("Offset out of range 0..65535: " + value);
            }
            offsets.add(value);
        }
        if (offsets.isEmpty()) {
            throw new IllegalArgumentException("Offset list must contain at least one offset.");
        }
        return offsets;
    }

    private static String cmd56Csv(Cmd56SweepRow row) {
        return row.selector()
                + "," + row.offset()
                + ",\"" + row.requestHex() + "\""
                + ",\"" + row.responseHex() + "\""
                + "," + row.payloadLen()
                + "," + row.dataLen()
                + "," + row.nonFfBytes()
                + "," + row.nonFfFloatSlots()
                + "," + row.firstFloat()
                + "," + row.secondFloat()
                + "," + row.classification()
                + ",\"" + row.prefixHex() + "\"";
    }

    private static int countNonFfBytes(byte[] data) {
        int out = 0;
        for (byte b : data) {
            if ((b & 0xFF) != 0xFF) {
                out++;
            }
        }
        return out;
    }

    private static int countNonFfFloatSlots(byte[] data) {
        int out = 0;
        for (int offset = 0; offset <= data.length - 4; offset += 4) {
            if (!isAllFf(data, offset, 4)) {
                out++;
            }
        }
        return out;
    }

    private static boolean isAllZero(byte[] data) {
        if (data.length == 0) {
            return false;
        }
        for (byte b : data) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllFf(byte[] data, int offset, int length) {
        if (data.length < offset + length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if ((data[offset + i] & 0xFF) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    private static String float32LeString(byte[] data, int offset) {
        if (data.length < offset + 4 || isAllFf(data, offset, 4)) {
            return "";
        }
        int bits = (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
        float value = Float.intBitsToFloat(bits);
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return "";
        }
        return Float.toString(value);
    }

    private record Cmd56SweepRow(int selector,
                                 int offset,
                                 String requestHex,
                                 String responseHex,
                                 int payloadLen,
                                 int dataLen,
                                 int nonFfBytes,
                                 int nonFfFloatSlots,
                                 String firstFloat,
                                 String secondFloat,
                                 String classification,
                                 String prefixHex) {
    }}
