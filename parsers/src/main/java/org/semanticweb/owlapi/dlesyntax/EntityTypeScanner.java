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
    /**
     * Names the document defines as datatypes, by {@code Code ≡ [xsd:string ⊓ […]]}.
     *
     * <p>The built-in list cannot know about these, so a document's own datatype was taken
     * for a class and every data property ranged on it became an object property — the
     * datatype flipping too. A definition is the strongest possible statement that a name is
     * a datatype, and it outranks both the list and the case convention.
     */
    private final Set<String> datatypeNames = new HashSet<>();
    /**
     * Names tied together by an equivalence, as groups.
     *
     * <p>Resolved in {@link #propagatePropertyTypes()} rather than as each statement is
     * visited, because the evidence that settles a group's kind may appear anywhere in the
     * document — including below the equivalence. Classifying on sight would make the answer
     * depend on statement order, which is the defect filed as #27.
     */
    private final List<Set<String>> equivalenceGroups = new ArrayList<>();
    /**
     * What the document says about each name's kind, and how firmly.
     *
     * <p>One store, replacing a pair of line maps per kind plus the membership sets that
     * mixed evidence with guesses. See {@link Findings} for why that distinction has to be
     * in the representation rather than remembered at each use.
     */
    private final Findings findings = new Findings();
    /**
     * The filler name that supplied each piece of object evidence, where there was one.
     *
     * <p>Resolved when the conflict is checked, not when the evidence is recorded, because
     * a named filler may turn out to be a <em>predicate</em> — `∃a.greaterThan` — and a
     * predicate says nothing about the kind of the role in front of it. Whether a name is a
     * predicate is only known once the whole document has been scanned, and depending on
     * where the `≝` line happened to sit would make the answer depend on document order.
     */
    private final Map<String, String> objectEvidenceFiller = new LinkedHashMap<>();

    // Roles the document states outright, via `X ⊑ owl:topObjectProperty` or
    // `X ⊑ owl:topDataProperty`, for the case inference cannot reach: a name whose kind
    // nothing in the document implies. Paired with `X ⊑ ⊤` — which lands in mustBeClass
    // like any other class use — it marks a pun.
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
    Set<String> getDatatypeNames()       { return datatypeNames; }

    /**
     * Names that are a role <em>and</em> a class — the puns.
     *
     * <p>Must be called after {@link #propagatePropertyTypes()}. A name qualifies when it is
     * classified as a role and there is class evidence for it: either the document said so
     * with {@code X ⊑ ⊤}, or it was used somewhere only a class can go.
     *
     * <p>The axiom visitor needs this because a name resolves to one kind, while a pun is one
     * kind in one position and the other in another. Knowing which names are punned lets a
     * class position take the class reading instead of failing.
     */
    Set<String> getPunnedNames() {
        Set<String> punned = new HashSet<>();
        for (String name : objectPropertyNames) {
            if (mustBeClass.contains(name)) punned.add(name);
        }
        for (String name : dataPropertyNames) {
            if (mustBeClass.contains(name)) punned.add(name);
        }
        return punned;
    }

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
        return visitChildren(ctx);
    }

    @Override
    public Void visitAllValuesFrom(DLESyntaxParser.AllValuesFromContext ctx) {
        classifyRestriction(ctx.propertyExpr(), ctx.primary());
        return visitChildren(ctx);
    }

    /**
     * If the primary is a bare NameAtom starting with a lowercase letter (and not
     * already known as a property), record it as a predicate name.
     */
    /**
     * A single-role restriction filler is a predicate reference only if it is a declared
     * predicate.
     *
     * <p>This used to invent one. A bare lower-case filler that was not already known to be
     * a property became a predicate on the strength of its case alone, which destroyed any
     * class whose name broke the convention: {@code A ⊑ ∃r.lowerC} put {@code lowerC} in
     * {@code predicateNames}, and the reader then built the skolem class
     * {@code dle:E_lowerC_…} and discarded both {@code lowerC} and {@code r} — exit 0,
     * document loading, two entities gone (#37).
     *
     * <p>It was also the order dependence in #27, because it read
     * {@code objectPropertyNames} and {@code dataPropertyNames} while the scan was still
     * filling them: the same document gave different answers depending on whether the
     * restriction sat above or below the statement that classified the name.
     *
     * <p>Both faults were the guess, not the mechanism. A real predicate is always declared
     * — {@code greaterThan(x,y) ≝ …}, or a multi-role reference {@code ∃a,b.p} whose comma
     * makes it unambiguous — and the visitor reads {@code predicateNames} only after this
     * scan has finished, so a declaration below the reference is found either way. With
     * nothing invented here there is nothing to get the order of.
     *
     * <p>Kept as a method, and still called, so the restriction sites continue to name what
     * they are doing; it now only asserts that the guess is gone.
     */
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

    // ── Annotation properties, the third kind ────────────────────────────────
    //
    // `@label`, `@doc`, `@db` and `@storage` name no property — each is a fixed rdfs: one —
    // so only these three forms put a document's own name in the annotation position.

    @Override
    public Void visitAnnAnnotation(DLESyntaxParser.AnnAnnotationContext ctx) {
        findings.record(ctx.name(1).getText(), Findings.Kind.ANNOTATION_PROPERTY,
            Findings.Certainty.POSITIONAL, ctx.start.getLine());
        return visitChildren(ctx);
    }

    @Override
    public Void visitAnnPropDomainAxiom(DLESyntaxParser.AnnPropDomainAxiomContext ctx) {
        findings.record(ctx.name(0).getText(), Findings.Kind.ANNOTATION_PROPERTY,
            Findings.Certainty.POSITIONAL, ctx.start.getLine());
        return visitChildren(ctx);
    }

    @Override
    public Void visitAnnPropRangeAxiom(DLESyntaxParser.AnnPropRangeAxiomContext ctx) {
        findings.record(ctx.name(0).getText(), Findings.Kind.ANNOTATION_PROPERTY,
            Findings.Certainty.POSITIONAL, ctx.start.getLine());
        return visitChildren(ctx);
    }

    // ── Textbook role axiom syntax ───────────────────────────────────────────
    //
    // Trans, Ref, Irref, Sym and Asym are defined for object properties only, so each is a
    // position only one kind can occupy — POSITIONAL evidence rather than a guess.

    @Override public Void visitTransitiveRoleAxiom(DLESyntaxParser.TransitiveRoleAxiomContext ctx)   { objectOnlyCharacteristic("Trans", ctx.propertyExpr(), ctx.start.getLine()); return visitChildren(ctx); }
    @Override public Void visitFunctionalRoleAxiom(DLESyntaxParser.FunctionalRoleAxiomContext ctx)   { classifyUnknownRole(PropertyExprs.coreNameText(ctx.propertyExpr())); return visitChildren(ctx); }
    @Override public Void visitReflexiveRoleAxiom(DLESyntaxParser.ReflexiveRoleAxiomContext ctx)     { objectOnlyCharacteristic("Ref", ctx.propertyExpr(), ctx.start.getLine()); return visitChildren(ctx); }
    @Override public Void visitIrreflexiveRoleAxiom(DLESyntaxParser.IrreflexiveRoleAxiomContext ctx) { objectOnlyCharacteristic("Irref", ctx.propertyExpr(), ctx.start.getLine()); return visitChildren(ctx); }
    @Override public Void visitSymmetricRoleAxiom(DLESyntaxParser.SymmetricRoleAxiomContext ctx)     { objectOnlyCharacteristic("Sym", ctx.propertyExpr(), ctx.start.getLine()); return visitChildren(ctx); }
    @Override public Void visitAsymmetricRoleAxiom(DLESyntaxParser.AsymmetricRoleAxiomContext ctx)   { objectOnlyCharacteristic("Asym", ctx.propertyExpr(), ctx.start.getLine()); return visitChildren(ctx); }
    @Override public Void visitDisjointRoleAxiom(DLESyntaxParser.DisjointRoleAxiomContext ctx) {
        ctx.propertyExpr().forEach(p -> classifyUnknownRole(PropertyExprs.coreNameText(p)));
        return visitChildren(ctx);
    }

    // Classifies a role as object property only if not already established as data.
    // Func(p) and Disj(p,q) are syntactically ambiguous — they apply to both data
    // and object properties.  This avoids overwriting a definitive data classification.
    /**
     * Whether a filler is {@code ⊤}, which belongs to both hierarchies and so pins neither.
     *
     * <p>{@code ∃p.⊤ ⊑ C} is how DLe writes a domain, for a data property as much as an
     * object one, so the filler there is no evidence of kind at all.
     */
    private static boolean isTopFiller(@Nullable DLESyntaxParser.AtomContext atom) {
        return atom instanceof DLESyntaxParser.TopAtomContext;
    }

    // ── Assertions about individuals ────────────────────────────────────────
    //
    // These are the strongest evidence in the language. `(a,b):r` can only be an object
    // property assertion and `(a,v):d` can only be a data property one, so the property's
    // kind is settled outright — no case convention, no propagation, no guess. The class in
    // `a:C` is likewise definitively a class.
    //
    // The individuals are deliberately left unclassified. An individual is neither a class
    // nor a role, and the visitor builds it as a named individual from the axiom itself;
    // putting it in mustBeClass would make it a barrier to role propagation for no reason.

    @Override
    public Void visitObjectAssertionAxiom(DLESyntaxParser.ObjectAssertionAxiomContext ctx) {
        String property = ctx.name(2).getText();
        classifyUnknownRole(property);
        recordKindEvidence(property, false, ctx.start.getLine());
        return null;
    }

    @Override
    public Void visitNegativeObjectAssertionAxiom(
            DLESyntaxParser.NegativeObjectAssertionAxiomContext ctx) {
        String property = ctx.name(2).getText();
        classifyUnknownRole(property);
        recordKindEvidence(property, false, ctx.start.getLine());
        return null;
    }

    @Override
    public Void visitDataAssertionAxiom(DLESyntaxParser.DataAssertionAxiomContext ctx) {
        String property = ctx.name(1).getText();
        dataPropertyNames.add(property);
        objectPropertyNames.remove(property);
        recordKindEvidence(property, true, ctx.start.getLine());
        return null;
    }

    @Override
    public Void visitNegativeDataAssertionAxiom(
            DLESyntaxParser.NegativeDataAssertionAxiomContext ctx) {
        String property = ctx.name(1).getText();
        dataPropertyNames.add(property);
        objectPropertyNames.remove(property);
        recordKindEvidence(property, true, ctx.start.getLine());
        return null;
    }

    // Identity and distinctness name individuals only. An individual is neither a class
    // nor a role, so there is nothing to classify and nothing to propagate — and visiting
    // the children would be worse than useless, since a name in one of these positions
    // must not be drawn into the class or role hierarchy by some later guess.

    @Override
    public Void visitSameIndividualAxiom(DLESyntaxParser.SameIndividualAxiomContext ctx) {
        return null;
    }

    @Override
    public Void visitDifferentIndividualsAxiom(
            DLESyntaxParser.DifferentIndividualsAxiomContext ctx) {
        return null;
    }

    @Override
    public Void visitClassAssertionAxiom(DLESyntaxParser.ClassAssertionAxiomContext ctx) {
        // Whichever way the visitor resolves the split, the class side is a class either
        // way, and that is all this pass needs. Only a bare name is recorded; a complex
        // expression is already unambiguous and its parts are classified by visiting it.
        String cls = singleBareName(ctx.classExpr());
        if (cls != null) markClass(cls, Findings.Certainty.POSITIONAL, ctx.start.getLine());
        return visitChildren(ctx);
    }


    /**
     * Classifies a name as an object property and records that as evidence.
     *
     * <p>For the positions that are object-only by construction — a chain's super-property,
     * and either side of an inverse. They used to call {@code objectPropertyNames.add}
     * directly, so {@code recordKindEvidence} was never reached and the conflict check could
     * not see them.
     */
    private void recordObjectOnly(String name, org.antlr.v4.runtime.ParserRuleContext ctx) {
        objectPropertyNames.add(name);
        recordKindEvidence(name, false, ctx.start.getLine());
    }

    private void recordObjectOnly(DLESyntaxParser.NameContext nameCtx,
                                  org.antlr.v4.runtime.ParserRuleContext ctx) {
        recordObjectOnly(nameCtx.getText(), ctx);
    }

    /** Notes that structure forced this name to one kind, keeping the first line for each. */
    /** A position only one kind of property can occupy. */
    private void recordKindEvidence(String name, boolean isData, int line) {
        recordKind(name, isData, Findings.Certainty.POSITIONAL, line);
    }

    private void recordKind(String name, boolean isData, Findings.Certainty certainty,
                            int line) {
        findings.record(name,
            isData ? Findings.Kind.DATA_PROPERTY : Findings.Kind.OBJECT_PROPERTY,
            certainty, line);
    }

    /**
     * Refuses a name the document forces to be both an object and a data property.
     *
     * <p>OWL 2 DL requires the two sets of property IRIs to be disjoint, and DLe has one
     * statement per kind, so such a name can be neither written nor loaded. It used to be
     * resolved quietly by letting data win, which gave four different outcomes for four
     * documents saying the same contradictory thing: two misleading messages about datatypes
     * and class fillers, one accurate message, and one document accepted in silence that
     * then declared the name as both kinds at once.
     *
     * <p>A class and a property on one name is a different matter — that is a pun, which is
     * legal and supported. This is only about the two <em>property</em> kinds.
     */
    /**
     * Reports a name the document gives two different property kinds.
     *
     * <p>One rule for all three pairs now. It used to be a loop over object-versus-data
     * followed by two more for the annotation pairs, which is why the annotation kind went
     * unwatched for so long — adding a kind meant remembering to add another loop.
     *
     * <p>A class and a property is not a conflict: that is the pun DLe carries on purpose.
     * Nor is a guess ever half of one — it loses to the evidence instead, which is what
     * {@link Findings.Certainty#isEvidence} decides.
     */
    /**
     * A characteristic OWL defines only for object properties, applied to a data property.
     *
     * <p>Checked before the generic kind clash so the message can name the keyword. The
     * clash would otherwise be reported, correctly but unhelpfully, as one IRI being used as
     * both kinds of property.
     */
    private void reportObjectOnlyCharacteristicOnDataProperty() {
        for (Map.Entry<String, String> use : objectOnlyCharacteristicUse.entrySet()) {
            Findings.Finding data =
                findings.firmestOf(use.getKey(), Findings.Kind.DATA_PROPERTY);
            if (data == null || !data.certainty.isEvidence()) continue;
            throw new DLESemanticException(
                use.getValue() + " applies to object properties only, and " + use.getKey()
                    + " is used as a data property in this document. There is no "
                    + use.getValue() + " for data properties; Func is the only"
                    + " characteristic that has both forms.",
                data.line, 0);
        }
    }

    private void reportKindConflicts() {
        reportObjectOnlyCharacteristicOnDataProperty();
        for (String name : findings.names()) {
            Findings.Finding[] clash = findings.propertyKindConflict(name);
            if (clash == null) continue;
            // A predicate filler is not evidence; see objectEvidenceFiller.
            String filler = objectEvidenceFiller.get(name);
            if (filler != null && predicateNames.contains(filler)) continue;
            throw new DLESemanticException(
                name + " is used as " + clash[0].kind.description() + " on line "
                    + clash[0].line + " and as " + clash[1].kind.description()
                    + " on line " + clash[1].line
                    + ". An IRI can be one or the other, not both.",
                Math.max(clash[0].line, clash[1].line), 0);
        }
    }

    /**
     * A name known to be a role, without anything saying which kind.
     *
     * <p>Object property is the fallback, so the finding is recorded as such: DEFAULTED, not
     * evidence. That is the distinction the old sets could not make — this went into
     * {@code objectPropertyNames} and was then indistinguishable from a name the document
     * had actually put in an object-only position.
     */
    /**
     * Marks a name as a class, recording why.
     *
     * <p>Paired with the set rather than replacing it: the set is what propagation walks,
     * and the finding is what makes a class visible to the conflict rule — which is how a
     * pun is told apart from a contradiction. Recorded through one method so the two cannot
     * drift apart.
     */
    private void markClass(String name, Findings.Certainty certainty, int line) {
        mustBeClass.add(name);
        findings.record(name, Findings.Kind.CLASS, certainty, line);
    }

    /**
     * Where each object-only characteristic was applied, for the diagnostic.
     *
     * <p>The kind clash is reportable from the findings alone, but the message would be the
     * generic one — "p is used as an object property on line 3 and as a data property on
     * line 2" — where naming the keyword and saying that Func is the exception is far more
     * use. Kept in the same shape as {@code invertedRoleUse}, which exists for the same
     * reason.
     */
    private final Map<String, String> objectOnlyCharacteristicUse = new LinkedHashMap<>();

    /**
     * Carries a kind across a subsumption edge, recording that that is how it got there.
     *
     * <p>PROPAGATED, never POSITIONAL: a name reached this way has no position of its own
     * saying what it is, and recording otherwise would let it contradict something later on
     * evidence it does not have. It is still evidence — firmer than the case convention —
     * which is what lets it settle a name the convention would have guessed wrong.
     *
     * @return true when this changed anything, for the fixpoint loop
     */
    private boolean propagateKind(String from, String to, Findings.Kind kind) {
        boolean changed = kind == Findings.Kind.DATA_PROPERTY
            ? dataPropertyNames.add(to) | objectPropertyNames.remove(to)
            : objectPropertyNames.add(to);
        if (!findings.hasEvidenceFor(to, kind)) {
            Findings.Finding source = findings.firmestOf(from, kind);
            findings.record(to, kind, Findings.Certainty.PROPAGATED,
                source == null ? 0 : source.line);
            changed = true;
        }
        return changed;
    }

    /** A characteristic OWL defines for object properties only. */
    private void objectOnlyCharacteristic(String keyword,
                                          DLESyntaxParser.PropertyExprContext ctx, int line) {
        String name = PropertyExprs.coreNameText(ctx);
        objectPropertyNames.add(name);
        findings.record(name, Findings.Kind.OBJECT_PROPERTY,
            Findings.Certainty.POSITIONAL, line);
        objectOnlyCharacteristicUse.putIfAbsent(name, keyword);
    }

    private void classifyUnknownRole(String name) {
        if (!dataPropertyNames.contains(name)) {
            objectPropertyNames.add(name);
            findings.record(name, Findings.Kind.OBJECT_PROPERTY,
                Findings.Certainty.DEFAULTED, 0);
        }
    }

    // ── Property chain axioms ────────────────────────────────────────────────

    @Override
    public Void visitSubPropertyChainAxiom(DLESyntaxParser.SubPropertyChainAxiomContext ctx) {
        // All positions in a chain axiom are object properties
        for (DLESyntaxParser.PropertyExprContext propCtx : ctx.chainExpr().propertyExpr()) {
            classifyProp(propCtx, false, true);
        }
        // The super of a chain is object-only, exactly as the members are, so it is
        // evidence too. It used not to be recorded, which left the conflict check blind: a
        // document with `(a,"5"):d` and `p ∘ q ⊑ d` declared `d` as both kinds in silence.
        recordObjectOnly(PropertyExprs.coreName(ctx.propertyExpr()), ctx);
        return visitChildren(ctx);
    }

    @Override
    public Void visitPropertyChainEquivAxiom(DLESyntaxParser.PropertyChainEquivAxiomContext ctx) {
        // `d ≡ p ∘ q` still takes a bare name on the left; only the nine rules that OWL
        // defines over a property expression were widened.
        recordObjectOnly(ctx.name(), ctx);
        for (DLESyntaxParser.PropertyExprContext propCtx : ctx.chainExpr().propertyExpr()) {
            classifyProp(propCtx, false, true);
        }
        return visitChildren(ctx);
    }

    // ── Chained equiv-sub axiom ──────────────────────────────────────────────

    @Override
    public Void visitChainedEquivSubAxiom(DLESyntaxParser.ChainedEquivSubAxiomContext ctx) {
        // A ≡ B ⊑ C — all three positions are object properties
        for (DLESyntaxParser.PropertyExprContext propCtx : ctx.propertyExpr()) {
            classifyProp(propCtx, false, true);
        }
        return visitChildren(ctx);
    }

    // ── Inverse property atom ─────────────────────────────────────────────────

    @Override
    public Void visitInversePropertyAtom(DLESyntaxParser.InversePropertyAtomContext ctx) {
        // The base name is always an object property — a data property cannot have an
        // inverse, since that would put a literal in the subject position. Recording it as
        // evidence is what makes a contradiction reportable: without it, `(a,"5"):d` beside
        // `q ≡ d⁻` produced an ontology declaring `d` as both kinds, in silence.
        recordObjectOnly(PropertyExprs.coreNameText(ctx.propertyExpr()), ctx);
        return visitChildren(ctx);
    }

    // ── Equiv axiom classification ───────────────────────────────────────────

    @Override
    public Void visitEquivAxiom(DLESyntaxParser.EquivAxiomContext ctx) {
        // Every operand, not just the first two: `≡` chains, so `A ≡ B ≡ C` has three and
        // the third used to be invisible here while the reader built an axiom from it.
        List<DLESyntaxParser.ClassExprContext> operands = ctx.classExpr();
        int n = operands.size();

        // p ≡ q⁻ — an inverse is object-only, so each bare name beside one is evidence.
        // Recording it is what lets a contradiction be reported rather than resolved
        // silently into the dual-kind ontology OWL 2 DL forbids.
        boolean anyInverse = false;
        for (int i = 0; i < n; i++) {
            if (singleInverseAtom(operands.get(i)) != null) anyInverse = true;
        }
        if (anyInverse) {
            for (int i = 0; i < n; i++) {
                String bare = singleBareName(operands.get(i));
                if (bare != null && singleInverseAtom(operands.get(i)) == null) {
                    recordObjectOnly(bare, ctx);
                }
            }
        }

        // All bare, all spelled like roles — `p ≡ q ≡ r`. Says "roles", not which kind, so
        // it goes through classifyUnknownRole and the group below settles the kind.
        List<String> bare = new ArrayList<>();
        boolean allBare = true;
        for (int i = 0; i < n; i++) {
            String name = singleBareName(operands.get(i));
            if (name == null) allBare = false; else bare.add(name);
        }
        if (allBare && bare.stream().allMatch(this::caseSuggestsRole)) {
            bare.forEach(this::classifyUnknownRole);
        }
        // Tied together whatever their spelling, so evidence on any one of them reaches the
        // rest. Only groups that are entirely bare names can be properties at all.
        if (allBare && bare.size() > 1) {
            equivalenceGroups.add(new java.util.LinkedHashSet<>(bare));
        }

        // A ≡ (complex) → A is a class. An inverse atom is a property expression, not a
        // complex class, so a name beside one is excluded.
        if (!allBare) {
            for (int i = 0; i < n; i++) {
                String name = singleBareName(operands.get(i));
                if (name == null) continue;
                boolean besideAComplexClass = false;
                for (int j = 0; j < n; j++) {
                    if (j != i && singleBareName(operands.get(j)) == null
                            && singleInverseAtom(operands.get(j)) == null) {
                        besideAComplexClass = true;
                    }
                }
                if (besideAComplexClass) {
                    markClass(name, Findings.Certainty.POSITIONAL, ctx.start.getLine());
                }
            }
        }
        return visitChildren(ctx);
    }

    // ── Sub-property pair collection ─────────────────────────────────────────

    @Override
    public Void visitSubClassAxiom(DLESyntaxParser.SubClassAxiomContext ctx) {
        // DisjointObjectProperties: p ⊓ q ⊑ ⊥
        //
        // Through classifyUnknownRole, not addAll. Disjointness holds between properties of
        // either kind, so this line says "roles" and not which — and adding to the object
        // set unconditionally overwrote a data classification the document had already
        // made. Which answer you got then depended on where this line sat: with the
        // datatype restrictions above it the names came out object properties, below it
        // data ones, from the same three axioms. The `Disj(p, q)` spelling of the same
        // axiom now routes by kind, and this one has to agree with it.
        List<String> intersected = allIntersectedBareNames(ctx.classExpr(0));
        if (intersected != null && isBottomClassExpr(ctx.classExpr(1))
                // caseSuggestsRole, not a hand-written case test. This was the last place
                // in the file spelling the convention itself, and it spelled a different
                // one: charAt(0) on the whole name, so a prefixed or digit-initial name
                // failed it. `EX:p ⊓ EX:q ⊑ ⊥` therefore read as a class intersection
                // while `Disj(EX:p, EX:q)` — the same axiom — read as properties.
                && intersected.stream().allMatch(this::caseSuggestsRole)) {
            intersected.forEach(this::classifyUnknownRole);
        }

        String lhs = singleBareName(ctx.classExpr(0));
        String rhs = singleBareName(ctx.classExpr(1));

        // X ⊑ ⊤ needs no special handling here: ⊤ is not a name, so the `rhs == null`
        // branch at the end of this method already puts X in mustBeClass, which is the
        // barrier. It stays an ordinary subsumption otherwise, which is what keeps the
        // corpus documents that open their blocks with it unaffected.

        // X ⊑ owl:topObjectProperty — a declaration that X is a role, not a subsumption
        // to record.  Written by the storer for a name whose kind the reader could not
        // otherwise infer: a pun, or one that breaks the case convention.
        String topProperty = rhs == null ? null : topPropertyIri(rhs);
        if (lhs != null && topProperty != null) {
            explicitRole.add(lhs);
            // STATED: the document says the kind outright, which is firmer than any
            // position and firmer than anything propagation can reach.
            recordKind(lhs, TOP_DATA_PROPERTY_IRI.equals(topProperty),
                Findings.Certainty.STATED, ctx.start.getLine());
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
            // Heuristic: a camelCase local part is almost certainly a property. A prefix
            // does not change that — see caseSuggestsRole, which is also what the writer
            // consults before deciding a kind needs stating.
            if (!objectPropertyNames.contains(lhs) && !dataPropertyNames.contains(lhs)
                    && !objectPropertyNames.contains(rhs) && !dataPropertyNames.contains(rhs)
                    && caseSuggestsRole(lhs) && caseSuggestsRole(rhs)) {
                objectPropertyNames.add(lhs);
                objectPropertyNames.add(rhs);
                // GUESSED, and recorded as such. The convention is the weakest thing that
                // can put a name in a set, and propagation must not carry it as though the
                // document had said it. Recording nothing here is what let a guess be
                // re-exported as PROPAGATED evidence and then outrank a real position:
                // `appState ⊑ calcList` / `calcList ⊑ calcValue` / `⊤ ⊑ ∀appState.{"a"}`
                // guessed both parents object, and the data evidence could not get past it.
                findings.record(lhs, Findings.Kind.OBJECT_PROPERTY,
                    Findings.Certainty.GUESSED, ctx.start.getLine());
                findings.record(rhs, Findings.Kind.OBJECT_PROPERTY,
                    Findings.Certainty.GUESSED, ctx.start.getLine());
            }
        }
        // A ⊑ B⁻ — lhs must be an object property (inverse forces the interpretation),
        // which makes it evidence.
        if (lhs != null && singleInverseAtom(ctx.classExpr(1)) != null) {
            recordObjectOnly(lhs, ctx);
        }
        // A ⊑ (complex) → A is definitively a class; (complex) ⊑ B → B is a class.
        // Exclude inverse-atom RHS/LHS since those are property expressions.
        if (lhs != null && rhs == null && singleInverseAtom(ctx.classExpr(1)) == null)
            markClass(lhs, Findings.Certainty.POSITIONAL, ctx.start.getLine());
        if (lhs == null && rhs != null && singleInverseAtom(ctx.classExpr(0)) == null)
            markClass(rhs, Findings.Certainty.POSITIONAL, ctx.start.getLine());
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
        // Before anything is propagated: a contradiction in the direct evidence has to be
        // reported from the evidence itself, because propagation resolves it silently.
        reportKindConflicts();

        // Equivalence groups first, and to a fixpoint, because they chain: `d ≡ e` with
        // `e ≡ f` elsewhere makes all three one kind. Equivalence holds within a kind, so
        // evidence on any member settles every member — and two members with opposite
        // evidence is a contradiction in the document, not something to resolve by guessing.
        boolean settled = true;
        while (settled) {
            settled = false;
            for (Set<String> group : equivalenceGroups) {
                String dataMember = null;
                String objectMember = null;
                Findings.Finding dataFound = null;
                Findings.Finding objectFound = null;
                for (String name : group) {
                    Findings.Finding d =
                        findings.firmestOf(name, Findings.Kind.DATA_PROPERTY);
                    Findings.Finding o =
                        findings.firmestOf(name, Findings.Kind.OBJECT_PROPERTY);
                    if (d != null && d.certainty.isEvidence()) {
                        dataMember = name;
                        dataFound = d;
                    }
                    if (o != null && o.certainty.isEvidence()) {
                        objectMember = name;
                        objectFound = o;
                    }
                }
                if (dataFound != null && objectFound != null) {
                    throw new DLESemanticException(
                        "this equivalence makes " + objectMember + ", an object property on"
                            + " line " + objectFound.line + ", equivalent to "
                            + dataMember + ", a data property on line "
                            + dataFound.line + ". Equivalence holds between"
                            + " properties of one kind.",
                        Math.max(objectFound.line, dataFound.line), 0);
                }
                // Nothing in the group is a property, so it is a class equivalence.
                if (dataFound == null && objectFound == null) continue;
                boolean isData = dataFound != null;
                int line = isData ? dataFound.line : objectFound.line;
                Findings.Kind reached = isData
                    ? Findings.Kind.DATA_PROPERTY : Findings.Kind.OBJECT_PROPERTY;
                for (String name : group) {
                    if (mustBeClass.contains(name)) continue;
                    if (isData) {
                        settled |= dataPropertyNames.add(name);
                        settled |= objectPropertyNames.remove(name);
                    } else {
                        settled |= objectPropertyNames.add(name);
                    }
                    // Reached across an equivalence, so it is propagation rather than a
                    // position of its own — which is what keeps it from being read later as
                    // firm evidence that could contradict something.
                    if (!findings.hasEvidenceFor(name, reached)) {
                        findings.record(name, reached, Findings.Certainty.PROPAGATED, line);
                        settled = true;
                    }
                }
            }
        }

        // Phase 1: propagate mustBeClass upward through sub-property chains.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Set<String>> e : subPropertyPairs.entrySet()) {
                if (mustBeClass.contains(e.getKey())) {
                    for (String sup : e.getValue()) {
                        if (mustBeClass.add(sup)) {
                            Findings.Finding source =
                                findings.firmestOf(e.getKey(), Findings.Kind.CLASS);
                            findings.record(sup, Findings.Kind.CLASS,
                                Findings.Certainty.PROPAGATED,
                                source == null ? 0 : source.line);
                            changed = true;
                        }
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
                    // The guards match the object-property rules below. They used not to,
                    // and data-ness crossed every barrier: a name the document had stated
                    // to be an object property, or had used where only a class can go, was
                    // overwritten and the document then failed to parse.
                    //
                    // That includes the case guess on the downward edge. It was applied only
                    // to object-property puns, so `Upper ⊑ pun` kept Upper a class under a
                    // punned object property and silently made it a data property under a
                    // punned data one — the same document, the same shape, two answers.
                    // Neither direction may cross a name the document has already forced
                    // to be an object property. Data propagation used to override that, so
                    // `∃x.Wheel ⊑ Car` with `x ⊑ s` and `s ⊑ owl:topDataProperty` quietly
                    // made x a data property and then complained that Wheel was not a
                    // datatype — blaming the filler for a conflict two lines away. Left
                    // alone, x stays an object property, s stays a data one, and the pair
                    // is reported for what it is: a sub-property axiom across the two
                    // hierarchies, which OWL has no way to express.
                    // Propagation reads the *findings*, not the sets. A name is only a
                    // source of a kind when something in the document evidenced it: a
                    // position, a statement, or an earlier propagation step. Set membership
                    // is weaker than that, because the case convention writes into the sets
                    // on a guess, and a guess carried across an edge and stamped PROPAGATED
                    // becomes indistinguishable from evidence — which is how a guessed
                    // parent came to outrank a stated child and refuse a sound document.
                    if (findings.hasEvidenceFor(sub, Findings.Kind.DATA_PROPERTY)
                            && !mustBeClass.contains(sup)
                            && !explicitRole.contains(sup)
                            && !findings.hasEvidenceFor(sup, Findings.Kind.OBJECT_PROPERTY)) {
                        changed |= propagateKind(sub, sup, Findings.Kind.DATA_PROPERTY);
                    }
                    if (findings.hasEvidenceFor(sup, Findings.Kind.DATA_PROPERTY)
                            && !mustBeClass.contains(sub)
                            && !explicitRole.contains(sub)
                            && !findings.hasEvidenceFor(sub, Findings.Kind.OBJECT_PROPERTY)
                            && !(mustBeClass.contains(sup) && looksLikeAClass(sub))) {
                        changed |= propagateKind(sup, sub, Findings.Kind.DATA_PROPERTY);
                    }
                    // Object property propagation is blocked at mustBeClass nodes.
                    if (findings.hasEvidenceFor(sub, Findings.Kind.OBJECT_PROPERTY)
                            && !dataPropertyNames.contains(sup)
                            && !mustBeClass.contains(sup))
                        changed |= propagateKind(sub, sup, Findings.Kind.OBJECT_PROPERTY);
                    if (findings.hasEvidenceFor(sup, Findings.Kind.OBJECT_PROPERTY)
                            && !dataPropertyNames.contains(sub)
                            && !mustBeClass.contains(sub)
                            && !(mustBeClass.contains(sup) && looksLikeAClass(sub)))
                        changed |= propagateKind(sup, sub, Findings.Kind.OBJECT_PROPERTY);
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
    /**
     * A name's full IRI, or null when no declared prefix covers it.
     *
     * <p>A bare name resolves against the default prefix. It used to resolve to nothing,
     * which was invisible in the ordinary case — a bare name in the document's own namespace
     * is not a datatype either way — but wrong whenever the default namespace is one that
     * matters: with {@code @prefix : <http://www.w3.org/2001/XMLSchema#>}, the bare
     * {@code string} is {@code xsd:string} and was not recognised as a datatype at all.
     */
    private String resolve(String name) {
        int colon = name.indexOf(':');
        if (colon < 0) {
            String base = prefixes.get(":");
            return base == null ? null : base + name;
        }
        String base = prefixes.get(name.substring(0, colon + 1));
        return base == null ? null : base + name.substring(colon + 1);
    }

    /** The XSD namespace, whose members are datatypes however their names are spelled. */
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";


    /**
     * Whether the case convention offers this name as a role.
     *
     * <p>The convention is about the local part: {@code ex:hasPart} names a role as plainly
     * as {@code hasPart} does. It used to be applied only to bare names, on the grounds that
     * "the prefix letter carries no information about the local resource type" — true of the
     * letter, but it threw away the local part along with it. A prefixed property hierarchy
     * was therefore read as a class hierarchy, losing both declarations and the axiom.
     *
     * <p>The exception is a datatype, because a datatype is not a class and is not a role,
     * and the standard ones are spelled in lower case. A resolved IRI is needed rather than
     * the text, since a document may bind {@code xsd:} elsewhere or reach the XSD namespace
     * under another prefix.
     *
     * <p>{@link DLESyntaxStorerBase} mirrors this to decide when to state a kind. The two
     * disagreeing is what the kind statements are for, so they share this one method.
     */
    static boolean caseSuggestsRole(String name, @Nullable String resolvedIri) {
        if (isDatatypeIri(resolvedIri)) return false;
        int colon = name.lastIndexOf(':');
        String local = colon < 0 ? name : name.substring(colon + 1);
        return !local.isEmpty() && Character.isLowerCase(local.charAt(0));
    }

    /**
     * The reader's own view of the name, resolving it with this document's prefixes.
     *
     * <p>Also excludes a datatype the *document* defines, which the static form cannot see:
     * it is given a resolved IRI and the built-in list, and {@code datatypeNames} is instance
     * state. So {@code code \u2261 [xsd:string \u2293 [minLength 3]]} followed by
     * {@code code \u2291 thing} guessed {@code code} to be a role on its spelling, and the
     * name ended up declared as both a datatype and an object property.
     */
    private boolean caseSuggestsRole(String name) {
        if (isDataTypeName(name)) return false;
        return caseSuggestsRole(name, resolve(name));
    }

    /**
     * Whether a name's local part begins with an upper-case letter, as a guess of last resort.
     *
     * <p>Long-standing DL practice: concepts are capitalised, roles are not. It is consulted
     * in exactly one place — the downward edge of role propagation, and there only when the
     * name above is <em>punned</em>, so that both readings are genuinely available.
     *
     * <p>That restriction matters. Below a punned name a capitalised child is ambiguous, and
     * without the guess a concept there is silently converted into a role and leaves the
     * class signature. Below an ordinary role there is no ambiguity — a pure role has no
     * class reading to offer — so the child must be a role whatever its case, and applying
     * the guess there turned {@code IsPartOf ⊑ hasPart} into a class subsumption. PascalCase
     * role names are a normal choice and nothing should punish them.
     *
     * <p>SNOMED CT is unaffected either way: its identifiers are numeric and carry no case
     * signal, so its attribute children cross downward as they must.
     *
     * <p>It must never override evidence. An earlier revision used it as a barrier on the
     * <em>upward</em> edge as well, where a capitalised object property already used in a
     * restriction was definitively a role, and the guess invented a pun and refiled its
     * sub-property axiom as a subsumption.
     */
    private static boolean looksLikeAClass(String name) {
        int colon = name.lastIndexOf(':');
        String local = colon < 0 ? name : name.substring(colon + 1);
        return !local.isEmpty() && Character.isUpperCase(local.charAt(0));
    }

    /**
     * Whether the class hierarchy is where this name belongs, blocking role classification
     * from travelling further up.
     *
     * <p>{@code mustBeClass} is the barrier, and it is populated by ordinary use: a name in a
     * position only a class can occupy, including the {@code X ⊑ ⊤} that states a class.
     * That is what stops a punned name dragging the concepts above it into the role
     * hierarchy, and it predates the kind statements.
     *
     * <p>The case of the name is deliberately <em>not</em> consulted here. An earlier revision
     * treated a capitalised name as a barrier, which overrode direct evidence: a capitalised
     * object property used in a restriction — {@code HasPart}, {@code Broader} — was
     * definitively a role, and the convention invented a pun and refiled its sub-property
     * axiom as a subsumption. It also bought nothing: removing it leaves the SNOMED CT
     * classification byte-identical, because {@code mustBeClass} was already doing the work.
     * The convention still governs what the <em>writer</em> must state, which is where a
     * guess is appropriate, and nothing downstream has to trust it.
     */
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
        classifyProp(propCtx, data, data || !isTopFiller(Parens.atomOf(fillerCtx)),
            primaryBareName(fillerCtx));
        // Non-data fillers are class expressions; seed mustBeClass so phase-1 propagation
        // can mark the full concept-hierarchy chain before role propagation runs.
        if (!data) {
            String fillerName = primaryBareName(fillerCtx);
            if (fillerName != null) {
                markClass(fillerName, Findings.Certainty.POSITIONAL,
                    propCtx.start.getLine());
            }
        }
    }

    private void classifyFromClassExpr(DLESyntaxParser.PropertyExprContext propCtx,
                                       DLESyntaxParser.ClassExprContext fillerCtx) {
        boolean data = isDataClassExpr(fillerCtx);
        classifyProp(propCtx, data, data || !isTopFiller(Parens.atomOf(fillerCtx)),
            singleBareName(fillerCtx));
        if (!data) {
            String fillerName = singleBareName(fillerCtx);
            if (fillerName != null) {
                markClass(fillerName, Findings.Certainty.POSITIONAL,
                    propCtx.start.getLine());
            }
        }
    }

    private void classifyProp(DLESyntaxParser.PropertyExprContext propCtx, boolean isData) {
        classifyProp(propCtx, isData, false);
    }

    /**
     * Classifies a property occurrence, recording evidence only when the position pins it.
     *
     * <p>{@code pinsKind} is separate from {@code isData} on purpose. Several callers pass
     * {@code isData = false} as a <em>default</em> rather than a finding: an unqualified
     * cardinality has no filler to inspect, and a multi-role predicate restriction's filler
     * is a predicate, which says nothing about the roles. Above all, the domain idiom
     * {@code ∃p.⊤ ⊑ C} has {@code ⊤} as its filler, and {@code ⊤} is kind-neutral — the
     * wildlife corpus writes exactly that for a data property, immediately above the range
     * that proves it is one. Treating those defaults as evidence made the conflict check
     * reject three perfectly good documents.
     */
    private void classifyProp(DLESyntaxParser.PropertyExprContext propCtx, boolean isData,
                              boolean pinsKind) {
        classifyProp(propCtx, isData, pinsKind, null);
    }

    private void classifyProp(DLESyntaxParser.PropertyExprContext propCtx, boolean isData,
                              boolean pinsKind, @Nullable String fillerName) {
        String name = PropertyExprs.coreNameText(propCtx);
        boolean inverse = PropertyExprs.isInverse(propCtx);
        if (pinsKind || inverse) {
            // An inverse is object-only whatever the filler, which is evidence in itself.
            recordKindEvidence(name, isData && !inverse, propCtx.start.getLine());
            if (!isData && fillerName != null) {
                objectEvidenceFiller.putIfAbsent(name, fillerName);
            }
        }
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
                // Only classify as object if not already established as data. Recorded as
                // DEFAULTED when the position did not pin the kind: `≥2 r` and `∃r.⊤ ⊑ C`
                // say "a role" and not which, and the fallback is object property. Saying so
                // is what stops it being mistaken for evidence later.
                objectPropertyNames.add(name);
                findings.record(name, Findings.Kind.OBJECT_PROPERTY,
                    pinsKind ? Findings.Certainty.POSITIONAL : Findings.Certainty.DEFAULTED,
                    propCtx.start.getLine());
            }
        }
    }

    /**
     * A note on what is deliberately *not* inferred.
     *
     * <p>A single-role restriction filler used to be taken for a predicate reference when its
     * name was lower-case and not already known to be a property. That destroyed any class
     * whose name broke the convention — {@code A ⊑ ∃r.lowerC} built the skolem class
     * {@code dle:E_lowerC_…} and discarded both the class and the property, with the document
     * loading cleanly (#37) — and it was the order dependence in #27 besides, because it read
     * the property sets while the scan was still filling them.
     *
     * <p>Both faults were the guess, not the mechanism. A predicate is always declared, by
     * {@code ≝} or by a multi-role reference whose comma makes it unambiguous, and those are
     * recorded by {@code visitPredicateDefinition} and the multi-role visitors. Nothing here
     * needs to be guessed, so nothing is.
     */

    /**
     * Records every datatype the document defines, before anything reads the answer.
     *
     * <p>A separate sweep, and it has to be: a restriction is classified as the scan passes
     * it, so a definition further down the file would not have been seen yet. Doing this
     * inside the main scan gave {@code ⊤ ⊑ ∀d.MyType} one answer above its definition and
     * another below it — the same order dependence just removed from predicates (#27), and it
     * would have been careless to reintroduce it here.
     *
     * <p>Only {@code name ≡ <data range>} is looked for. A data range on one side cannot be a
     * class equivalence, so the shape is unambiguous without knowing anything else yet.
     */
    void collectDatatypeDefinitions(org.antlr.v4.runtime.tree.ParseTree tree) {
        // Prefixes first. Every judgement below resolves a name, and this pass runs before
        // the main scan, which is where prefix declarations were being recorded — so a
        // document that rebinds `xsd:` elsewhere had its own `xsd:notAType` resolved against
        // the real XSD namespace and called a datatype. Resolution cannot depend on how far
        // down the file the reader has got.
        collectPrefixes(tree);
        // Then to a fixpoint, because the walk reads the set it is filling: isDataClassExpr
        // asks isDataTypeName, so `Alias \u2261 Code` is only recognised once `Code` is known.
        // One pass in document order gave a different answer for every ordering of the same
        // statements — `A1 \u2261 xsd:string / A2 \u2261 A1 / A3 \u2261 A2` found three
        // datatypes, two, or one across the six permutations, and the pass that found fewest
        // went on to declare a datatype as a class. This pre-pass exists to remove an order
        // dependence, so it must not have one of its own.
        int before;
        do {
            before = datatypeNames.size();
            collectDatatypeDefinitionsOnce(tree);
        } while (datatypeNames.size() > before);
    }

    /** Records every prefix declaration in the tree, wherever it appears. */
    private void collectPrefixes(org.antlr.v4.runtime.tree.ParseTree tree) {
        if (tree instanceof DLESyntaxParser.PrefixDeclContext) {
            visitPrefixDecl((DLESyntaxParser.PrefixDeclContext) tree);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectPrefixes(tree.getChild(i));
        }
    }

    private void collectDatatypeDefinitionsOnce(org.antlr.v4.runtime.tree.ParseTree tree) {
        if (tree instanceof DLESyntaxParser.EquivAxiomContext) {
            List<DLESyntaxParser.ClassExprContext> operands =
                ((DLESyntaxParser.EquivAxiomContext) tree).classExpr();
            for (int i = 0; i < operands.size(); i++) {
                String bareName = singleBareName(operands.get(i));
                if (bareName == null || isDatatypeIri(resolve(bareName))) continue;
                for (int j = 0; j < operands.size(); j++) {
                    if (j != i && isDataClassExpr(operands.get(j))) {
                        datatypeNames.add(bareName);
                        // DECLARED: the document defines it, which outranks any built-in
                        // list and any guess about its name.
                        findings.record(bareName, Findings.Kind.DATATYPE,
                            Findings.Certainty.DECLARED,
                            ((DLESyntaxParser.EquivAxiomContext) tree).start.getLine());
                    }
                }
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectDatatypeDefinitionsOnce(tree.getChild(i));
        }
    }

    /** True if the primary is an atom that looks like a data range. */
    private boolean isDataPrimary(DLESyntaxParser.PrimaryContext ctx) {
        // Through the complement, because `¬` is as happy over a data range as over a
        // class: `∃d.¬xsd:string` says d is a data property just as plainly as
        // `∃d.xsd:string` does. Stopping at the `¬` classified d as an object property,
        // and the document then contradicted its own range statement.
        // On the unwrapped primary, because the writer parenthesises it:
        // `∃d.(¬xsd:string)`. Parens.atomOf unwraps first and then finds no atom, so
        // testing the raw context missed every form the writer actually produces.
        DLESyntaxParser.PrimaryContext unwrapped = Parens.unwrap(ctx);
        if (unwrapped instanceof DLESyntaxParser.ComplementContext) {
            return isDataPrimary(((DLESyntaxParser.ComplementContext) unwrapped).primary());
        }
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

    /** The datatypes outside the XSD namespace, by IRI. */
    private static final Set<String> NON_XSD_DATATYPES = Set.of(
        RDF_NS + "PlainLiteral", RDF_NS + "langString", RDF_NS + "dirLangString",
        RDF_NS + "HTML", RDF_NS + "XMLLiteral", RDF_NS + "JSON",
        RDFS_NS + "Literal",
        OWL_NS + "rational", OWL_NS + "real");

    /**
     * Whether a resolved IRI names an OWL datatype.
     *
     * <p>The single answer to that question. There used to be two, and they disagreed in
     * both directions. This one is keyed on the resolved IRI; the other was keyed on the
     * text — {@code name.startsWith("xsd:")} plus a switch on seven CURIE spellings — so:
     *
     * <ul>
     * <li>{@code owl:real} and {@code owl:rational} were excluded from the case convention
     *     as datatypes but were not recognised <em>as</em> datatypes, so they came out
     *     classes. A valid {@code DataPropertyRange(:ratio owl:real)} was written
     *     {@code ⊤ ⊑ ∀ratio.owl:real} by this very tool and read back as an
     *     ObjectPropertyRange — a round trip DLe itself broke.
     * <li>The XSD namespace under any other prefix went unrecognised, putting a datatype
     *     IRI in a class position, which OWL 2 DL forbids.
     * <li>A document rebinding {@code xsd:} elsewhere had its own names treated as
     *     datatypes.
     * </ul>
     */
    static boolean isDatatypeIri(@Nullable String resolvedIri) {
        return resolvedIri != null
            && (resolvedIri.startsWith(XSD_NS) || NON_XSD_DATATYPES.contains(resolvedIri));
    }

    /** Whether a name, as written in the source, denotes a datatype in this document. */
    /**
     * Where the findings and the name sets disagree, if anywhere.
     *
     * <p>Two representations of one thing can drift, and drift is what this whole area has
     * suffered from. Findings are the evidence; the sets are the resolution propagation
     * walks. They are supposed to agree on which names hold which kinds, and until the sets
     * are retired the cheapest guarantee of that is to check it.
     *
     * <p>Reported as descriptions rather than thrown, so a test can assert the whole picture
     * at once instead of failing on the first name.
     *
     * @return one line per disagreement, empty when they agree
     */
    List<String> findingsDisagreements() {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        findings.names().forEach(seen::add);
        seen.addAll(objectPropertyNames);
        seen.addAll(dataPropertyNames);
        seen.addAll(mustBeClass);
        seen.addAll(datatypeNames);
        for (String name : seen) {
            check(out, name, Findings.Kind.OBJECT_PROPERTY, objectPropertyNames);
            check(out, name, Findings.Kind.DATA_PROPERTY, dataPropertyNames);
            check(out, name, Findings.Kind.CLASS, mustBeClass);
            check(out, name, Findings.Kind.DATATYPE, datatypeNames);
        }
        return out;
    }

    private void check(List<String> out, String name, Findings.Kind kind,
                       java.util.Set<String> resolved) {
        boolean inSet = resolved.contains(name);
        boolean expected = kind.isProperty()
            // The three property kinds compete, so only the firmest holds. A superseded
            // finding is not drift — a DEFAULTED object guess beneath POSITIONAL data
            // evidence is the model working.
            ? kind == firmestPropertyKind(name)
            // A class or a datatype does not compete with a property: that is a pun.
            : findings.of(name).stream().anyMatch(f -> f.kind == kind);
        if (inSet && !expected) {
            out.add(name + ": in the " + kind + " set, but the findings say "
                + findings.of(name));
        } else if (!inSet && expected) {
            out.add(name + ": findings say " + kind + " but it is not in that set ("
                + findings.of(name) + ")");
        }
    }

    /** The one property kind a name resolves to, or null if it has no property finding. */
    @Nullable
    private Findings.Kind firmestPropertyKind(String name) {
        Findings.Finding best = null;
        for (Findings.Finding f : findings.of(name)) {
            if (!f.kind.isProperty()) continue;
            if (best == null || f.certainty.compareTo(best.certainty) > 0) best = f;
        }
        return best == null ? null : best.kind;
    }

    boolean isDataTypeName(String name) {
        return datatypeNames.contains(name) || isDatatypeIri(resolve(name));
    }

    /**
     * If a classExpr is just a single bare name (name atom with no union/
     * intersection/restriction), returns that name text; otherwise null.
     */
    private String singleBareName(DLESyntaxParser.ClassExprContext ctx) {
        // Parens.atomOf, not a hand-rolled unwrap: the axiom visitor's loneName uses it, and
        // the two must agree. They did not, so `X ⊑ (owl:topObjectProperty)` was consumed as
        // a kind statement by the visitor while staying invisible here — the classifier and
        // the axiom builder then disagreed about the same name.
        var atom = Parens.atomOf(ctx);
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

    /** Returns the InversePropertyAtomContext if the classExpr is just a single name⁻, else null. */
    private DLESyntaxParser.InversePropertyAtomContext singleInverseAtom(DLESyntaxParser.ClassExprContext ctx) {
        var atom = Parens.atomOf(ctx);
        if (!(atom instanceof DLESyntaxParser.InversePropertyAtomContext)) return null;
        return (DLESyntaxParser.InversePropertyAtomContext) atom;
    }
}
