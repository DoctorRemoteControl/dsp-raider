package de.drremote.dsp408.snapshot;

import de.drremote.dsp408.parameters.BlockData;
import de.drremote.dsp408.parameters.ParameterDefinition;
import de.drremote.dsp408.parameters.ParameterProfile;
import de.drremote.dsp408.parameters.ParameterProfiles;
import de.drremote.dsp408.parameters.ParameterValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Offline interpretation of the first three modeled channel parameters. */
final class SnapshotParameters {
    private SnapshotParameters() { }

    static String describe(Snapshot snapshot) {
        var profile=ParameterProfiles.forVersion(snapshot.identity().version());
        if(profile.isEmpty())return "No confirmed parameter profile for this firmware; values unknown.\n\n";
        StringBuilder report=new StringBuilder("## Gain / Mute / Delay\n\n")
                .append("CONFIRMED = proven mapping; INFERRED = unconfirmed candidate; UNKNOWN = missing; INVALID = invalid raw value.\n")
                .append("Offsets refer to block data excluding the 5-byte response header. Mute raw values show the entire mask byte.\n\n")
                .append("| Channel | Parameter | Value | Status | Raw value | Read position | Write command | Read evidence |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for(var value:profile.get().values(snapshot.blockData())) {
            var definition=value.definition();
            var location=definition.readLocation();
            String position=location==null ? "unknown" : "0x%02X+0x%02X/%d%s".formatted(
                    location.block(),location.dataOffset(),location.width(),
                    location.mask()==0 ? "" : " mask=0x%02X".formatted(location.mask()));
            report.append("| %s | %s | %s | %s | %s | %s | 0x%02X | %s (%s) |\n".formatted(
                    definition.channel(),definition.kind().label(),value.display(),value.status(),
                    value.raw()==null ? "--" : value.raw(),position,definition.kind().command(),
                    definition.readEvidence().source(),definition.readEvidence().status()));
        }
        return report.append("\n").toString();
    }

    static String diff(Snapshot before,Snapshot after) {
        var leftProfile=ParameterProfiles.forVersion(before.identity().version());
        var rightProfile=ParameterProfiles.forVersion(after.identity().version());
        BlockData left=before.blockData(),right=after.blockData();
        Map<Integer,Integer> coverage=new HashMap<>();
        StringBuilder report=new StringBuilder("\nParameter changes (Gain/Mute/Delay):\n");
        int changed=0;
        if(leftProfile.isPresent() && rightProfile.isPresent()
                && leftProfile.get().version().equals(rightProfile.get().version())) {
            ParameterProfile profile=leftProfile.get();
            for(ParameterDefinition definition:profile.definitions()) {
                var a=definition.read(left);var b=definition.read(right);
                if(a.status()==ParameterValue.Status.CONFIRMED && b.status()==ParameterValue.Status.CONFIRMED) {
                    var location=definition.readLocation();
                    for(int index=0;index<location.width();index++) {
                        int key=key(location.block(),location.dataOffset()+index);
                        coverage.merge(key,location.mask()==0 ? 255 : location.mask(),(x,y)->x|y);
                    }
                }
                if((a.status()==ParameterValue.Status.CONFIRMED || b.status()==ParameterValue.Status.CONFIRMED)
                        && (a.status()!=b.status() || !Objects.equals(a.value(),b.value()))) {
                    report.append("%s %s: %s -> %s\n".formatted(definition.channel(),definition.kind().label(),a.display(),b.display()));
                    changed++;
                }
            }
        } else {
            report.append("No shared confirmed parameter profile; no semantic mapping.\n");
        }
        report.append("parameter_changes=%d\n\nUnmapped raw changes (outside confirmed Gain/Mute/Delay fields):\n".formatted(changed));
        int unmapped=0;
        var indices=new TreeSet<>(before.blocks().keySet());indices.addAll(after.blocks().keySet());
        for(int block:indices) {
            int leftSize=before.blocks().containsKey(block) ? before.blocks().get(block).payloadLen()-5 : 0;
            int rightSize=after.blocks().containsKey(block) ? after.blocks().get(block).payloadLen()-5 : 0;
            for(int offset=0;offset<Math.max(leftSize,rightSize);offset++) {
                var a=left.byteAt(block,offset);var b=right.byteAt(block,offset);
                int mask=a.isEmpty() || b.isEmpty() ? 255 : a.getAsInt()^b.getAsInt();
                mask&=~coverage.getOrDefault(key(block,offset),0);
                if(mask==0)continue;
                report.append("unmapped block 0x%02X offset 0x%04X mask 0x%02X\n".formatted(block,offset,mask));
                unmapped++;
            }
        }
        return report.append("unmapped_bytes=%d\n\nRaw comparison (all changes):\n".formatted(unmapped)).toString();
    }

    private static int key(int block,int offset) { return block*256+offset; }
}
