package de.drremote.dsp408.dspd;

import java.util.ArrayList;
import java.util.List;
import static de.drremote.dsp408.dspd.Token.Kind.*;

/** Source-preserving lexer. It never resolves variables or executes functions. */
public final class DspdLexer {
    public record Result(List<Token> tokens, List<Diagnostic> diagnostics) {
        public Result {tokens=List.copyOf(tokens);diagnostics=List.copyOf(diagnostics);}
    }
    private final String source;
    private final List<Token> tokens=new ArrayList<>();
    private final List<Diagnostic> diagnostics=new ArrayList<>();
    private int offset,line=1,column=1;
    private DspdLexer(String source) {this.source=source;}
    public static Result lex(String source) {return new DspdLexer(java.util.Objects.requireNonNull(source)).scan();}
    private SourcePosition position(){return new SourcePosition(offset,line,column);}
    private char peek(){return source.charAt(offset);}
    private void advance() {
        char c=source.charAt(offset++);
        if(c=='\r') {if(offset<source.length() && peek()=='\n')offset++;line++;column=1;}
        else if(c=='\n'){line++;column=1;}else column++;
    }
    private boolean follows(String text){return source.startsWith(text,offset);}
    private void token(Token.Kind kind,SourcePosition start){tokens.add(new Token(kind,source.substring(start.offset(),offset),start));}
    private Result scan() {
        while(offset<source.length()) {
            var start=position();char c=peek();
            if(c=='\n'||c=='\r'||c==';'){advance();token(SEPARATOR,start);continue;}
            if(Character.isWhitespace(c)){advance();continue;}
            if(c=='#'||follows("//")){while(offset<source.length() && peek()!='\r' && peek()!='\n')advance();continue;}
            if(c=='"') {
                advance();boolean closed=false;
                while(offset<source.length()) {
                    if(peek()=='\\'){advance();if(offset<source.length())advance();}
                    else if(peek()=='"'){advance();closed=true;break;}else advance();
                }
                if(!closed)diagnostics.add(new Diagnostic("E1001","Unterminated string",start));
                token(STRING,start);continue;
            }
            Token.Kind punctuation=switch(c) {
                case '('->LPAREN;case ')'->RPAREN;case '['->LBRACKET;case ']'->RBRACKET;
                case '{'->LBRACE;case '}'->RBRACE;case ','->COMMA;default->null;
            };
            if(punctuation!=null){advance();token(punctuation,start);continue;}
            if(follows("..")){advance();advance();token(OPERATOR,start);continue;}
            if("=<>!+".indexOf(c)>=0) {
                advance();if(offset<source.length() && peek()=='=' && c!='+')advance();
                token(c=='=' && offset-start.offset()==1 ? EQUALS : OPERATOR,start);continue;
            }
            while(offset<source.length()) {
                c=peek();
                if(Character.isWhitespace(c)||"\"()[]{},;=<>!+#".indexOf(c)>=0||follows("..")||follows("//"))break;
                advance();
            }
            if(offset==start.offset())advance();
            token(WORD,start);
        }
        tokens.add(new Token(EOF,"",position()));return new Result(tokens,diagnostics);
    }
}
