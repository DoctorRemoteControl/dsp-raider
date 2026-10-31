package de.drremote.dsp408.script;

import de.drremote.dsp408.execution.ChangeApplier;
import de.drremote.dsp408.execution.ChangeApplicationException;
import de.drremote.dsp408.parameters.ChannelNames;
import de.drremote.dsp408.parameters.ParameterKind;
import de.drremote.dsp408.parameters.ParameterProfiles;
import de.drremote.dsp408.planning.ChangePlanner;
import de.drremote.dsp408.planning.ChangeRequest;
import de.drremote.dsp408.planning.ChangeRequestFiles;
import de.drremote.dsp408.planning.ParameterChange;
import de.drremote.dsp408.snapshot.Snapshot;
import de.drremote.dsp408.snapshot.SnapshotFiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Checked-language domain operations; planning and reads of saved files stay offline. */
final class ScriptParameters {
    private static final Set<String> NAMES=Set.of("load-snapshot","parameter","plan-change","apply-changes");
    private ScriptParameters() { }
    static boolean supports(String name) { return NAMES.contains(name); }

    static Object invoke(String name, List<ScriptArgument> args, ScriptRuntime runtime) throws Exception {
        if(name.equals("load-snapshot"))return SnapshotFiles.read(Path.of(ScriptValueResolver.stringify(args.get(0).evaluate())));
        Object value=args.get(0).evaluate();
        if(!(value instanceof Snapshot snapshot))throw new IllegalArgumentException("Expected a loaded snapshot");
        if(name.equals("apply-changes")) {
            var request=ChangeRequestFiles.read(Path.of(ScriptValueResolver.stringify(args.get(1).evaluate())));
            var plan=ChangeApplier.prepare(snapshot,request);
            if(plan.blockedCount()!=0)throw new IllegalArgumentException("Request contains blocked parameters; nothing was sent");
            var result=new ChangeApplier().apply(runtime.requireClient(),snapshot,request);
            if(!result.ok())throw new ChangeApplicationException(result);
            return result;
        }
        int channel=ChannelNames.index(ScriptValueResolver.stringify(args.get(1).evaluate()));
        var kind=ParameterKind.valueOf(ScriptValueResolver.stringify(args.get(2).evaluate()).strip().toUpperCase(Locale.ROOT));
        if(name.equals("parameter"))return ParameterProfiles.forVersion(snapshot.identity().version())
                .orElseThrow(()->new IllegalArgumentException("No confirmed parameter profile for snapshot firmware"))
                .definition(channel,kind).read(snapshot.blockData());
        Object target=args.get(3).evaluate();
        double scalar;
        if(target instanceof Number number)scalar=number.doubleValue();
        else if(kind==ParameterKind.MUTE && target instanceof Boolean bool)scalar=bool?1:0;
        else throw new IllegalArgumentException("Expected a number, or a boolean for mute");
        var change=new ParameterChange(channel,kind,scalar);
        return ChangePlanner.plan(snapshot.identity().version(),snapshot.blockData(),
                new ChangeRequest(snapshot.identity().version(),List.of(change))).entries().getFirst();
    }
}
