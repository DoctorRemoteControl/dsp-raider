package de.drremote.dsp408.script;

import de.drremote.dsp408.dspd.DspdAst.*;
import de.drremote.dsp408.dspd.DspdBuiltins;

import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Walks a checked AST. No statements or expressions are reparsed as script text. */
final class DspdExecutor {
    private final String sourceName;
    private final ScriptRuntime state;
    private final ScriptValueResolver resolver;
    private final ScriptFunctions functions;
    private final PrintStream output;

    DspdExecutor(String sourceName, ScriptRuntime state, PrintStream output) {
        this.sourceName = sourceName;
        this.state = state;
        this.resolver = new ScriptValueResolver(state);
        this.functions = new ScriptFunctions(state,resolver);
        this.output = output;
    }

    void execute(Program program) throws Exception { statements(program.statements()); }

    private void statements(List<Statement> statements) throws Exception {
        for (var statement : statements) {
            try {
                interrupted();
                switch (statement) {
                    case Let let -> {
                        Object value = expression(let.value());
                        state.variables.put(let.name(),value);
                        state.variables.put("_",value);
                    }
                    case Invoke invoke -> {
                        Object value = call(invoke.call());
                        String name = DspdBuiltins.normalizeFunctionName(invoke.call().name());
                        if (invoke.call().legacy() && name.equals("status")) output.println(ScriptFunctions.displayValue(value));
                        if (!(invoke.call().legacy() && List.of("disconnect","gui-disconnect").contains(name)))
                            state.variables.put("_",value);
                    }
                    case Assertion assertion -> {
                        if (!ScriptFunctions.truthy(expression(assertion.condition())))
                            throw new IllegalStateException("Assertion failed");
                        state.variables.put("_",true);
                    }
                    case Conditional conditional -> {
                        boolean matched = false;
                        for (var branch : conditional.branches()) {
                            if (ScriptFunctions.truthy(expression(branch.condition()))) {
                                statements(branch.body()); matched = true; break;
                            }
                        }
                        if (!matched) statements(conditional.otherwise());
                    }
                    case Loop loop -> loop(loop);
                }
            } catch (DspdExecutionException failure) { throw failure;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new DspdExecutionException(sourceName,statement.position(),failure);
            }
        }
    }

    private void loop(Loop loop) throws Exception {
        int start = ScriptValueResolver.toInt(expression(loop.start()));
        int end = ScriptValueResolver.toInt(expression(loop.end()));
        boolean existed = state.variables.containsKey(loop.variable());
        Object previous = state.variables.get(loop.variable());
        try {
            long step = start <= end ? 1 : -1;
            for (long index = start; step > 0 ? index <= end : index >= end; index += step) {
                interrupted();
                state.variables.put(loop.variable(),(int)index);
                state.variables.put("_",(int)index);
                statements(loop.body());
            }
        } finally {
            if (existed) state.variables.put(loop.variable(),previous);
            else state.variables.remove(loop.variable());
        }
    }

    private Object expression(Expression expression) throws Exception {
        try {
            return switch (expression) {
                case Atom atom -> resolver.resolveValue(atom.text());
                case Call call -> call(call);
                case Member member -> resolver.resolveProperty(expression(member.base()),member.name());
                case Index index -> ScriptValueResolver.applySingleIndex(expression(index.base()),
                        ScriptValueResolver.toInt(expression(index.index())));
                case Unary unary -> !ScriptFunctions.truthy(expression(unary.operand()));
                case Binary binary -> binary(binary);
                case Sequence sequence -> {
                    var values = new ArrayList<String>();
                    for (var value : sequence.values()) values.add(ScriptValueResolver.stringify(expression(value)));
                    yield String.join(" ",values);
                }
            };
        } catch (DspdExecutionException failure) { throw failure;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new DspdExecutionException(sourceName,expression.position(),failure);
        }
    }

    private Object call(Call call) throws Exception {
        String name = DspdBuiltins.normalizeFunctionName(call.name());
        List<ScriptArgument> args = call.arguments().stream()
                .map(value -> (ScriptArgument) () -> expression(value)).toList();
        if (name.equals("print")) {
            Object value = args.getFirst().evaluate();
            output.println(ScriptFunctions.displayValue(value));
            return value;
        }
        if (ScriptParameters.supports(name)) return ScriptParameters.invoke(name,args,state);
        if (call.legacy() && List.of("write","send-payload","tx").contains(name)) {
            var payload = new ArrayList<String>();
            Integer expected = null;
            for (int i = 0; i < args.size(); i++) {
                if (call.arguments().get(i) instanceof Atom atom && atom.text().equalsIgnoreCase("expect")) {
                    if (i + 2 != args.size()) throw new IllegalArgumentException("Expected one command after expect");
                    expected = ScriptValueResolver.toInt(args.get(++i).evaluate()); break;
                }
                payload.add(ScriptValueResolver.stringify(args.get(i).evaluate()));
            }
            byte[] bytes=de.drremote.dsp408.util.HexUtil.hexToBytes(String.join(" ",payload));
            if(bytes.length==0)throw new IllegalArgumentException("Missing payload for send-payload");
            return state.requireClient().sendPayload(bytes,
                    expected,expected != null,"script_send_payload");
        }
        if (call.legacy() && name.equals("bytes")) {
            var parts = new ArrayList<String>();
            for (var arg : args) parts.add(ScriptValueResolver.stringify(arg.evaluate()));
            return de.drremote.dsp408.util.HexUtil.hexToBytes(String.join(" ",parts));
        }
        return functions.invoke(name,args);
    }

    private Object binary(Binary binary) throws Exception {
        Object left = expression(binary.left());
        if (binary.operator().equals("and")) return ScriptFunctions.truthy(left) && ScriptFunctions.truthy(expression(binary.right()));
        if (binary.operator().equals("or")) return ScriptFunctions.truthy(left) || ScriptFunctions.truthy(expression(binary.right()));
        Object right = expression(binary.right());
        return switch (binary.operator()) {
            case "==" -> ScriptFunctions.valueEquals(left,right);
            case "!=" -> !ScriptFunctions.valueEquals(left,right);
            // The legacy fallback treats plus-separated words as a literal phrase.
            case "+" -> ScriptValueResolver.stringify(left) + " + " + ScriptValueResolver.stringify(right);
            case ">" -> compare(left,right) > 0;
            case ">=" -> compare(left,right) >= 0;
            case "<" -> compare(left,right) < 0;
            case "<=" -> compare(left,right) <= 0;
            default -> throw new IllegalArgumentException("Unknown operator: " + binary.operator());
        };
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(),b.doubleValue());
        if (left instanceof String a && right instanceof String b) return a.compareTo(b);
        if (left instanceof Boolean a && right instanceof Boolean b) return Boolean.compare(a,b);
        throw new IllegalArgumentException("Ordered comparison requires matching non-null types");
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Script interrupted");
    }
}
