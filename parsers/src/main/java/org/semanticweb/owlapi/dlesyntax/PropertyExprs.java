package org.semanticweb.owlapi.dlesyntax;

/**
 * Structural helpers for {@code propertyExpr} parse trees.
 *
 * <p>A property expression is a name wrapped in any number of parentheses and
 * inverse markers: {@code r}, {@code r⁻}, {@code (r⁻)}, {@code (r)⁻},
 * {@code ((r)⁻)⁻}. Parentheses are transparent and a doubled {@code ⁻} cancels
 * out, so every such expression reduces to exactly one of two things — a named
 * property, or the inverse of a named property. That is also the limit of what
 * OWL can express in a role position, so the reduction is a requirement rather
 * than a tidiness measure.
 *
 * <p>These live apart from both the scanner and the axiom visitor because both
 * passes need them and neither owns them.
 */
final class PropertyExprs {

    private PropertyExprs() {
    }

    /**
     * The name at the core of a property expression, with parentheses and
     * inverse markers stripped.
     */
    static DLESyntaxParser.NameContext coreName(DLESyntaxParser.PropertyExprContext ctx) {
        DLESyntaxParser.PropertyExprContext current = ctx;
        while (true) {
            if (current instanceof DLESyntaxParser.ParenPropertyExprContext) {
                current = ((DLESyntaxParser.ParenPropertyExprContext) current).propertyExpr();
            } else if (current instanceof DLESyntaxParser.InversePropertyExprContext) {
                current = ((DLESyntaxParser.InversePropertyExprContext) current).propertyExpr();
            } else {
                return ((DLESyntaxParser.SimplePropertyExprContext) current).name();
            }
        }
    }

    /** The local text of the core name, as written (used for entity-type lookup). */
    static String coreNameText(DLESyntaxParser.PropertyExprContext ctx) {
        return coreName(ctx).getText();
    }

    /**
     * Whether the expression denotes an inverse role, i.e. carries an odd number
     * of {@code ⁻} markers.
     */
    static boolean isInverse(DLESyntaxParser.PropertyExprContext ctx) {
        DLESyntaxParser.PropertyExprContext current = ctx;
        boolean inverted = false;
        while (true) {
            if (current instanceof DLESyntaxParser.ParenPropertyExprContext) {
                current = ((DLESyntaxParser.ParenPropertyExprContext) current).propertyExpr();
            } else if (current instanceof DLESyntaxParser.InversePropertyExprContext) {
                inverted = !inverted;
                current = ((DLESyntaxParser.InversePropertyExprContext) current).propertyExpr();
            } else {
                return inverted;
            }
        }
    }

    /**
     * The expression in canonical form: {@code r} or {@code r⁻}, never
     * parenthesised.
     *
     * <p>Predicate-restriction classes hash their expression text into an IRI, so
     * this has to be canonical: {@code ∃(a⁻),b.p} and {@code ∃a⁻,b.p} are the
     * same expression and must not mint two different classes. Canonicalising
     * here also means documents written without redundant parentheses keep
     * exactly the IRIs they had before parentheses were supported.
     */
    static String render(DLESyntaxParser.PropertyExprContext ctx) {
        return isInverse(ctx) ? coreNameText(ctx) + "⁻" : coreNameText(ctx);
    }
}
