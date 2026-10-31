package de.drremote.dsp408.parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Version-specific catalog, normalized from preserved protocol evidence. */
public final class ParameterProfiles {
    private static final String BASE="tracks-dsp-raider/DspLib-408.json#/parameters/";
    private static final String FIR="tracks-dsp-raider/DspLib-408-fir.json#/parameters/";
    private static final String FIR_MUTE="DSP408Controller/dsp408-parent/docs/protocol-spec/DspLib-408-fir.json#/parameters/mute/read/";
    private static final ParameterProfile DSP_PROFILE=build(false);
    private static final ParameterProfile FIR_PROFILE=build(true);
    private ParameterProfiles() { }

    public static Optional<ParameterProfile> forVersion(String version) {
        if(version==null)return Optional.empty();
        return switch(version.strip().toUpperCase(Locale.ROOT)) {
            case "DSP408 V0104" -> Optional.of(DSP_PROFILE);
            case "FIR408 V0113P" -> Optional.of(FIR_PROFILE);
            default -> Optional.empty();
        };
    }

    private static ParameterProfile build(boolean fir) {
        int[] gainBlocks=fir ? new int[]{2,5,8,11,13,15,17,19,22,24,26,28} : new int[]{2,5,8,11,13,15,17,19,21,23,25,28};
        int[] gainOffsets=fir ? new int[]{46,34,22,10,18,26,34,42,0,8,16,24} : new int[]{48,38,28,18,22,26,30,34,38,42,46,0};
        int[] delayBlocks=fir ? new int[]{3,5,8,11,13,15,17,19,22,24,26,28} : new int[]{3,5,8,11,13,15,17,19,21,23,26,28};
        int[] delayOffsets=fir ? new int[]{0,38,26,14,22,30,38,46,4,12,20,28} : new int[]{2,42,32,22,26,30,34,38,42,46,0,4};
        List<ParameterDefinition> definitions=new ArrayList<>();
        String source=fir ? FIR : BASE;
        for(int channel=0;channel<12;channel++) {
            String name=channel<4 ? "In"+(char)('A'+channel) : "Out"+(channel-3);
            String group=channel<4 ? "inputs" : "outputs";
            for(ParameterKind kind:ParameterKind.values()) {
                ReadLocation location;
                Evidence read;
                if(kind==ParameterKind.GAIN) {
                    location=new ReadLocation(gainBlocks[channel],gainOffsets[channel],2,0);
                    read=evidence(true,source+"gain/read/"+(channel<4?"input_locations/":"output_locations/")+(channel<4?channel:channel-4),
                            fir?"verified_live_fir408_2026_07_16":"observed");
                } else if(kind==ParameterKind.DELAY) {
                    location=new ReadLocation(delayBlocks[channel],delayOffsets[channel],2,0);
                    boolean confirmed=fir || (channel!=1 && channel!=2);
                    read=evidence(confirmed,source+"delay/read/full_channel_read_map/channels/"+name,confirmed?"observed":"predicted_strong");
                } else {
                    int mask=1<<(channel<4?channel:channel-4);
                    location=new ReadLocation(28,fir?(channel<4?32:34):(channel<4?8:10),1,mask);
                    boolean confirmed=fir ? channel==3 || channel==11 : channel<8;
                    read=evidence(confirmed,(fir?FIR_MUTE:BASE+"mute/read/")+group,
                            fir?(confirmed?"observed_live_fir408_2026_08_06":"inherited_bits_not_individually_confirmed")
                                    :(confirmed?"observed":"inferred_from_bitmask"));
                }
                definitions.add(new ParameterDefinition(channel,kind,location,read,
                        evidence(true,source+kind.name().toLowerCase(Locale.ROOT)+"/write","observed_payload_layout")));
            }
        }
        return new ParameterProfile(fir?"FIR408 V0113P":"DSP408 V0104",definitions);
    }

    private static Evidence evidence(boolean confirmed,String source,String status) {
        return new Evidence(confirmed?Evidence.Level.CONFIRMED:Evidence.Level.INFERRED,source,status);
    }
}
