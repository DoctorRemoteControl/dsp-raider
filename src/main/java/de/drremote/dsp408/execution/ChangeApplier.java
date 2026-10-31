package de.drremote.dsp408.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.drremote.dsp408.dump.ReadOnlySnapshotReader;
import de.drremote.dsp408.parameters.ParameterProfiles;
import de.drremote.dsp408.parameters.ParameterValue;
import de.drremote.dsp408.planning.ChangePlan;
import de.drremote.dsp408.planning.ChangePlanner;
import de.drremote.dsp408.planning.ChangeRequest;
import de.drremote.dsp408.session.DspSession;
import de.drremote.dsp408.snapshot.Snapshot;
import de.drremote.dsp408.snapshot.SnapshotFiles;
import de.drremote.dsp408.snapshot.SnapshotFrames;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Explicit active application, separate from pure planning. No retries or rollback. */
public final class ChangeApplier {
    @FunctionalInterface
    public interface SnapshotReader { Snapshot read(DspSession session) throws Exception; }
    private final SnapshotReader reader;

    public ChangeApplier() {
        this(session -> SnapshotFiles.read(new ObjectMapper().readTree(
                new ReadOnlySnapshotReader().dump(session,null).toJson())));
    }

    public ChangeApplier(SnapshotReader reader) { this.reader = Objects.requireNonNull(reader); }

    /** Pure preflight, also used before CLI session creation. */
    public static ChangePlan prepare(Snapshot baseline, ChangeRequest request) throws IOException {
        validate(baseline);
        return ChangePlanner.plan(baseline.identity().version(),baseline.blockData(),request);
    }

    public ApplyResult apply(DspSession session, Snapshot baseline, ChangeRequest request) {
        Objects.requireNonNull(session);
        var entries = new ArrayList<ApplyResult.Entry>();
        int current = -1;
        try {
            var plan = prepare(baseline,request);
            entries.addAll(initialEntries(plan));
            if (plan.blockedCount() != 0) return blocked(plan);
            if (entries.isEmpty()) return result(true,baseline,entries,"");
            interrupted();
            Snapshot fresh = reader.read(session);
            checkState(baseline,entries,fresh);
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                if (entry.status() != ApplyResult.Status.NOT_ATTEMPTED) continue;
                current = index;
                checkState(baseline,entries,fresh);
                var change = entry.planned().change();
                interrupted();
                entries.set(index,new ApplyResult.Entry(entry.planned(),ApplyResult.Status.FAILED,true,null,"Write attempted; not yet verified"));
                byte[] payload = change.kind().writePayload(change.channelIndex(),change.value());
                var ack = SnapshotFrames.checked(session.sendPayload(payload,1,true,"apply_"+change.channel()+"_"+change.kind()),1,null);
                if (!Arrays.equals(ack.payload(),new byte[]{1,0,1,1})) throw new IOException("Unexpected DSP ACK payload");
                fresh = reader.read(session);
                validate(fresh);
                sameFirmware(baseline,fresh);
                var readback = reading(fresh,entry);
                if (readback.status() != ParameterValue.Status.CONFIRMED
                        || change.kind().valueToRaw(readback.value()) != change.writeRaw()) {
                    entries.set(index,new ApplyResult.Entry(entry.planned(),ApplyResult.Status.FAILED,true,readback,"Target readback mismatch"));
                    throw new IOException("Target readback mismatch for " + change.channel() + " " + change.kind());
                }
                entries.set(index,new ApplyResult.Entry(entry.planned(),ApplyResult.Status.APPLIED,true,readback,"ACK and target readback verified"));
                current = -1;
            }
            checkState(baseline,entries,fresh);
            return result(true,baseline,entries,"");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (current >= 0) {
                var entry = entries.get(current);
                entries.set(current,new ApplyResult.Entry(entry.planned(),ApplyResult.Status.FAILED,
                        entry.writeAttempted(),entry.readback(),failure.getMessage()));
            }
            return result(false,baseline,entries,failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public static ApplyResult blocked(ChangePlan plan) {
        if(plan.blockedCount()==0)throw new IllegalArgumentException("Plan has no blocked entries");
        return new ApplyResult(false,plan.deviceVersion(),initialEntries(plan),"Request contains blocked parameters; nothing was sent");
    }

    private static List<ApplyResult.Entry> initialEntries(ChangePlan plan) {
        return plan.entries().stream().map(entry -> new ApplyResult.Entry(entry,
                switch(entry.status()) {
                    case READY -> ApplyResult.Status.NOT_ATTEMPTED;
                    case UNCHANGED -> ApplyResult.Status.UNCHANGED;
                    case BLOCKED -> ApplyResult.Status.BLOCKED;
                },false,null,entry.reason())).toList();
    }

    private static void interrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Parameter application interrupted");
    }

    private static void validate(Snapshot snapshot) throws IOException {
        for (var block : snapshot.blocks().entrySet()) {
            SnapshotFrames.checked(block.getValue(),0x24,block.getKey());
            SnapshotFrames.configurationSize(snapshot.identity(),block.getKey(),block.getValue());
        }
    }

    private static ParameterValue reading(Snapshot snapshot, ApplyResult.Entry entry) {
        var change = entry.planned().change();
        return ParameterProfiles.forVersion(snapshot.identity().version()).orElseThrow()
                .definition(change.channelIndex(),change.kind()).read(snapshot.blockData());
    }

    private static void sameFirmware(Snapshot baseline, Snapshot fresh) throws IOException {
        if (!baseline.identity().version().strip().equalsIgnoreCase(fresh.identity().version().strip()))
            throw new IOException("Live firmware differs from baseline firmware");
    }

    private static void checkState(Snapshot baseline, List<ApplyResult.Entry> entries, Snapshot fresh) throws IOException {
        validate(fresh); sameFirmware(baseline,fresh);
        for (var entry : entries) {
            var change = entry.planned().change();
            var value = reading(fresh,entry);
            int expected = entry.status() == ApplyResult.Status.APPLIED ? change.writeRaw()
                    : change.kind().valueToRaw(entry.planned().before().value());
            if (value.status() != ParameterValue.Status.CONFIRMED || change.kind().valueToRaw(value.value()) != expected)
                throw new IOException("Live state differs from baseline/verified state for " + change.channel() + " " + change.kind());
        }
    }

    private static ApplyResult result(boolean ok, Snapshot baseline, List<ApplyResult.Entry> entries, String error) {
        return new ApplyResult(ok,baseline.identity().version(),entries,error);
    }
}
