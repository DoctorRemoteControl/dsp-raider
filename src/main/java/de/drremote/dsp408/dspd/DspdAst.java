package de.drremote.dsp408.dspd;

import java.util.List;

/** Immutable syntax tree. Nodes retain positions in the original source. */
public final class DspdAst {
    private DspdAst() { }
    public record Program(List<Statement> statements) {public Program{statements=List.copyOf(statements);}}
    public sealed interface Node {SourcePosition position();}
    public sealed interface Expression extends Node permits Atom,Call,Binary,Unary,Index,Member,Sequence { }
    public sealed interface Statement extends Node permits Let,Invoke,Assertion,Conditional,Loop { }
    public record Atom(String text,boolean quoted,SourcePosition position) implements Expression { }
    public record Call(String name,List<Expression> arguments,boolean legacy,SourcePosition position) implements Expression {
        public Call{arguments=List.copyOf(arguments);}
    }
    public record Binary(String operator,Expression left,Expression right,SourcePosition position) implements Expression { }
    public record Unary(String operator,Expression operand,SourcePosition position) implements Expression { }
    public record Index(Expression base,Expression index,SourcePosition position) implements Expression { }
    public record Member(Expression base,String name,SourcePosition position) implements Expression { }
    public record Sequence(List<Expression> values,SourcePosition position) implements Expression {public Sequence{values=List.copyOf(values);}}
    public record Let(String name,Expression value,SourcePosition position) implements Statement { }
    public record Invoke(Call call,SourcePosition position) implements Statement { }
    public record Assertion(Expression condition,SourcePosition position) implements Statement { }
    public record Branch(Expression condition,List<Statement> body) {public Branch{body=List.copyOf(body);}}
    public record Conditional(List<Branch> branches,List<Statement> otherwise,SourcePosition position) implements Statement {
        public Conditional{branches=List.copyOf(branches);otherwise=List.copyOf(otherwise);}
    }
    public record Loop(String variable,Expression start,Expression end,List<Statement> body,SourcePosition position) implements Statement {
        public Loop{body=List.copyOf(body);}
    }
}
