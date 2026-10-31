package de.drremote.dsp408.dspd;

import java.util.*;

/** Pure names and call signatures; no session, IO or runtime evaluation. */
public final class DspdBuiltins {
    public record Signature(String name,Set<Integer> counts,int minimum,int maximum,boolean expression) {
        public Signature {counts=Set.copyOf(counts);}
        public boolean accepts(int count,boolean legacy) {
            if(legacy && Set.of("gui-capture","gui-action-capture").contains(name))return count>=1 && count<=3;
            if(legacy && Set.of("bytes","send-payload","tx","write").contains(name))return count>=1;
            return counts.isEmpty() ? count>=minimum && count<=maximum : counts.contains(count);
        }
        public String expected(){return counts.isEmpty()?minimum+".."+(maximum==Integer.MAX_VALUE?"*":maximum):counts.stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining("/"));}
    }
    private static final Map<String,Signature> FUNCTIONS=new LinkedHashMap<>();
    static {
        fixed(0,"status disconnect gui-disconnect reset-session ensure-session clear-frames handshake handshake-init device-info system-info gui-begin-capture");
        fixed(1,"login read-block read-block-index cmd payload raw payload-hex payload-ascii assembled-data decode-fir408-config fir408-safe-pings capture-count capture-write-count capture-response-count first-write last-write first-response last-response writes responses payload-series changing-offsets-across-writes len upper lower trim bytes sleep");
        fixed(2,"read-blocks read-block-payload diff-bytes diff-u16le diff-report changed-offsets save-text save-capture-read-blocks save-read-blocks capture-frame writes-by-command frames-by-command responses-by-command recent-writes u16-series contains starts-with ends-with at split u8 u16le u32le");
        fixed(3,"save-diff-report writes-by-command-and-channel replace slice");
        choices("connect",0,4);choices("gui-connect",0,2);choices("attach-session",0,2);
        choices("fir408-cmd56-readonly-sweep",1,6);choices("fir408-cmd56-readonly-offsets",3,4);
        choices("fir408-upload-test-fir",2,3);choices("ascii",3,4);choices("hex",1,3);
        choices("send-payload",1,2);choices("tx",1,2);choices("write",1,2);
        range("reconnect-session",0,2);range("pause",0,1);range("join",1,2);
        range("read-config-blocks",0,2);range("read-save-config",1,3);
        range("gui-capture",0,4);range("gui-action-capture",0,4);range("gui-end-capture",0,2);
        range("last-write-excluding",1,Integer.MAX_VALUE);range("recent-writes-excluding",2,Integer.MAX_VALUE);
        FUNCTIONS.put("print",new Signature("print",Set.of(1),1,1,false));
        fixed(1,"load-snapshot");
        fixed(2,"apply-changes");
        fixed(3,"parameter");
        fixed(4,"plan-change");
    }
    private DspdBuiltins() { }
    private static void fixed(int count,String names){for(String name:names.split(" "))choices(name,count);}
    private static void choices(String name,Integer...counts){FUNCTIONS.put(name,new Signature(name,Set.of(counts),0,0,true));}
    private static void range(String name,int min,int max){FUNCTIONS.put(name,new Signature(name,Set.of(),min,max,true));}
    public static Optional<Signature> find(String name){return Optional.ofNullable(FUNCTIONS.get(normalizeFunctionName(name)));}
    public static Set<String> names(){return Set.copyOf(FUNCTIONS.keySet());}
    public static boolean isExpressionStarter(String name){return find(name).map(Signature::expression).orElse(false);}
    public static String normalizeFunctionName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "loadsnapshot" -> "load-snapshot";
            case "planchange" -> "plan-change";
            case "applychanges" -> "apply-changes";
            case "readblock" -> "read-block";
            case "readblocks" -> "read-blocks";
            case "readconfigblocks" -> "read-config-blocks";
            case "readsaveconfig" -> "read-save-config";
            case "decodefir408config" -> "decode-fir408-config";
            case "fir408safepings" -> "fir408-safe-pings";
            case "fir408cmd56readonlysweep" -> "fir408-cmd56-readonly-sweep";
            case "fir408cmd56readonlyoffsets" -> "fir408-cmd56-readonly-offsets";
            case "fir408uploadtestfir" -> "fir408-upload-test-fir";
            case "readblockindex" -> "read-block-index";
            case "readblockpayload" -> "read-block-payload";
            case "guiconnect" -> "gui-connect";
            case "guidisconnect" -> "gui-disconnect";
            case "ensuresession" -> "ensure-session";
            case "resetsession" -> "reset-session";
            case "clearframes" -> "clear-frames";
            case "attachsession" -> "attach-session";
            case "reconnectsession" -> "reconnect-session";
            case "pauseforuser" -> "pause";
            case "handshakeinit" -> "handshake-init";
            case "deviceinfo" -> "device-info";
            case "systeminfo" -> "system-info";
            case "sendpayload" -> "send-payload";
            case "payloadhex" -> "payload-hex";
            case "payloadascii" -> "payload-ascii";
            case "diffbytes" -> "diff-bytes";
            case "diffu16le" -> "diff-u16le";
            case "diffreport" -> "diff-report";
            case "changedoffsets" -> "changed-offsets";
            case "savetext" -> "save-text";
            case "savediffreport" -> "save-diff-report";
            case "savecapturereadblocks" -> "save-capture-read-blocks";
            case "savereadblocks" -> "save-read-blocks";
            case "assembleddata" -> "assembled-data";
            case "guicapture" -> "gui-capture";
            case "guiactioncapture" -> "gui-action-capture";
            case "guibegincapture" -> "gui-begin-capture";
            case "guiendcapture" -> "gui-end-capture";
            case "capturecount" -> "capture-count";
            case "capturewritecount" -> "capture-write-count";
            case "captureresponsecount" -> "capture-response-count";
            case "captureframe" -> "capture-frame";
            case "firstwrite" -> "first-write";
            case "lastwrite" -> "last-write";
            case "firstresponse" -> "first-response";
            case "lastresponse" -> "last-response";
            case "lastwriteexcluding" -> "last-write-excluding";
            case "writesbycommand" -> "writes-by-command";
            case "framesbycommand" -> "frames-by-command";
            case "responsesbycommand" -> "responses-by-command";
            case "writesbycommandandchannel" -> "writes-by-command-and-channel";
            case "recentwrites" -> "recent-writes";
            case "recentwritesexcluding" -> "recent-writes-excluding";
            case "payloadseries" -> "payload-series";
            case "u16series" -> "u16-series";
            case "changingoffsetsacrosswrites" -> "changing-offsets-across-writes";
            case "startswith" -> "starts-with";
            case "endswith" -> "ends-with";
            default -> trimmed;
        };
    }

}
