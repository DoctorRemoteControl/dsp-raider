package de.drremote.dsp408.dspd;

import java.util.*;
import java.util.regex.Pattern;
import de.drremote.dsp408.parameters.ChannelNames;
import de.drremote.dsp408.parameters.ParameterKind;
import static de.drremote.dsp408.dspd.DspdAst.*;

/** Conservative analysis: only diagnose failures provable without running code. */
final class DspdAnalyzer {
    private enum Type {UNKNOWN,NUMBER,STRING,BOOLEAN,NULL}
    private record Value(Type type,Object constant) { }
    private static final Value UNKNOWN=new Value(Type.UNKNOWN,null);
    private static final Pattern PLACEHOLDER=Pattern.compile("\\$\\{([^}]+)}");
    private final String source;
    private final List<Diagnostic> diagnostics=new ArrayList<>();
    private int expressionDepth;
    private int pathDepth;
    private DspdAnalyzer(String source){this.source=source;}
    static List<Diagnostic> analyze(String source,Program program) {
        var analyzer=new DspdAnalyzer(source);var variables=new HashMap<String,Value>();variables.put("_",UNKNOWN);
        analyzer.statements(program.statements(),variables);return List.copyOf(analyzer.diagnostics);
    }
    private void statements(List<Statement> statements,Map<String,Value> variables) {
        for(var statement:statements) {
            switch(statement) {
                case Let let -> {var value=expression(let.value(),variables);variables.put(let.name(),value);variables.put("_",value);}
                case Invoke invoke -> variables.put("_",call(invoke.call(),variables,true));
                case Assertion assertion -> {expression(assertion.condition(),variables);variables.put("_",new Value(Type.BOOLEAN,true));}
                case Conditional conditional -> conditional(conditional,variables);
                case Loop loop -> {
                    numeric(expression(loop.start(),variables),loop.start().position(),Integer.MIN_VALUE,Integer.MAX_VALUE);
                    numeric(expression(loop.end(),variables),loop.end().position(),Integer.MIN_VALUE,Integer.MAX_VALUE);
                    var written=new HashSet<String>();assigned(loop.body(),written);written.add("_");
                    var body=new HashMap<>(variables);
                    for(String name:written)if(body.containsKey(name))body.put(name,UNKNOWN);
                    body.put(loop.variable(),new Value(Type.NUMBER,null));body.put("_",new Value(Type.NUMBER,null));
                    statements(loop.body(),body);
                    for(String name:written)if(body.containsKey(name))body.put(name,UNKNOWN);
                    body.remove(loop.variable());
                    if(variables.containsKey(loop.variable()))body.put(loop.variable(),variables.get(loop.variable()));
                    variables.clear();variables.putAll(body);
                }
            }
        }
    }
    private void assigned(List<Statement> statements,Set<String> names) {
        for(var statement:statements) {
            if(statement instanceof Let let)names.add(let.name());
            else if(statement instanceof Loop loop)assigned(loop.body(),names);
            else if(statement instanceof Conditional conditional){for(var branch:conditional.branches())assigned(branch.body(),names);assigned(conditional.otherwise(),names);}
        }
    }
    private void conditional(Conditional conditional,Map<String,Value> variables) {
        var paths=new ArrayList<Map<String,Value>>();
        for(var branch:conditional.branches()) {
            expression(branch.condition(),variables);
            var path=new HashMap<>(variables);statements(branch.body(),path);paths.add(path);
        }
        var otherwise=new HashMap<>(variables);statements(conditional.otherwise(),otherwise);paths.add(otherwise);
        var names=new HashSet<>(paths.getFirst().keySet());for(var path:paths)names.retainAll(path.keySet());
        variables.clear();
        for(String name:names) {
            Value first=paths.getFirst().get(name);
            variables.put(name,paths.stream().allMatch(p->Objects.equals(p.get(name),first))?first:UNKNOWN);
        }
    }
    private Value expression(Expression expression,Map<String,Value> variables) {
        if(++expressionDepth>128) {
            expressionDepth--;error("E2005","Maximum expression depth (128) exceeded",expression.position());return UNKNOWN;
        }
        try {return switch(expression) {
            case Atom atom -> atom(atom,variables);
            case Call call -> call(call,variables,false);
            case Binary binary -> {
                expression(binary.left(),variables);expression(binary.right(),variables);
                yield binary.operator().equals("+")?new Value(Type.STRING,null):new Value(Type.BOOLEAN,null);
            }
            case Unary unary -> {expression(unary.operand(),variables);yield new Value(Type.BOOLEAN,null);}
            case Index index -> {
                expression(index.base(),variables);numeric(expression(index.index(),variables),index.index().position(),0,Integer.MAX_VALUE);yield UNKNOWN;
            }
            case Member member -> {expression(member.base(),variables);yield UNKNOWN;}
            case Sequence sequence -> {
                var values=sequence.values().stream().map(e->expression(e,variables)).toList();
                boolean known=values.stream().allMatch(v->v.constant()!=null);
                yield new Value(Type.STRING,known?values.stream().map(v->v.constant().toString()).collect(java.util.stream.Collectors.joining(" ")):null);
            }
        };}finally{expressionDepth--;}
    }
    private Value atom(Atom atom,Map<String,Value> variables) {
        String text=atom.text();var placeholders=PLACEHOLDER.matcher(text);boolean interpolated=false;
        while(placeholders.find()) {
            interpolated=true;
            int dollar=atom.position().offset()+placeholders.start();
            reference(placeholders.group(1),SourcePosition.at(source,dollar),dollar+2,variables);
        }
        if(atom.quoted())return new Value(Type.STRING,interpolated?null:unescape(text.substring(1,text.length()-1)));
        if(text.startsWith("$") && text.length()>1 && !text.startsWith("${"))return reference(text.substring(1),atom.position(),atom.position().offset()+1,variables);
        if(variables.containsKey(text))return variables.get(text);
        String root=root(text);
        if(!root.equals(text)&&variables.containsKey(root))return reference(text,atom.position(),atom.position().offset(),variables);
        try {
            if(text.matches("0[xX][0-9A-Fa-f]+"))return new Value(Type.NUMBER,Integer.parseInt(text.substring(2),16));
            if(text.matches("-?\\d+"))return new Value(Type.NUMBER,Integer.parseInt(text));
            if(text.matches("-?\\d+\\.\\d+")) {
                double value=Double.parseDouble(text);if(!Double.isFinite(value))throw new NumberFormatException();
                return new Value(Type.NUMBER,value);
            }
        } catch(NumberFormatException invalid){error("E2004","Numeric literal outside supported range",atom.position());return UNKNOWN;}
        if(text.equalsIgnoreCase("true")||text.equalsIgnoreCase("false"))return new Value(Type.BOOLEAN,Boolean.parseBoolean(text));
        if(text.equalsIgnoreCase("null"))return new Value(Type.NULL,null);
        return new Value(Type.STRING,interpolated?null:text);
    }
    private Value reference(String path,SourcePosition position,int textOffset,Map<String,Value> variables) {
        if(++pathDepth>128){pathDepth--;error("E2005","Maximum path depth exceeded",position);return UNKNOWN;}
        try {
            int cursor=0;while(cursor<path.length()&&Character.isWhitespace(path.charAt(cursor)))cursor++;
            int start=cursor;while(cursor<path.length()&&identifierPart(path.charAt(cursor)))cursor++;
            String root=path.substring(start,cursor);
            if(!root.matches("[A-Za-z_][A-Za-z0-9_]*")){error("E2006","Invalid variable path",SourcePosition.at(source,textOffset+start));return UNKNOWN;}
            boolean exists=variables.containsKey(root);
            if(!exists)error("E2003","Variable not definitely defined: "+root,position);
            boolean simple=true;
            while(cursor<path.length()) {
                char c=path.charAt(cursor);simple=false;
                if(Character.isWhitespace(c)){cursor++;continue;}
                if(c=='.') {
                    int member=++cursor;while(cursor<path.length()&&identifierPart(path.charAt(cursor)))cursor++;
                    if(!path.substring(member,cursor).matches("[A-Za-z_][A-Za-z0-9_]*")){error("E2006","Expected property name",SourcePosition.at(source,textOffset+member));return UNKNOWN;}
                } else if(c=='[') {
                    int open=cursor++,begin=cursor,balance=1;
                    while(cursor<path.length()&&balance>0){char part=path.charAt(cursor);if(part=='[')balance++;if(part==']')balance--;if(balance>0)cursor++;}
                    if(balance!=0){error("E2006","Expected ']' in variable path",SourcePosition.at(source,textOffset+open));return UNKNOWN;}
                    while(begin<cursor&&Character.isWhitespace(path.charAt(begin)))begin++;
                    String index=path.substring(begin,cursor).stripTrailing();
                    if(index.isEmpty()){error("E2006","Missing index expression",SourcePosition.at(source,textOffset+begin));return UNKNOWN;}
                    var indexPosition=SourcePosition.at(source,textOffset+begin);
                    boolean quoted=index.startsWith("\"")&&index.endsWith("\"")&&index.length()>1;
                    numeric(atom(new Atom(index,quoted,indexPosition),variables),indexPosition,0,Integer.MAX_VALUE);
                    cursor++;
                } else {error("E2006","Invalid variable path",SourcePosition.at(source,textOffset+cursor));return UNKNOWN;}
            }
            return simple&&exists?variables.get(root):UNKNOWN;
        } finally {pathDepth--;}
    }
    private boolean identifierPart(char c){return c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_';}
    private String root(String path) {
        int end=0;while(end<path.length()&&(Character.isLetterOrDigit(path.charAt(end))||path.charAt(end)=='_'))end++;
        return path.substring(0,end);
    }
    private Value call(Call call,Map<String,Value> variables,boolean statement) {
        var signature=DspdBuiltins.find(call.name());var args=new ArrayList<Value>();
        for(var arg:call.arguments())args.add(expression(arg,variables));
        if(signature.isEmpty()||(!statement&&!signature.get().expression())) {
            error("E2001","Unknown function: "+call.name(),call.position());return UNKNOWN;
        }
        var function=signature.get();String name=function.name();
        if(!function.accepts(args.size(),call.legacy())) {
            String hint=call.legacy()&&args.isEmpty()&&variables.containsKey(call.name())?"; use $"+call.name()+" for the variable":"";
            error("E2002",name+": argument count "+args.size()+", expected "+function.expected()+hint,call.position());return UNKNOWN;
        }
        switch(name) {
            case "parameter","plan-change" -> parameterArguments(call,args);
            case "read-block" -> numeric(args.get(0),call.arguments().get(0).position(),Integer.MIN_VALUE,Integer.MAX_VALUE);
            case "read-blocks","read-config-blocks" -> {
                for(int i=0;i<args.size();i++)numeric(args.get(i),call.arguments().get(i).position(),0,255);
                if(args.size()==2)orderedRange(args.get(0),args.get(1),call.position());
            }
            case "read-save-config" -> {
                for(int i=1;i<args.size();i++)numeric(args.get(i),call.arguments().get(i).position(),0,255);
                if(args.size()==3)orderedRange(args.get(1),args.get(2),call.position());
            }
            case "sleep" -> numeric(args.get(0),call.arguments().get(0).position(),0,Long.MAX_VALUE,true);
            case "connect" -> {if(args.size()==4){numeric(args.get(1),call.arguments().get(1).position(),1,65535);numeric(args.get(3),call.arguments().get(3).position(),1,65535);}}
            case "gui-connect" -> {if(args.size()==2)numeric(args.get(1),call.arguments().get(1).position(),1,65535);}
            case "u8","u16le","u32le","at" -> numeric(args.get(1),call.arguments().get(1).position(),0,Integer.MAX_VALUE);
            case "ascii","slice" -> {numeric(args.get(1),call.arguments().get(1).position(),0,Integer.MAX_VALUE);numeric(args.get(2),call.arguments().get(2).position(),0,Integer.MAX_VALUE);}
            case "hex" -> {if(args.size()==3){numeric(args.get(1),call.arguments().get(1).position(),0,Integer.MAX_VALUE);numeric(args.get(2),call.arguments().get(2).position(),0,Integer.MAX_VALUE);}}
            case "read-block-payload","capture-frame","u16-series","writes-by-command","frames-by-command","responses-by-command","recent-writes" -> numericPositions(call,args,1,false);
            case "writes-by-command-and-channel","last-write-excluding","recent-writes-excluding" -> {for(int i=1;i<args.size();i++)numericPositions(call,args,i,false);}
            case "attach-session","reconnect-session","gui-end-capture" -> {for(int i=0;i<args.size();i++)numericPositions(call,args,i,true);}
            case "write","send-payload","tx" -> {
                if(call.legacy())legacyWrite(call,args);
                else if(args.size()==2)numericPositions(call,args,1,false);
            }
            case "fir408-upload-test-fir" -> numericPositions(call,args,0,false);
            case "fir408-cmd56-readonly-sweep" -> {for(int i=1;i<args.size();i++)numericPositions(call,args,i,false);}
            case "fir408-cmd56-readonly-offsets" -> {numericPositions(call,args,1,false);if(args.size()==4)numericPositions(call,args,3,false);}
            case "gui-capture","gui-action-capture" -> {if(args.stream().filter(v->v.type()==Type.NUMBER).count()>3)error("E2004","Expected at most three numeric capture timing values",call.position());}
            default -> { }
        }
        return switch(name) {
            case "print" -> args.getFirst();
            case "len","u8","u16le","u32le","cmd","read-block-index","capture-count","capture-write-count","capture-response-count" -> new Value(Type.NUMBER,null);
            case "hex","ascii","upper","lower","trim","join","replace","payload-hex","payload-ascii","diff-report" -> new Value(Type.STRING,null);
            default -> UNKNOWN;
        };
    }
    private void numericPositions(Call call,List<Value> args,int index,boolean wide) {
        numeric(args.get(index),call.arguments().get(index).position(),wide?Long.MIN_VALUE:Integer.MIN_VALUE,wide?Long.MAX_VALUE:Integer.MAX_VALUE,wide);
    }
    private void parameterArguments(Call call,List<Value> args) {
        try {
            if(args.get(1).constant()!=null)ChannelNames.index(args.get(1).constant().toString());
            if(args.get(2).constant()!=null) {
                var kind=ParameterKind.valueOf(args.get(2).constant().toString().strip().toUpperCase(Locale.ROOT));
                if(args.size()==4 && args.get(3).type()!=Type.UNKNOWN && args.get(3).constant()!=null) {
                    Object value=args.get(3).constant();
                    double scalar;
                    if(value instanceof Number number)scalar=number.doubleValue();
                    else if(kind==ParameterKind.MUTE && value instanceof Boolean bool)scalar=bool?1:0;
                    else throw new IllegalArgumentException("Expected a number, or a boolean for mute");
                    kind.valueToRaw(scalar);
                }
            }
        } catch(IllegalArgumentException invalid) {error("E2004","Invalid parameter argument: "+invalid.getMessage(),call.position());}
    }
    private void legacyWrite(Call call,List<Value> args) {
        for(int index=0;index<call.arguments().size();index++) {
            if(call.arguments().get(index) instanceof Atom atom && atom.text().equalsIgnoreCase("expect")) {
                if(index==0 || index+2!=args.size()) {
                    error("E2004","Expected a payload followed by expect and one command",atom.position());
                    return;
                }
                numericPositions(call,args,index+1,false);
                return;
            }
        }
    }
    private void orderedRange(Value start,Value end,SourcePosition position) {
        try {Long a=integer(start,false),b=integer(end,false);if(a!=null&&b!=null&&b<a)error("E2004","Block range ends before its start",position);}
        catch(NumberFormatException ignored){/* Individual argument diagnostic already emitted. */}
    }
    private void numeric(Value value,SourcePosition position,double minimum,double maximum) {
        numeric(value,position,minimum,maximum,false);
    }
    private void numeric(Value value,SourcePosition position,double minimum,double maximum,boolean wide) {
        if(value.type()==Type.UNKNOWN || value.constant()==null && (value.type()==Type.NUMBER||value.type()==Type.STRING))return;
        double number;
        try {
            Long converted=integer(value,wide);if(converted==null)return;number=converted;
            // Runtime numeric arguments use Number.intValue / integer parsing.
            if(!Double.isFinite(number)||number<minimum||number>maximum)throw new NumberFormatException();
        } catch(NumberFormatException invalid){error("E2004","Expected numeric value in range "+minimum+".."+maximum+"",position);}
    }
    private Long integer(Value value,boolean wide) {
        if(value.type()==Type.UNKNOWN||value.constant()==null&&(value.type()==Type.NUMBER||value.type()==Type.STRING))return null;
        if(value.constant() instanceof Number n)return wide?n.longValue():(long)n.intValue();
        if(value.constant() instanceof Boolean b&&!wide)return b?1L:0L;
        if(value.constant() instanceof String s)return wide?Long.parseLong(s):s.startsWith("0x")||s.startsWith("0X")?(long)Integer.parseInt(s.substring(2),16):(long)Integer.parseInt(s);
        throw new NumberFormatException();
    }
    private String unescape(String text) {
        var result=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\\'&&i+1<text.length()) {
                c=text.charAt(++i);
                switch(c){case 'n'->result.append('\n');case 'r'->result.append('\r');case 't'->result.append('\t');case '"','\\'->result.append(c);default->result.append('\\').append(c);}
            } else result.append(c);
        }
        return result.toString();
    }
    private void error(String code,String message,SourcePosition position){diagnostics.add(new Diagnostic(code,message,position));}
}
