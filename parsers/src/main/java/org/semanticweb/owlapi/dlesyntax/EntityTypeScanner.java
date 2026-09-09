package org.semanticweb.owlapi.dlesyntax;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    // Multimap: each sub can have multiple supers (SNOMED-CT dual-hierarchy).
    private final Map<String, Set<String>> subPropertyPairs = new HashMap<>();

    // Names that are definitively known to be classes (from restriction filler positions
    // or complex-expression subclass/equiv contexts).  Role propagation will not cross
    // these nodes, preventing the attribute hierarchy from bleeding into the concept hierarchy.
    private final Set<String> mustBeClass = new HashSet<>();

    // Kinds a document states outright, for the cases inference cannot reach: a punned
    // name, or one that breaks the case convention.  `X ⊑ ⊤` states a class and
    // `X ⊑ owl:topObjectProperty` a role; a name carrying both is punned.
    //
    // The two do different jobs, and keeping them apart is the whole point.  A stated class
    // stops role classification travelling further up the hierarchy.  Only a stated *role*
    // may override mustBeClass — an earlier attempt let a stated class do that, and since
    // every ordinary class opens its block with `X ⊑ ⊤`, that rejected valid documents.
    private final Set<String> explicitClass = new HashSet<>();
    private final Set<String> explicitRole  = new HashSet<>();

    // Prefix map, needed only to resolve the kind statements.  Recognising them by
    // spelling is not sound: a document may bind the OWL namespace to some other prefix,
    // in which case the statement is missed, and it may bind `owl:` to some other
    // namespace, in which case an ordinary subsumption is wrongly taken for a statement
    // and the axiom destroyed.  Seeded with the same defaults as the axiom visitor.
    private final Map<String, String> prefixes = new HashMap<String, String>() {{
        put("owl:",  OWL_NS);
        put("rdf:",  "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        put("xsd:",  "http://www.w3.org/2001/XMLSchema#");
        put("xml:",  "http://www.w3.org/XML/1998/namespace");
    }};

    @Override
    public Void visitPrefixDecl(DLESyntaxParser.PrefixDeclContext ctx) {
        String label = ctx.PNAME_NS().getText();
        String iri   = ctx.IRI().getText();
        prefixes.put(label.endsWith(":") ? label : label + ":",
                     iri.substring(1, iri.length() - 1));
        return null;
    }

    // Where each name was first used as an inverse role (r⁻). Only needed to
    // report a location if that name also turns out to be a data property:
    // inverting a data property is not expressible in OWL. See validate().
    private final Map<String, DLESyntaxParser.PropertyExprContext> invertedRoleUse =
        new LinkedHashMap<>();

    // Inverse roles whose filler is a datatype. This is the conflict outright,
    // and it has to be recorded separately: an inverse role is classified as an
    // object property, so the datatype evidence never reaches dataPropertyNames.
    private final Map<String, DLESyntaxParser.PropertyExprContext> datatypeFilledInverse =
        new LinkedHashMap<>();

    Set<String> getObjectPropertyNames() { return objectPropertyNames; }
    Set<String> getDataPropertyNames()   { return dataPropertyNames; }
    Set<String> getPredicateNames()      { return predicateNames; }
    /** Names the document states are roles, via {@code X ⊑ owl:topObjectProperty}. */
    Set<String> getExplicitRoleNames()   { return explicitRole; }

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
        predicateNames.add(Parens.predicateName(ctx.predicateRef()));
        return null;
    }

    @Override
    public Void visitMultiRoleAllValuesFrom(DLESyntaxParser.MultiRoleAllValuesFromContext ctx) {
        ctx.propertyExpr().forEach(pe -> classifyProp(pe, false));
        predicateNames.add(Parens.predicateName(ctx.predicateRef()));
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
    @Override public Void visitFunctionalRoleAxiom(DLESyntaxParser.FunctionalRoleAxiomContext ctx)   { classifyUnknownRole(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitReflexiveRoleAxiom(DLESyntaxParser.ReflexiveRoleAxiomContext ctx)     { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitIrreflexiveRoleAxiom(DLESyntaxParser.IrreflexiveRoleAxiomContext ctx) { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitSymmetricRoleAxiom(DLESyntaxParser.SymmetricRoleAxiomContext ctx)     { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitAsymmetricRoleAxiom(DLESyntaxParser.AsymmetricRoleAxiomContext ctx)   { objectPropertyNames.add(ctx.name().getText()); return visitChildren(ctx); }
    @Override public Void visitDisjointRoleAxiom(DLESyntaxParser.DisjointRoleAxiomContext ctx) {
        ctx.name().forEach(n -> classifyUnknownRole(n.getText()));
        return visitChildren(ctx);
    }

    // Classifies a role as object property only if not already established as data.
    // Func(p) and Disj(p,q) are syntactically ambiguous — they apply to both data
    // and object properties.  This avoids overwriting a definitive data classification.
    private void classifyUnknownRole(String name) {
        if (!dataPropertyNames.contains(name)) {
            objectPropertyNames.add(name);
        }
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
        objectPropertyNames.add(PropertyExprs.coreNameText(ctx.propertyExpr()));
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
        // p ≡ q (both bare unprefixed names, lowercase) — treat as object properties.
        // Prefixed names are excluded: the prefix letter is not a signal about resource type.
        if (lhs != null && rhs != null
                && !lhs.contains(":") && !rhs.contains(":")
                && Character.isLowerCase(lhs.charAt(0)) && Character.isLowerCase(rhs.charAt(0))) {
            objectPropertyNames.add(lhs);
            objectPropertyNames.add(rhs);
        }
        // A ≡ (complex) → A is a class; (complex) ≡ B → B is a class.
        // Exclude inverse-atom RHS/LHS since those are property expressions, not complex classes.
        if (lhs != null && rhs == null && singleInverseAtom(ctx.classExpr(1)) == null)
            mustBeClass.add(lhs);
        if (rhs != null && lhs == null && singleInverseAtom(ctx.classExpr(0)) == null)
            mustBeClass.add(rhs);
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

        // X ⊑ ⊤ — states that X is a class.  Recorded as a barrier only; it does not
        // exempt X from mustBeClass, and it stays an ordinary subsumption otherwise, which
        // is what keeps the nine corpus documents that open with it unaffected.
        if (lhs != null && isTopClassExpr(ctx.classExpr(1))) {
            explicitClass.add(lhs);
        }

        // X ⊑ owl:topObjectProperty — a declaration that X is a role, not a subsumption
        // to record.  Written by the storer for a name whose kind the reader could not
        // otherwise infer: a pun, or one that breaks the case convention.
        String topProperty = rhs == null ? null : topPropertyIri(rhs);
        if (lhs != null && topProperty != null) {
            explicitRole.add(lhs);
            if (TOP_DATA_PROPERTY_IRI.equals(topProperty)) {
                dataPropertyNames.add(lhs);
                objectPropertyNames.remove(lhs);
            } else if (!dataPropertyNames.contains(lhs)) {
                objectPropertyNames.add(lhs);
            }
            return null;
        }

        if (lhs != null && rhs != null) {
            subPropertyPairs.computeIfAbsent(lhs, k -> new HashSet<>()).add(rhs);
            // Heuristic: bare camelCase names (lowercase start, no prefix) are almost
            // certainly properties.  Prefixed names are excluded because the prefix
            // letter carries no information about the local resource type.
            if (!objectPropertyNames.contains(lhs) && !dataPropertyNames.contains(lhs)
                    && !objectPropertyNames.contains(rhs) && !dataPropertyNames.contains(rhs)
                    && !lhs.contains(":") && !rhs.contains(":")
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
        // A ⊑ (complex) → A is definitively a class; (complex) ⊑ B → B is a class.
        // Exclude inverse-atom RHS/LHS since those are property expressions.
        if (lhs != null && rhs == null && singleInverseAtom(ctx.classExpr(1)) == null)
            mustBeClass.add(lhs);
        if (lhs == null && rhs != null && singleInverseAtom(ctx.classExpr(0)) == null)
            mustBeClass.add(rhs);
        return visitChildren(ctx);
    }

    // ── Propagation ──────────────────────────────────────────────────────────

    /**
     * Propagates property classifications through sub-property axioms until
     * no new names are added.  Must be called after visiting the full tree.
     *
     * <p>Two phases:
     * <ol>
     *   <li>Propagate {@code mustBeClass} upward (sub → sup): if A is definitively a class
     *       and A ⊑ B, then B is also a class.  This marks the SNOMED-CT concept-hierarchy
     *       root before role propagation reaches it.</li>
     *   <li>Propagate role classifications, skipping any node in {@code mustBeClass}.
     *       This stops attribute-hierarchy propagation from bleeding into the concept
     *       hierarchy at the shared root.</li>
     * </ol>
     */
    void propagatePropertyTypes() {
        // Phase 1: propagate mustBeClass upward through sub-property chains.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Set<String>> e : subPropertyPairs.entrySet()) {
                if (mustBeClass.contains(e.getKey())) {
                    for (String sup : e.getValue()) {
                        changed |= mustBeClass.add(sup);
                    }
                }
            }
        }
        // Phase 2: propagate role classifications, stopping at mustBeClass nodes.
        changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Set<String>> e : subPropertyPairs.entrySet()) {
                String sub = e.getKey();
                for (String sup : e.getValue()) {
                    // Upward (sub → sup) stops at a name that names a class: that is where
                    // the role hierarchy ends and the concept hierarchy begins.  Downward
                    // (sup → sub) crosses it, because being the parent of roles is what makes
                    // such a name a pun — whatever else it is, its sub-names are roles.
                    //
                    // The consequence, deliberate but worth knowing: *every* name below a
                    // punned name becomes a role, including one that was meant as a class.
                    // Right for SNOMED CT, whose punned roots have none but roles beneath
                    // them; wrong for a pun with concept children, which DLe cannot express.
                    //
                    // Data classification is definitive — maintain mutual exclusivity.
                    if (dataPropertyNames.contains(sub) && !isClassBarrier(sub)) {
                        changed |= dataPropertyNames.add(sup);
                        changed |= objectPropertyNames.remove(sup);
                    }
                    if (dataPropertyNames.contains(sup)) {
                        changed |= dataPropertyNames.add(sub);
                        changed |= objectPropertyNames.remove(sub);
                    }
                    // Object property propagation is blocked at mustBeClass nodes.
                    if (objectPropertyNames.contains(sub) && !isClassBarrier(sub)
                            && !dataPropertyNames.contains(sup)
                            && (!mustBeClass.contains(sup) || mayBeRoleDespiteClassUse(sup)))
                        changed |= objectPropertyNames.add(sup);
                    if (objectPropertyNames.contains(sup) && !dataPropertyNames.contains(sub)
                            && (!mustBeClass.contains(sub) || mayBeRoleDespiteClassUse(sub)))
                        changed |= objectPropertyNames.add(sub);
                }
            }
        }
    }

    static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String TOP_OBJECT_PROPERTY_IRI = OWL_NS + "topObjectProperty";
    private static final String TOP_DATA_PROPERTY_IRI   = OWL_NS + "topDataProperty";

    /**
     * Whether a name resolves to one of the OWL top properties, making {@code X ⊑ name} a
     * declaration that X is a role rather than an ordinary sub-property axiom.
     *
     * <p>Unambiguous because no document says it for any other reason: every object property
     * is a sub-property of {@code owl:topObjectProperty} already, so the statement carries no
     * information except the one it is being used to carry.
     *
     * <p>Resolved to an IRI rather than compared as text.  Matching the spelling fails both
     * ways round: it misses {@code o:topObjectProperty} where the document binds the OWL
     * namespace to {@code o:}, and it wrongly claims {@code owl:topObjectProperty} where the
     * document has bound {@code owl:} to something else — silently destroying a real axiom.
     *
     * @return the top property's IRI if it is one, else null
     */
    @Nullable
    private String topPropertyIri(String name) {
        String iri = resolve(name);
        if (TOP_OBJECT_PROPERTY_IRI.equals(iri) || TOP_DATA_PROPERTY_IRI.equals(iri)) {
            return iri;
        }
        return null;
    }

    /** Expands a name to an IRI, or null if its prefix is undeclared. */
    @Nullable
    private String resolve(String name) {
        int colon = name.indexOf(':');
        if (colon < 0) return null;   // a bare name is in the default namespace, never owl:
        String base = prefixes.get(name.substring(0, colon + 1));
        return base == null ? null : base + name.substring(colon + 1);
    }

    /**
     * Whether role classification stops at this name on its way up the hierarchy.
     *
     * <p>{@code a ⊑ b} is written the same for a sub-class and a sub-property, so something
     * has to say which hierarchy a pair belongs to.  Three things can, in order: a stated
     * role, a stated class, and otherwise the case of the name's local part.  A name that
     * names a class is where the two hierarchies meet, and carrying role classification past
     * it turns the concepts above into properties.
     *
     * <p>This does not stop the name itself being a role — that is exactly what a pun is —
     * and it deliberately leaves {@code mustBeClass} alone.  Exempting a name from
     * {@code mustBeClass} would let a name used in a class position become a property, and a
     * document naming it as a class would then fail to parse.
     */
    private boolean isClassBarrier(String name) {
        if (explicitClass.contains(name)) return true;
        if (explicitRole.contains(name)) return false;
        return namesAClassByConvention(name);
    }

    /**
     * Whether role classification may settle on a name that use has established as a class.
     *
     * <p>Normally it may not: {@code mustBeClass} is what stops the role hierarchy bleeding
     * into the concept hierarchy. A name explicitly declared a role is the exception, and
     * only such a name — the declaration is the document saying it is punned, which is
     * information no inference can recover. Widening this to any name that merely *looks*
     * like a class is what made an earlier attempt reject valid documents.
     */
    private boolean mayBeRoleDespiteClassUse(String name) {
        return explicitRole.contains(name);
    }

    /**
     * Whether a name's local part begins with an upper-case letter.
     *
     * <p>Long-standing DL practice, which DLe relies on: concepts are capitalised, roles are
     * not.  It says nothing about a numeric local part — SNOMED CT's identifiers, for
     * instance — which is why such a name has to state its kind instead.
     */
    static boolean namesAClassByConvention(String name) {
        int colon = name.lastIndexOf(':');
        String local = colon < 0 ? name : name.substring(colon + 1);
        return !local.isEmpty() && Character.isUpperCase(local.charAt(0));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Rejects documents that parse but cannot be expressed in OWL.
     *
     * <p>Run after {@link #propagatePropertyTypes()}, because a name can become a
     * data property indirectly — through a sub-property axiom — and the conflict
     * is only visible once classification has settled.
     *
     * <p>The one conflict checked here is a data property used as an inverse
     * role. It is worth a dedicated diagnostic because the failure was otherwise
     * an {@code IllegalStateException} escaping the axiom visitor, which told the
     * author nothing about what was wrong with their document.
     */
    void validate() {
        // An inverse role with a datatype filler: the contradiction is in the one
        // expression, so no propagation is needed to see it.
        for (Map.Entry<String, DLESyntaxParser.PropertyExprContext> use
                : datatypeFilledInverse.entrySet()) {
            throw inverseOfDataProperty(use.getKey(), use.getValue());
        }
        // Or the two halves are in different axioms, possibly only connected once
        // sub-property classifications have propagated.
        for (Map.Entry<String, DLESyntaxParser.PropertyExprContext> use
                : invertedRoleUse.entrySet()) {
            if (dataPropertyNames.contains(use.getKey())) {
                throw inverseOfDataProperty(use.getKey(), use.getValue());
            }
        }
    }

    private static DLESemanticException inverseOfDataProperty(
            String name, DLESyntaxParser.PropertyExprContext where) {
        return DLESemanticException.at(where,
            "'" + name + "' is used as an inverse role, but it is a data property. "
                + "A data property cannot have an inverse: that would put a literal "
                + "in the subject position of a triple. Either give '" + name
                + "' a class as its range so it is an object property, or drop the "
                + "inverse marker.");
    }

    private void classifyRestriction(DLESyntaxParser.PropertyExprContext propCtx,
                                     DLESyntaxParser.PrimaryContext fillerCtx) {
        boolean data = isDataPrimary(fillerCtx);
        classifyProp(propCtx, data);
        // Non-data fillers are class expressions; seed mustBeClass so phase-1 propagation
        // can mark the full concept-hierarchy chain before role propagation runs.
        if (!data) {
            String fillerName = primaryBareName(fillerCtx);
            if (fillerName != null) mustBeClass.add(fillerName);
        }
    }

    private void classifyFromClassExpr(DLESyntaxParser.PropertyExprContext propCtx,
                                       DLESyntaxParser.ClassExprContext fillerCtx) {
        boolean data = isDataClassExpr(fillerCtx);
        classifyProp(propCtx, data);
        if (!data) {
            String fillerName = singleBareName(fillerCtx);
            if (fillerName != null) mustBeClass.add(fillerName);
        }
    }

    private void classifyProp(DLESyntaxParser.PropertyExprContext propCtx, boolean isData) {
        String name = PropertyExprs.coreNameText(propCtx);
        if (PropertyExprs.isInverse(propCtx)) {
            // Inverse properties are always object properties
            objectPropertyNames.add(name);
            invertedRoleUse.putIfAbsent(name, propCtx);
            if (isData) datatypeFilledInverse.putIfAbsent(name, propCtx);
        } else {
            if (isData) {
                // Data classification is definitive — remove any prior object classification.
                dataPropertyNames.add(name);
                objectPropertyNames.remove(name);
            } else if (!dataPropertyNames.contains(name)) {
                // Only classify as object if not already established as data.
                objectPropertyNames.add(name);
            }
        }
    }

    /** True if the primary is an atom that looks like a data range. */
    private boolean isDataPrimary(DLESyntaxParser.PrimaryContext ctx) {
        var atom = Parens.atomOf(ctx);
        return atom != null && isDataAtom(atom);
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
            return isDataTypeName(name);
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
     * Returns true if {@code name} (a CURIE or bare name as written in the DLE source)
     * denotes an OWL datatype.  All {@code xsd:} names are datatypes; from the RDF/RDFS
     * namespaces only the specific RDF 1.2 datatype names qualify.
     */
    static boolean isDataTypeName(String name) {
        if (name.startsWith("xsd:")) return true;
        switch (name) {
            case "rdf:PlainLiteral":
            case "rdf:langString":
            case "rdf:dirLangString":
            case "rdf:HTML":
            case "rdf:XMLLiteral":
            case "rdf:JSON":
            case "rdfs:Literal":
                return true;
            default:
                return false;
        }
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
        var atom = Parens.atomOf(ctx);
        if (!(atom instanceof DLESyntaxParser.NameAtomContext)) return null;
        return ((DLESyntaxParser.NameAtomContext) atom).name().getText();
    }

    private boolean isBottomClassExpr(DLESyntaxParser.ClassExprContext ctx) {
        return Parens.atomOf(ctx) instanceof DLESyntaxParser.BottomAtomContext;
    }

    /** Whether a classExpr is just {@code ⊤}. */
    private boolean isTopClassExpr(DLESyntaxParser.ClassExprContext ctx) {
        return Parens.atomOf(ctx) instanceof DLESyntaxParser.TopAtomContext;
    }

    /** Returns the InversePropertyAtomContext if the classExpr is just a single name⁻, else null. */
    private DLESyntaxParser.InversePropertyAtomContext singleInverseAtom(DLESyntaxParser.ClassExprContext ctx) {
        var atom = Parens.atomOf(ctx);
        if (!(atom instanceof DLESyntaxParser.InversePropertyAtomContext)) return null;
        return (DLESyntaxParser.InversePropertyAtomContext) atom;
    }
}
