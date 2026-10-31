package de.drremote.dsp408.dspd;

import java.util.ArrayList;
import java.util.List;
import static de.drremote.dsp408.dspd.Token.Kind.*;
import static de.drremote.dsp408.dspd.DspdAst.*;

/** Recursive descent parser for modern calls and the existing legacy forms. */
public final class DspdParser {
    public record Result(Program program,List<Diagnostic> diagnostics) {public Result{diagnostics=List.copyOf(diagnostics);}}
    private final List<Token> tokens;
    private final String source;
    private int cursor,depth,softDepth;
    private DspdParser(List<Token> tokens,String source){this.tokens=tokens;this.source=source;}
    public static Result parse(String source) {
        var lexed=DspdLexer.lex(source);
        if(!lexed.diagnostics().isEmpty())return new Result(null,lexed.diagnostics());
        try {var parser=new DspdParser(lexed.tokens(),source);return new Result(new Program(parser.statements(false,false)),List.of());}
        catch(Failure failure){return new Result(null,List.of(failure.diagnostic));}
    }
    private List<Statement> statements(boolean braces,boolean legacy) {
        var out=new ArrayList<Statement>();separators();
        while(!at(EOF)) {
            if(braces && at(RBRACE) || legacy && (word("end")||word("else")))break;
            Statement statement=statement();out.add(statement);
            if(!(statement instanceof Conditional)&&!(statement instanceof Loop)
                    &&!at(SEPARATOR)&&!at(EOF)&&!at(RBRACE)&&!word("end")&&!word("else"))throw fail("Expected statement separator");
            separators();
        }
        return List.copyOf(out);
    }
    private Statement statement() {
        enter();try {
            Token first=peek();
            if(at(RBRACE)||word("end")||word("else"))throw fail("Unexpected block ending or else");
            if(takeWord("let")) {
                Token name=require(WORD,"Expected variable name");
                if(!name.text().matches("[A-Za-z_][A-Za-z0-9_]*"))throw failure(name,"Invalid variable name");
                require(EQUALS,"Expected '=' after variable name");
                return new Let(name.text(),expression(),first.position());
            }
            if(takeWord("assert"))return new Assertion(expression(),first.position());
            if(takeWord("if"))return conditional(first);
            if(takeWord("for"))return loop(first);
            Token name=require(WORD,"Expected command");
            Call call=at(LPAREN)?call(name):legacyCall(name);
            return new Invoke(call,first.position());
        } finally {depth--;}
    }
    private Statement conditional(Token first) {
        var branches=new ArrayList<Branch>();Expression condition=expression();
        separators();boolean braces=take(LBRACE);
        branches.add(new Branch(condition,statements(braces,!braces)));
        if(braces)require(RBRACE,"Expected '}' for if");
        separators();List<Statement> otherwise=List.of();
        while(takeWord("else")) {
            if(takeWord("if")) {
                condition=expression();separators();
                if(braces)require(LBRACE,"Expected '{' after else if");
                branches.add(new Branch(condition,statements(braces,!braces)));
                if(braces)require(RBRACE,"Expected '}' for else if");
                separators();
            } else {
                separators();if(braces)require(LBRACE,"Expected '{' after else");
                otherwise=statements(braces,!braces);
                if(braces)require(RBRACE,"Expected '}' for else");
                separators();break;
            }
        }
        if(!braces && !takeWord("end"))throw fail("Expected 'end' for if");
        return new Conditional(branches,otherwise,first.position());
    }
    private Statement loop(Token first) {
        boolean parenthesized=take(LPAREN);if(parenthesized){softDepth++;softLines();}takeWord("let");
        Token variable=require(WORD,"Expected loop variable");
        if(!variable.text().matches("[A-Za-z_][A-Za-z0-9_]*"))throw failure(variable,"Invalid loop variable");
        if(!takeWord("in"))throw fail("Expected 'in'");
        Expression start=binary(4);if(!takeOperator(".."))throw fail("Expected '..' in loop range");
        Expression end=binary(4);
        if(parenthesized){softLines();require(RPAREN,"Expected ')' after loop range");softDepth--;}
        separators();boolean braces=take(LBRACE);var body=statements(braces,!braces);
        if(braces)require(RBRACE,"Expected '}' for for");else if(!takeWord("end"))throw fail("Expected 'end' for for");
        return new Loop(variable.text(),start,end,body,first.position());
    }
    private Expression expression(){
        Expression first=binary(0);var values=new ArrayList<Expression>();values.add(first);
        boolean phrase=first instanceof Atom || first instanceof Index || first instanceof Member;
        while((at(WORD)||at(STRING)||at(LPAREN))&&!word("end")&&!word("else"))values.add(phrase?phrasePart():binary(0));
        return values.size()==1?first:new Sequence(values,first.position());
    }
    /** The old fallback joins tokens: later builtin-looking words are literal text. */
    private Expression phrasePart() {
        Token token=peek();
        if(at(WORD)||at(STRING)) {
            advance();return postfix(new Atom(token.text(),token.kind()==STRING,token.position()),token.position());
        }
        require(LPAREN,"Expected '('");enter();softDepth++;
        try {
            var parts=new ArrayList<Expression>();parts.add(new Atom("(",false,token.position()));softLines();
            while(!at(RPAREN)) {
                if(at(EOF)||at(RBRACE)||at(RBRACKET)||at(SEPARATOR))throw fail("Expected ')' in phrase");
                if(at(COMMA)||at(OPERATOR)||at(EQUALS)){var literal=advance();parts.add(new Atom(literal.text(),false,literal.position()));}
                else parts.add(phrasePart());
                softLines();
            }
            var end=advance();parts.add(new Atom(")",false,end.position()));return new Sequence(parts,token.position());
        } finally {depth--;softDepth--;}
    }
    private Expression binary(int minimum) {
        Expression left=primary();
        while(true) {
            if(softDepth>0)softLines();
            String operator=peek().text();int precedence=precedence(operator);
            if(precedence<minimum)break;
            Token token=advance();var right=binary(precedence+1);
            left=new Binary(operator,left,right,token.position());
        }
        return left;
    }
    private int precedence(String op){return switch(op){case "or"->0;case "and"->1;case "==","!=","<",">","<=",">="->2;case "+"->3;default->-1;};}
    private Expression primary() {
        enter();try {
            if(softDepth>0)softLines();
            Token token=peek();Expression value;
            if(takeWord("not"))value=new Unary(token.text(),primary(),token.position());
            else if(take(LPAREN)){
                softDepth++;try{softLines();value=expression();softLines();require(RPAREN,"Expected ')'");}finally{softDepth--;}
            }
            else if(at(STRING)){advance();value=new Atom(token.text(),true,token.position());}
            else if(at(WORD)) {
                advance();if(softDepth>0)softLines();
                if(at(LPAREN))value=call(token);
                else if(DspdBuiltins.find(token.text()).filter(DspdBuiltins.Signature::expression).isPresent())value=legacyCall(token);
                else value=new Atom(token.text(),false,token.position());
            } else throw fail("Expected expression");
            return postfix(value,token.position());
        } finally {depth--;}
    }
    private Expression postfix(Expression value,SourcePosition position) {
        while(true) {
            if(take(LBRACKET))value=new Index(value,indexValue(),position);
            else if(at(WORD)&&peek().text().startsWith(".")) {
                var member=advance();int offset=1;
                for(String name:member.text().substring(1).split("\\.",-1)) {
                    if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw failure(member,"Invalid property name");
                    value=new Member(value,name,SourcePosition.at(source,member.position().offset()+offset));
                    offset+=name.length()+1;
                }
            }
            else return value;
        }
    }
    /** Indexes use resolveValue in the legacy runtime, not function evaluation. */
    private Expression indexValue() {
        Token first=peek();int balance=1;
        while(!at(EOF)) {
            if(at(RBRACE)||at(RPAREN)||at(SEPARATOR))throw fail("Expected ']' in index");
            if(at(LBRACKET))balance++;
            if(at(RBRACKET)&&--balance==0) {
                String text=source.substring(first.position().offset(),peek().position().offset()).stripTrailing();
                if(text.isEmpty())throw fail("Missing index expression");
                advance();return new Atom(text,text.startsWith("\"")&&text.endsWith("\"")&&text.length()>1,first.position());
            }
            advance();
        }
        throw fail("Expected ']' in index");
    }
    private Call call(Token name) {
        require(LPAREN,"Expected '('");softDepth++;
        try {
            softLines();var args=new ArrayList<Expression>();
            if(!at(RPAREN)) {
                do {softLines();args.add(expression());softLines();if(!take(COMMA))break;softLines();}while(!at(RPAREN));
            }
            require(RPAREN,"Expected ')' or ',' in call");return new Call(name.text(),args,false,name.position());
        } finally {softDepth--;}
    }
    private Call legacyCall(Token name) {
        var args=new ArrayList<Expression>();
        String normalized=DspdBuiltins.normalizeFunctionName(name.text());
        if(normalized.equals("print") && (at(WORD)||at(STRING)||at(LPAREN))) {
            return new Call(name.text(),List.of(expression()),true,name.position());
        }
        if(normalized.equals("save-text") && (at(WORD)||at(STRING)||at(LPAREN))) {
            args.add(primary());
            if(at(WORD)||at(STRING)||at(LPAREN))args.add(expression());
            return new Call(name.text(),args,true,name.position());
        }
        while(at(WORD)||at(STRING)||at(LPAREN)) {
            if(word("and")||word("or")||word("end")||word("else"))break;
            args.add(binary(3));
        }
        return new Call(name.text(),args,true,name.position());
    }
    private void enter(){if(++depth>128)throw fail("Maximum syntax nesting depth (128) exceeded");}
    private void separators(){while(take(SEPARATOR)){} }
    private void softLines(){while(at(SEPARATOR)&&!peek().text().equals(";"))advance();}
    private boolean word(String word){return at(WORD)&&peek().text().equalsIgnoreCase(word);}
    private boolean takeWord(String word){if(!word(word))return false;advance();return true;}
    private boolean takeOperator(String op){if(!at(OPERATOR)||!peek().text().equals(op))return false;advance();return true;}
    private boolean at(Token.Kind kind){return peek().kind()==kind;}
    private boolean take(Token.Kind kind){if(!at(kind))return false;advance();return true;}
    private Token require(Token.Kind kind,String message){if(!at(kind))throw fail(message);return advance();}
    private Token peek(){return tokens.get(cursor);}
    private Token advance(){return tokens.get(cursor++);}
    private Failure fail(String message){return failure(peek(),message);}
    private Failure failure(Token token,String message){return new Failure(new Diagnostic("E1100",message,token.position()));}
    private static final class Failure extends RuntimeException {
        private final Diagnostic diagnostic;
        private Failure(Diagnostic diagnostic){super(diagnostic.message());this.diagnostic=diagnostic;}
    }
}
