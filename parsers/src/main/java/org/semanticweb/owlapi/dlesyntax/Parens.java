package org.semanticweb.owlapi.dlesyntax;

/**
 * Sees through redundant parentheses in the class-expression path.
 *
 * <p>`(Self)`, `((Self))` and `(greaterThan)` mean exactly what `Self` and
 * `greaterThan` mean. The grammar already accepts them — a parenthesised
 * expression is a {@code ParenAtom} wrapping a whole {@code classExpr} — but the
 * code that recognises DLe's special fillers matches on the parse-tree shape, so
 * without unwrapping it fails to see a filler that is wrapped in parentheses.
 *
 * <p>Parentheses are only stripped when they are genuinely redundant, i.e. when
 * they enclose a single {@code primary}. In {@code (A ⊔ B)} they are load-bearing
 * and the expression is returned untouched.
 *
 * <p>Only the class-expression path needs this. Property expressions handle their
 * own parentheses in the grammar; see {@link PropertyExprs}.
 */
final class Parens {

    private Parens() {
    }

    /**
     * Strips redundant parentheses from a primary, to any depth.
     *
     * <p>Returns the argument unchanged when it is not a parenthesised single
     * expression, so this is safe to call on any primary.
     */
    static DLESyntaxParser.PrimaryContext unwrap(DLESyntaxParser.PrimaryContext ctx) {
        DLESyntaxParser.PrimaryContext current = ctx;
        while (true) {
            if (!(current instanceof DLESyntaxParser.AtomWrapContext)) return current;
            DLESyntaxParser.AtomContext atom = ((DLESyntaxParser.AtomWrapContext) current).atom();
            if (!(atom instanceof DLESyntaxParser.ParenAtomContext)) return current;
            DLESyntaxParser.PrimaryContext inner =
                lonePrimary(((DLESyntaxParser.ParenAtomContext) atom).classExpr());
            if (inner == null) return current;  // the parentheses group something
            current = inner;
        }
    }

    /**
     * The single {@code primary} a classExpr consists of, or null if it is a
     * union or intersection. Redundant parentheses are stripped.
     */
    static DLESyntaxParser.PrimaryContext lonePrimary(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return null;
        DLESyntaxParser.IntersectionExprContext inter =
            ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        if (!(inter instanceof DLESyntaxParser.PrimaryWrapContext)) return null;
        return unwrap(((DLESyntaxParser.PrimaryWrapContext) inter).primary());
    }

    /**
     * The atom inside a primary, with redundant parentheses stripped, or null if
     * the primary is a restriction rather than an atom.
     */
    static DLESyntaxParser.AtomContext atomOf(DLESyntaxParser.PrimaryContext ctx) {
        DLESyntaxParser.PrimaryContext unwrapped = unwrap(ctx);
        if (!(unwrapped instanceof DLESyntaxParser.AtomWrapContext)) return null;
        return ((DLESyntaxParser.AtomWrapContext) unwrapped).atom();
    }

    /** The atom a classExpr consists of, parentheses stripped, or null. */
    static DLESyntaxParser.AtomContext atomOf(DLESyntaxParser.ClassExprContext ctx) {
        DLESyntaxParser.PrimaryContext primary = lonePrimary(ctx);
        return primary == null ? null : atomOf(primary);
    }

    /**
     * The core name of a predicate reference, with parentheses stripped.
     *
     * <p>Predicate-restriction classes hash their expression text into an IRI, so
     * the name has to be the bare one: {@code ∃a,b.(p)} and {@code ∃a,b.p} are the
     * same expression and must not mint two different classes.
     */
    static String predicateName(DLESyntaxParser.PredicateRefContext ctx) {
        DLESyntaxParser.PredicateRefContext current = ctx;
        while (current.name() == null) {
            current = current.predicateRef();
        }
        return current.name().getText();
    }
}
