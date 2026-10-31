package de.drremote.dsp408.dspd;

public record Token(Kind kind, String text, SourcePosition position) {
    public enum Kind { WORD, STRING, LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, SEPARATOR, OPERATOR, EQUALS, EOF }
}
