package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A role characteristic has to agree with the kind of role it is applied to.
 *
 * <p>Transitivity, symmetry, asymmetry, reflexivity and irreflexivity are relations between
 * two individuals, and OWL defines them for object properties only. Every one of them built
 * an object property regardless of what the rest of the document said, so applying one to a
 * name used with a datatype produced an ontology declaring that name as both an object and
 * a data property — the punning OWL 2 DL forbids, which no reasoner will load and which DLe
 * cannot write back. Functionality is the exception: it has both forms.
 *
 * <p>Disjointness also has both forms, and DLe has two spellings for it. They disagreed:
 * `Disj(p, q)` always built the object form, and `p ⊓ q ⊑ ⊥` built whichever the scan order
 * happened to leave in place.
 */
class RoleCharacteristicKindTest {

    private static final String PREFIX = "@prefix : <http://example.org/t#>\n";
    /** Makes p a data property, by the only evidence that settles it: a datatype filler. */
    private static final String DATA_P = "∃p.xsd:integer ⊑ Device\n";

    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            manager.getOntologyLoaderConfiguration());
        return o;
    }

    private String refusal(String document) {
        Throwable t = assertThrows(Throwable.class, () -> parse(document),
            () -> "expected a refusal for:\n" + document);
        return String.valueOf(t.getMessage());
    }

    @Test
    void anObjectOnlyCharacteristicOnADataPropertyIsRefused() {
        for (String keyword : new String[] {"Trans", "Sym", "Asym", "Ref", "Irref"}) {
            String message = refusal(PREFIX + DATA_P + keyword + "(p)\n");
            assertTrue(message.contains(keyword + " applies to object properties only"),
                () -> "expected " + keyword + " to name itself, got: " + message);
        }
    }

    /** Functionality is the one that has both forms, and must keep working. */
    @Test
    void functionalityStillWorksForBothKinds() throws Exception {
        OWLOntology data = parse(PREFIX + DATA_P + "Func(p)\n");
        assertEquals(1, data.getAxioms(AxiomType.FUNCTIONAL_DATA_PROPERTY).size(),
            () -> "a functional data property: " + data.getLogicalAxioms());

        OWLOntology object = parse(PREFIX + "A ⊑ ∃r.B\nFunc(r)\n");
        assertEquals(1, object.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).size(),
            () -> "a functional object property: " + object.getLogicalAxioms());
    }

    /** And the object-property characteristics must not have been broken by the guard. */
    @Test
    void objectOnlyCharacteristicsStillWorkOnObjectProperties() throws Exception {
        OWLOntology o = parse(PREFIX + "A ⊑ ∃r.B\nTrans(r)\nSym(r)\nRef(r)\n");
        assertEquals(1, o.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY).size(), "Trans");
        assertEquals(1, o.getAxioms(AxiomType.SYMMETRIC_OBJECT_PROPERTY).size(), "Sym");
        assertEquals(1, o.getAxioms(AxiomType.REFLEXIVE_OBJECT_PROPERTY).size(), "Ref");
    }

    /**
     * Both spellings of disjointness agree, whichever kind and whichever order.
     *
     * <p>The order matters because the classification is built during a single scan: with
     * the datatype restrictions above the disjointness line the names came out one way,
     * below it the other, from the same three axioms.
     */
    @Test
    void bothDisjointnessSpellingsAgreeOnKindAndOrder() throws Exception {
        String twoData = "∃m.xsd:integer ⊑ Device\n∃n.xsd:integer ⊑ Device\n";
        String twoObject = "A ⊑ ∃m.B\nA ⊑ ∃n.B\n";
        for (String disj : new String[] {"Disj(m, n)\n", "m ⊓ n ⊑ ⊥\n"}) {
            for (String[] order : new String[][] {{twoData, disj}, {disj, twoData}}) {
                OWLOntology o = parse(PREFIX + order[0] + order[1]);
                assertEquals(1, o.getAxioms(AxiomType.DISJOINT_DATA_PROPERTIES).size(),
                    () -> "data disjointness for " + disj.trim() + ": " + o.getLogicalAxioms());
                assertEquals(0, o.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(),
                    () -> "and not the object form: " + o.getLogicalAxioms());
            }
            OWLOntology objects = parse(PREFIX + twoObject + disj);
            assertEquals(1, objects.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(),
                () -> "object disjointness for " + disj.trim() + ": " + objects.getLogicalAxioms());
        }
    }

    /**
     * They agree for a prefixed name, which the convention reaches through its local part.
     *
     * <p>The intersection spelling tested the case itself — {@code charAt(0)} on the whole
     * name — rather than calling the shared rule, so any prefixed name failed it:
     * {@code EX:p ⊓ EX:q ⊑ ⊥} read as a class intersection while {@code Disj(EX:p, EX:q)},
     * the same axiom, read as properties.
     *
     * <p>A digit-initial name is deliberately not included. {@code Disj(…)} is role-only
     * syntax, so its keyword settles the kind by itself, whereas {@code p ⊓ q ⊑ ⊥} is an
     * ordinary class expression that only the convention can reclassify — and a digit says
     * nothing either way. The two spellings therefore cannot agree for such a name without
     * a kind statement, which is exactly what kind statements are for.
     */
    @Test
    void bothSpellingsAgreeForAPrefixedName() throws Exception {
        String prefixes = "@prefix EX: <http://ex2.org/>\n" + PREFIX;
        for (String[] pair : new String[][] {{"EX:p", "EX:q"}}) {
            for (String disj : new String[] {
                    "Disj(" + pair[0] + ", " + pair[1] + ")\n",
                    pair[0] + " ⊓ " + pair[1] + " ⊑ ⊥\n"}) {
                // The disjointness alone. Giving the names a restriction as well would
                // classify them independently, and the seeding under test would then be
                // redundant — which is how the first version of this test passed against
                // the hand-written case check it was written to catch.
                OWLOntology o = parse(prefixes + disj);
                assertEquals(1, o.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(),
                    () -> disj.trim() + " must be a disjointness: " + o.getLogicalAxioms());
            }
        }
    }

    /**
     * One property named twice is refused however it is spelled.
     *
     * <p>Two prefixes may be bound to one namespace, so {@code e1:p} and {@code e2:p} are
     * one property written two ways. The check compared the written text, so the pair passed
     * it; OWL then collapsed them and the writer emitted {@code Disj(p)}, which is not a
     * form the grammar has, and the whole document stopped reloading.
     *
     * <p>The intersection spelling is included because it reaches the unary axiom by its own
     * route — it had no repeat check at all.
     */
    @Test
    void oneRepeatedPropertyIsRefusedWhicheverWayItIsNamed() {
        String twoPrefixes = "@prefix e1: <http://example.org/z#>\n"
            + "@prefix e2: <http://example.org/z#>\n" + PREFIX;
        for (String disj : new String[] {"Disj(e1:p, e2:p)\n", "e1:p \u2293 e2:p \u2291 \u22a5\n"}) {
            String message = refusal(twoPrefixes + "A \u2291 \u2203e1:p.B\n" + disj);
            assertTrue(message.contains("named twice in this disjointness statement"),
                () -> disj.trim() + " names one property twice, got: " + message);
        }
        // And the plain repeat, by both spellings.
        for (String disj : new String[] {"Disj(p, p)\n", "p \u2293 p \u2291 \u22a5\n"}) {
            String message = refusal(PREFIX + "A \u2291 \u2203p.B\n" + disj);
            assertTrue(message.contains("named twice in this disjointness statement"),
                () -> disj.trim() + " must be refused, got: " + message);
        }
    }

    /**
     * Disjointness holds within one kind, so a mixture is a contradiction, not a choice.
     *
     * <p>Reported from the evidence now rather than from the statement, which is what lets
     * the message name both properties and both lines. It is the same report an equivalence
     * gets, because the two constructs constrain a kind the same way — and naming which of
     * them it was is why the group carries its construct.
     */
    @Test
    void disjointnessMixingKindsIsRefused() {
        String message = refusal(PREFIX + "∃m.xsd:integer ⊑ Device\nA ⊑ ∃n.B\nDisj(m, n)\n");
        assertTrue(message.contains("this disjointness relates")
                && message.contains("It holds between properties of one kind"),
            () -> "got: " + message);
        assertTrue(message.contains("line 2") && message.contains("line 3"),
            () -> "both lines must be named: " + message);
    }

    /**
     * Disjointness carries the kind, rather than refusing a document that states one.
     *
     * <p>`Disj(d, e)` with `e` a stated data property was refused: `d` fell to the object
     * default and then clashed with its own partner, so a sound document was rejected and
     * the message blamed the line that was right. OWL has no disjointness that mixes the
     * kinds, so evidence on any member settles them all — exactly as for an equivalence.
     */
    @Test
    void disjointnessTakesTheKindFromItsPartner() throws Exception {
        for (String spelling : new String[] {"Disj(q, p)", "Disj(p, q)", "p ⊓ q ⊑ ⊥"}) {
            OWLOntology data = parse(PREFIX + DATA_P + spelling + "\n");
            assertEquals(1, data.getAxioms(AxiomType.DISJOINT_DATA_PROPERTIES).size(),
                () -> spelling + " with a data partner: " + data.getLogicalAxioms());

            OWLOntology objects = parse(PREFIX + "A ⊑ ∃p.B\n" + spelling + "\n");
            assertEquals(1, objects.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(),
                () -> spelling + " with an object partner: " + objects.getLogicalAxioms());
        }
    }

    /**
     * The object-only positions count as evidence, so a contradiction is reported.
     *
     * <p>A chain's super-property and either side of an inverse are object-only by
     * construction, but they classified the name without recording it — so the conflict
     * check was blind to them and a document naming one IRI as both kinds was accepted
     * silently, producing an ontology OWL 2 DL forbids and no reasoner will load.
     */
    @Test
    void theObjectOnlyPositionsAreEvidence() {
        for (String contradiction : new String[] {
                "p ∘ q ⊑ d", "q ≡ d⁻", "e ⊑ d⁻", "A ⊑ ∃d⁻.B"}) {
            String message = refusal(PREFIX + "(a,\"5\"):d\n" + contradiction + "\n");
            assertTrue(message.contains("object property") && message.contains("data property"),
                () -> contradiction + " must be reported, got: " + message);
        }
    }

    /** And the same shapes are untouched when there is no contradiction. */
    @Test
    void theObjectOnlyPositionsStillWorkAlone() throws Exception {
        OWLOntology chain = parse(PREFIX + "A ⊑ ∃p.B\nA ⊑ ∃q.B\np ∘ q ⊑ d\n");
        assertEquals(1, chain.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF).size(),
            () -> chain.getLogicalAxioms().toString());

        // The dedicated inverse axiom; see ChainedEquivalenceTest for why this spelling
        // resolves to InverseObjectProperties rather than the general equivalence.
        OWLOntology inverse = parse(PREFIX + "A ⊑ ∃r.B\nq ≡ r⁻\n");
        assertEquals(1, inverse.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES).size(),
            () -> inverse.getLogicalAxioms().toString());
    }

    /**
     * An object-only characteristic is positional evidence, not a default.
     *
     * <p>Nothing pinned the tier: recording it {@code DEFAULTED} instead — which is not
     * evidence at all — passed the entire suite. The difference shows in which line a
     * mixed-hierarchy document is blamed for.
     *
     * <p>{@code PROPAGATED} still passes, and that is not a gap: it is evidence too, and the
     * two differ only in provenance, which nothing here reads. The line this finding carries
     * is passed in rather than taken from a source finding, so there is no observable
     * difference to assert.
     *
     * <p>As evidence, {@code Trans(x)} blocks data from propagating onto {@code x}, so
     * {@code x ⊑ d} is reported as what it is: a subsumption across the two hierarchies, on
     * the line the author wrote. As a default it does not block, {@code x} becomes a data
     * property, and the complaint moves to the {@code Trans} line — true, but two lines away
     * from the mistake.
     */
    @Test
    void anObjectOnlyCharacteristicIsEvidenceNotADefault() {
        DLESemanticException error = assertThrows(DLESemanticException.class,
            () -> parse("Trans(x)\nx \u2291 d\n\u22a4 \u2291 \u2200d.xsd:string\n"));
        assertTrue(error.getMessage().contains("cannot subsume"),
            () -> "the subsumption is the line at fault, which needs Trans to count as"
                + " evidence: " + error.getMessage());
    }

    /**
     * Both spellings of every keyword read, and mean the same axiom.
     *
     * <p>The writer emits the written-out one, so the abbreviations are only ever an input
     * form — which is exactly how a spelling stops being exercised and quietly breaks. The
     * textbook uses them, and documents in the wild are written from the textbook.
     */
    @Test
    void bothSpellingsOfEveryKeywordRead() throws Exception {
        String[][] pairs = {
            {"Trans", "Transitive"}, {"Func", "Functional"}, {"Ref", "Reflexive"},
            {"Irref", "Irreflexive"}, {"Sym", "Symmetric"}, {"Asym", "Asymmetric"},
        };
        for (String[] pair : pairs) {
            OWLOntology shortForm = parse(PREFIX + "A ⊑ ∃p.B\n" + pair[0] + "(p)\n");
            OWLOntology longForm  = parse(PREFIX + "A ⊑ ∃p.B\n" + pair[1] + "(p)\n");
            assertEquals(shortForm.getLogicalAxioms(), longForm.getLogicalAxioms(),
                () -> pair[0] + " and " + pair[1] + " are one keyword: "
                    + shortForm.getLogicalAxioms() + " vs " + longForm.getLogicalAxioms());
            assertEquals(2, longForm.getLogicalAxioms().size(),
                () -> pair[1] + " must produce its axiom: " + longForm.getLogicalAxioms());
        }
    }

    /** And the written-out spelling is the one written back. */
    @Test
    void theWrittenOutSpellingIsWhatIsEmitted() throws Exception {
        String[][] pairs = {
            {"Trans", "Transitive"}, {"Func", "Functional"}, {"Ref", "Reflexive"},
            {"Irref", "Irreflexive"}, {"Sym", "Symmetric"}, {"Asym", "Asymmetric"},
        };
        for (String[] pair : pairs) {
            String written = write(parse(PREFIX + "A ⊑ ∃p.B\n" + pair[0] + "(p)\n"));
            assertTrue(written.contains(pair[1] + "(p)"),
                () -> "the short input must come back written out:\n" + written);
        }
    }

    /** Writes an ontology back as DLe. */
    private String write(OWLOntology o) throws Exception {
        org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat format =
            new org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat();
        format.setDefaultPrefix("http://example.org/t#");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format,
            new org.semanticweb.owlapi.io.StreamDocumentTarget(out));
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
