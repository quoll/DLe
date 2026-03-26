package org.semanticweb.owlapi.dlesyntax;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First-pass visitor that scans a DLE parse tree to determine which names are
 * used as object properties and which as data properties.
 *
 * <p>A name that appears as the role in a restriction (∃r.C, ∀r.C, ≤n r.C) is
 * classified as a property.  If the filler is an XSD/RDF datatype name or a
 * one-of containing literals, it is classified as a data property; otherwise
 * as an object property.  After scanning, {@link #propagatePropertyTypes()}
 * propagates these classifications through sub-property axioms (A ⊑ B where A
 * is known to be a property implies B is also a property of the same kind).
 */
class EntityTypeScanner extends DLESyntaxBaseVisitor<Void> {

    private final Set<String> objectPropertyNames = new HashSet<>();
    private final Set<String> dataPropertyNames   = new HashSet<>();
    private final Set<String> predicateNames       = new HashSet<>();

    // Sub-property pairs collected from simple A ⊑ B axioms (both bare names).
    // Used by propagatePropertyTypes() to spread type info transitively.
    private final Map<String, String> subPropertyPairs = new HashMap<>();

    Set<String> getObjectPropertyNames() { return objectPropertyNames; }
    Set<String> getDataPropertyNames()   { return dataPropertyNames; }
    Set<String> getPredicateNames()      { return predicateNames; }

    // ── Restriction contexts ─────────────────────────────────────────────────

    @Override
    public Void visitImplicitSomeValuesFrom(DLESyntaxParser.ImplicitSomeValuesFromContext ctx) {
        classifyRestriction(ctx.propertyExpr(), ctx.primary());
        return visitChildren(ctx);
    }

    @Override
    public Void visitPredicateDefinition(DLESyntaxParser.PredicateDefinitionContext ctx) {
        // name(0) is the predicate name; name(1..n) are variable names, not entities.
        predicateNames.add(ctx.name(0).getText());
        return null;
    }

    @Override
    public Void visitMultiRoleSomeValuesFrom(DLESyntaxParser.MultiRoleSomeValuesFromContext ctx) {
        ctx.propertyExpr().forEach(pe -> classifyProp(pe, false));
        predicateNames.add(ctx.name().getText());
        return null;
    }

    @Override
    public Void visitMultiRoleAllValuesFrom(DLESyntaxParser.MultiRoleAllValuesFromContext ctx) {
        ctx.propertyExpr().forEach(pe -> classifyProp(pe, false));
        predicateNames.add(ctx.name().getText());
        return null;
    }

    @Override
    public Void visitSomeValuesFrom(DLESyntaxParser.SomeValuesFromContext ctx) {
        classifyRestriction(ctx.propertyExpr(), ctx.primary());
        checkUnaryPredicate(ctx.primary());
        return visitChildren(ctx);
    }

    @Override
    public Void visitAllValuesFrom(DLESyntaxParser.AllValuesFromContext ctx) {
        classifyRestriction(ctx.propertyExpr(), ctx.primary());
        checkUnaryPredicate(ctx.primary());
        return visitChildren(ctx);
    }

    /**
     * If the primary is a bare NameAtom starting with a lowercase letter (and not
     * already known as a property), record it as a predicate name.
     */
    private void checkUnaryPredicate(DLESyntaxParser.PrimaryContext primary) {
        if (!(primary instanceof DLESyntaxParser.AtomWrapContext)) return;
        DLESyntaxParser.AtomContext atom = ((DLESyntaxParser.AtomWrapContext) primary).atom();
        if (!(atom instanceof DLESyntaxParser.NameAtomContext)) return;
        String name = ((DLESyntaxParser.NameAtomContext) atom).name().getText();
        if (predicateNames.contains(name)) return;
        if (objectPropertyNames.contains(name) || dataPropertyNames.contains(name)) return;
        if (name.contains(":")) return;   // prefixed names (e.g. owl:Thing) are not predicates
        if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) {
            predicateNames.add(name);
        }
    }

    @Override
    public Void visitCardinalityRestriction(DLESyntaxParser.CardinalityRestrictionContext ctx) {
        classifyRestriction(ctx.propertyExpr(), ctx.primary());
        return visitChildren(ctx);
    }

    @Override
    public Void visitUnqualifiedCardinalityRestriction(DLESyntaxParser.UnqualifiedCardinalityRestrictionContext ctx) {
        // No filler to inspect; default to object property (propagation will correct if already known as data).
        classifyProp(ctx.propertyExpr(), false);
        return visitChildren(ctx);
    }

    @Override
    public Void visitFunctionalPropertyAxiom(DLESyntaxParser.FunctionalPropertyAxiomContext ctx) {
        classifyFromClassExpr(ctx.propertyExpr(), ctx.classExpr());
        return visitChildren(ctx);
    }

    // ── Textbook role axiom syntax ───────────────────────────────────────────

    @Override public Void visitTransitiveRoleAxiom(DLESyntaxParser.TransitiveRoleAxiomContext ctx)   { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitFunctionalRoleAxiom(DLESyntaxParser.FunctionalRoleAxiomContext ctx)   { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitReflexiveRoleAxiom(DLESyntaxParser.ReflexiveRoleAxiomContext ctx)     { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitIrreflexiveRoleAxiom(DLESyntaxParser.IrreflexiveRoleAxiomContext ctx) { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitSymmetricRoleAxiom(DLESyntaxParser.SymmetricRoleAxiomContext ctx)     { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitAsymmetricRoleAxiom(DLESyntaxParser.AsymmetricRoleAxiomContext ctx)   { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitDisjointRoleAxiom(DLESyntaxParser.DisjointRoleAxiomContext ctx) {
        ctx.name().forEach(n -> objectPropertyNames.add(n.getText()));
        return visitChildren(ctx);
    }

    // ── Property chain axioms ────────────────────────────────────────────────

    @Override
    public Void visitSubPropertyChainAxiom(DLESyntaxParser.SubPropertyChainAxiomContext ctx) {
        // All positions in a chain axiom are object properties
        for (DLESyntaxParser.PropertyExprContext propCtx : ctx.chainExpr().propertyExpr()) {
            classifyProp(propCtx, false);
        }
        objectPropertyNames.add(ctx.name().getText());
        return visitChildren(ctx);
    }

    @Override
    public Void visitPropertyChainEquivAxiom(DLESyntaxParser.PropertyChainEquivAxiomContext ctx) {
        objectPropertyNames.add(ctx.name().getText());
        for (DLESyntaxParser.PropertyExprContext propCtx : ctx.chainExpr().propertyExpr()) {
            classifyProp(propCtx, false);
        }
        return visitChildren(ctx);
    }

    // ── Chained equiv-sub axiom ──────────────────────────────────────────────

    @Override
    public Void visitChainedEquivSubAxiom(DLESyntaxParser.ChainedEquivSubAxiomContext ctx) {
        // A ≡ B ⊑ C — all three positions are object properties
        for (DLESyntaxParser.PropertyExprContext propCtx : ctx.propertyExpr()) {
            classifyProp(propCtx, false);
        }
        return visitChildren(ctx);
    }

    // ── Inverse property atom ─────────────────────────────────────────────────

    @Override
    public Void visitInversePropertyAtom(DLESyntaxParser.InversePropertyAtomContext ctx) {
        // The base name is always an object property
        objectPropertyNames.add(ctx.name().getText());
        return visitChildren(ctx);
    }

    // ── Equiv axiom classification ───────────────────────────────────────────

    @Override
    public Void visitEquivAxiom(DLESyntaxParser.EquivAxiomContext ctx) {
        String lhs = singleBareName(ctx.classExpr(0));
        String rhs = singleBareName(ctx.classExpr(1));
        // p ≡ q⁻  (or q⁻ ≡ p) — both sides are object properties
        if (lhs != null && singleInverseAtom(ctx.classExpr(1)) != null) {
            objectPropertyNames.add(lhs);
        }
        if (rhs != null && singleInverseAtom(ctx.classExpr(0)) != null) {
            objectPropertyNames.add(rhs);
        }
        // p ≡ q (both bare names, lowercase) — treat as object properties
        if (lhs != null && rhs != null
                && Character.isLowerCase(lhs.charAt(0)) && Character.isLowerCase(rhs.charAt(0))) {
            objectPropertyNames.add(lhs);
            objectPropertyNames.add(rhs);
        }
        return visitChildren(ctx);
    }

    // ── Sub-property pair collection ─────────────────────────────────────────

    @Override
    public Void visitSubClassAxiom(DLESyntaxParser.SubClassAxiomContext ctx) {
        // DisjointObjectProperties: p ⊓ q ⊑ ⊥
        List<String> intersected = allIntersectedBareNames(ctx.classExpr(0));
        if (intersected != null && isBottomClassExpr(ctx.classExpr(1))
                && intersected.stream().allMatch(n -> Character.isLowerCase(n.charAt(0)))) {
            objectPropertyNames.addAll(intersected);
        }

        String lhs = singleBareName(ctx.classExpr(0));
        String rhs = singleBareName(ctx.classExpr(1));
        if (lhs != null && rhs != null) {
            subPropertyPairs.put(lhs, rhs);
            // Heuristic: camelCase names (lowercase start) are almost certainly
            // properties, not classes.  Apply when neither side is yet classified.
            if (!objectPropertyNames.contains(lhs) && !dataPropertyNames.contains(lhs)
                    && !objectPropertyNames.contains(rhs) && !dataPropertyNames.contains(rhs)
                    && Character.isLowerCase(lhs.charAt(0))
                    && Character.isLowerCase(rhs.charAt(0))) {
                objectPropertyNames.add(lhs);
                objectPropertyNames.add(rhs);
            }
        }
        // A ⊑ B⁻ — lhs must be an object property (inverse forces the interpretation)
        if (lhs != null && singleInverseAtom(ctx.classExpr(1)) != null) {
            objectPropertyNames.add(lhs);
        }
        return visitChildren(ctx);
    }

    // ── Propagation ──────────────────────────────────────────────────────────

    /**
     * Propagates property classifications through sub-property axioms until
     * no new names are added.  Must be called after visiting the full tree.
     */
    void propagatePropertyTypes() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, String> e : subPropertyPairs.entrySet()) {
                String sub = e.getKey(), sup = e.getValue();
                // sub ⊑ sup: if either side is known, the other must be the same kind
                if (objectPropertyNames.contains(sub)) changed |= objectPropertyNames.add(sup);
                if (objectPropertyNames.contains(sup)) changed |= objectPropertyNames.add(sub);
                if (dataPropertyNames.contains(sub))   changed |= dataPropertyNames.add(sup);
                if (dataPropertyNames.contains(sup))   changed |= dataPropertyNames.add(sub);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void classifyRestriction(DLESyntaxParser.PropertyExprContext propCtx,
                                     DLESyntaxParser.PrimaryContext fillerCtx) {
        boolean data = isDataPrimary(fillerCtx);
        classifyProp(propCtx, data);
    }

    private void classifyFromClassExpr(DLESyntaxParser.PropertyExprContext propCtx,
                                       DLESyntaxParser.ClassExprContext fillerCtx) {
        boolean data = isDataClassExpr(fillerCtx);
        classifyProp(propCtx, data);
    }

    private void classifyProp(DLESyntaxParser.PropertyExprContext propCtx, boolean isData) {
        if (propCtx instanceof DLESyntaxParser.InversePropertyExprContext) {
            // Inverse properties are always object properties
            String name = ((DLESyntaxParser.InversePropertyExprContext) propCtx).name().getText();
            objectPropertyNames.add(name);
        } else {
            String name = ((DLESyntaxParser.SimplePropertyExprContext) propCtx).name().getText();
            if (isData) {
                dataPropertyNames.add(name);
            } else {
                objectPropertyNames.add(name);
            }
        }
    }

    /** True if the primary is an atom that looks like a data range. */
    private boolean isDataPrimary(DLESyntaxParser.PrimaryContext ctx) {
        if (ctx instanceof DLESyntaxParser.AtomWrapContext) {
            return isDataAtom(((DLESyntaxParser.AtomWrapContext) ctx).atom());
        }
        return false;
    }

    /**
     * True if a classExpr looks like a data range (single atom, or a union/
     * intersection of data ranges).
     */
    private boolean isDataClassExpr(DLESyntaxParser.ClassExprContext ctx) {
        if (ctx instanceof DLESyntaxParser.UnionOfContext) {
            var u = (DLESyntaxParser.UnionOfContext) ctx;
            return isDataClassExpr(u.classExpr()) && isDataIntersectionExpr(u.intersectionExpr());
        }
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return false;
        return isDataIntersectionExpr(((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr());
    }

    private boolean isDataIntersectionExpr(DLESyntaxParser.IntersectionExprContext inter) {
        if (inter instanceof DLESyntaxParser.IntersectionOfContext) {
            var iof = (DLESyntaxParser.IntersectionOfContext) inter;
            return isDataIntersectionExpr(iof.intersectionExpr()) && isDataPrimary(iof.primary());
        }
        if (inter instanceof DLESyntaxParser.PrimaryWrapContext) {
            return isDataPrimary(((DLESyntaxParser.PrimaryWrapContext) inter).primary());
        }
        return false;
    }

    private boolean isDataAtom(DLESyntaxParser.AtomContext atom) {
        if (atom instanceof DLESyntaxParser.NameAtomContext) {
            String name = ((DLESyntaxParser.NameAtomContext) atom).name().getText();
            return name.startsWith("xsd:") || name.startsWith("rdf:") || name.startsWith("rdfs:");
        }
        if (atom instanceof DLESyntaxParser.OneOfAtomContext) {
            // DataOneOf if any element is a literal
            return ((DLESyntaxParser.OneOfAtomContext) atom).oneOfList().oneOfElem().stream()
                .anyMatch(e -> e instanceof DLESyntaxParser.LiteralElemContext);
        }
        // () is an empty data range
        if (atom instanceof DLESyntaxParser.EmptyAtomContext) return true;
        // [datatype ⊓ [facet ...]] is a datatype restriction
        if (atom instanceof DLESyntaxParser.DataRangeAtomContext) return true;
        // xsd:integer[≥1 ⊓ ≤5] compact numeric restriction
        if (atom instanceof DLESyntaxParser.NumericDataRangeAtomContext) return true;
        // (xsd:integer ⊔ xsd:string) parenthesised union/intersection of data ranges
        if (atom instanceof DLESyntaxParser.ParenAtomContext) {
            return isDataClassExpr(((DLESyntaxParser.ParenAtomContext) atom).classExpr());
        }
        return false;
    }

    /**
     * If a classExpr is just a single bare name (name atom with no union/
     * intersection/restriction), returns that name text; otherwise null.
     */
    private String singleBareName(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return null;
        var inter = ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        if (!(inter instanceof DLESyntaxParser.PrimaryWrapContext)) return null;
        var prim = ((DLESyntaxParser.PrimaryWrapContext) inter).primary();
        if (!(prim instanceof DLESyntaxParser.AtomWrapContext)) return null;
        var atom = ((DLESyntaxParser.AtomWrapContext) prim).atom();
        if (!(atom instanceof DLESyntaxParser.NameAtomContext)) return null;
        return ((DLESyntaxParser.NameAtomContext) atom).name().getText();
    }

    /**
     * If classExpr is a flat intersection of bare names only (no restrictions,
     * unions, or other structure), returns those names; otherwise null.
     */
    private List<String> allIntersectedBareNames(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return null;
        var inter = ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        List<String> names = new ArrayList<>();
        return collectIntersectionNames(inter, names) ? names : null;
    }

    private boolean collectIntersectionNames(DLESyntaxParser.IntersectionExprContext inter,
                                             List<String> names) {
        if (inter instanceof DLESyntaxParser.PrimaryWrapContext) {
            String n = primaryBareName(((DLESyntaxParser.PrimaryWrapContext) inter).primary());
            if (n == null) return false;
            names.add(n);
            return true;
        }
        if (inter instanceof DLESyntaxParser.IntersectionOfContext) {
            var iof = (DLESyntaxParser.IntersectionOfContext) inter;
            String n = primaryBareName(iof.primary());
            if (n == null) return false;
            names.add(n);
            return collectIntersectionNames(iof.intersectionExpr(), names);
        }
        return false;
    }

    private String primaryBareName(DLESyntaxParser.PrimaryContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.AtomWrapContext)) return null;
        var atom = ((DLESyntaxParser.AtomWrapContext) ctx).atom();
        if (!(atom instanceof DLESyntaxParser.NameAtomContext)) return null;
        return ((DLESyntaxParser.NameAtomContext) atom).name().getText();
    }

    private boolean isBottomClassExpr(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return false;
        var inter = ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        if (!(inter instanceof DLESyntaxParser.PrimaryWrapContext)) return false;
        var prim = ((DLESyntaxParser.PrimaryWrapContext) inter).primary();
        if (!(prim instanceof DLESyntaxParser.AtomWrapContext)) return false;
        return ((DLESyntaxParser.AtomWrapContext) prim).atom() instanceof DLESyntaxParser.BottomAtomContext;
    }

    /** Returns the InversePropertyAtomContext if the classExpr is just a single name⁻, else null. */
    private DLESyntaxParser.InversePropertyAtomContext singleInverseAtom(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return null;
        var inter = ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        if (!(inter instanceof DLESyntaxParser.PrimaryWrapContext)) return null;
        var prim = ((DLESyntaxParser.PrimaryWrapContext) inter).primary();
        if (!(prim instanceof DLESyntaxParser.AtomWrapContext)) return null;
        var atom = ((DLESyntaxParser.AtomWrapContext) prim).atom();
        if (!(atom instanceof DLESyntaxParser.InversePropertyAtomContext)) return null;
        return (DLESyntaxParser.InversePropertyAtomContext) atom;
    }
}
