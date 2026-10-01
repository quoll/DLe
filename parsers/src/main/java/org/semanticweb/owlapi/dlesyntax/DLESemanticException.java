package org.semanticweb.owlapi.dlesyntax;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.semanticweb.owlapi.io.OWLParserException;

/**
 * A DLe document that parses but does not describe a valid ontology.
 *
 * <p>Kept distinct from a syntax error because the two call for different
 * responses: a syntax error means the text is malformed, while this means the
 * text is well formed and says something OWL cannot express. {@code ⊤ ⊑
 * ∀age⁻.xsd:integer} is grammatical, but asserts that a data property has an
 * inverse — which would require a literal in the subject position of a triple.
 *
 * <p>Extends {@link OWLParserException} so existing callers that catch parse
 * failures keep working, and so these surface as diagnostics rather than as
 * internal errors leaking out of the visitor.
 */
public class DLESemanticException extends OWLParserException {

    private static final long serialVersionUID = 1L;

    /** Message prefix, mirroring the shape of the syntax-error messages. */
    private static String at(String message, int line, int column) {
        if (line <= 0) {
            return "DLE semantic error — " + message;
        }
        return "DLE semantic error at " + line + ":" + column + " — " + message;
    }

    /**
     * A failure at a known position in the document.
     *
     * @param message what is wrong, without a position; {@link #at} adds that
     * @param line    the 1-based line, or 0 and below when the position is unknown
     * @param column  the 0-based column within that line
     */
    public DLESemanticException(String message, int line, int column) {
        // Deliberately not the (message, line, column) superclass constructor:
        // that makes OWLAPI append its own "(Line N)", which would duplicate the
        // position already in the message. The message-only form matches how the
        // syntax errors from ThrowingErrorListener render.
        super(at(message, line, column));
    }

    /**
     * A failure with no position to report.
     *
     * @param message what is wrong
     */
    public DLESemanticException(String message) {
        super(at(message, 0, 0));
    }

    /**
     * Reports against the position where {@code ctx} starts.
     *
     * @param ctx     the rule the failure is about; a null or unstarted context yields an
     *                exception with no position rather than a misleading one
     * @param message what is wrong, without a position
     * @return the exception, ready to throw
     */
    public static DLESemanticException at(ParserRuleContext ctx, String message) {
        if (ctx == null || ctx.getStart() == null) {
            return new DLESemanticException(message);
        }
        Token start = ctx.getStart();
        return new DLESemanticException(message, start.getLine(), start.getCharPositionInLine());
    }
}
