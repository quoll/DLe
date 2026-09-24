package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An inverse property is accepted everywhere OWL allows one.
 *
 * <p>Nine rules took a bare name where OWL defines the axiom over an object property
 * expression, and the writer emitted {@code r⁻} into all of them regardless. Every one was
 * therefore a save-then-fail-to-load: any ontology with an inverse in one of those positions
 * produced a document DLe could not read back.
 *
 * <p>A data property has no inverse in OWL — a value cannot point back at what holds it — so
 * the three characteristics that have both forms refuse one.
 */
class InversePropertyPositionTest {

    private static final String NS = "http://example.org/v#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";
    /** Enough for the scanner to classify r and s without guessing. */
    private static final String ROLES = "A ⊑ ∃r.B\nA ⊑ ∃s.B\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
    private final OWLObjectProperty s = df.getOWLObjectProperty(IRI.create(NS + "s"));
    private final OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
    private final OWLClass b = df.getOWLClass(IRI.create(NS + "B"));

    private OWLOntology ontology(OWLAxiom underTest) throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, underTest);
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(r, b)));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(s, b)));
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Writes, reads, and asserts the axiom came back; returns the body for messages. */
    private String roundTrip(OWLAxiom axiom) throws Exception {
        OWLOntology o = ontology(axiom);
        String written = write(o);
        String body = bodyOf(written);
        OWLOntology back = assertDoesNotThrow(() -> read(written),
            () -> axiom + " must reload:\n" + body);
        assertTrue(back.containsAxiom(axiom),
            () -> axiom + " must come back:\n" + body + back.getLogicalAxioms());
        return body;
    }

    private String refusal(String document) {
        Throwable t = assertThrows(Throwable.class, () -> read(document),
            () -> "expected a refusal for:\n" + document);
        return String.valueOf(t.getMessage());
    }

    private OWLObjectInverseOf inverseOfR() {
        return df.getOWLObjectInverseOf(r);
    }

    // ── The six characteristics ─────────────────────────────────────────────

    @Test
    void everyCharacteristicAcceptsAnInverse() throws Exception {
        assertTrue(roundTrip(df.getOWLTransitiveObjectPropertyAxiom(inverseOfR()))
            .contains("Trans(r⁻)"));
        assertTrue(roundTrip(df.getOWLSymmetricObjectPropertyAxiom(inverseOfR()))
            .contains("Sym(r⁻)"));
        assertTrue(roundTrip(df.getOWLAsymmetricObjectPropertyAxiom(inverseOfR()))
            .contains("Asym(r⁻)"));
        assertTrue(roundTrip(df.getOWLReflexiveObjectPropertyAxiom(inverseOfR()))
            .contains("Ref(r⁻)"));
        assertTrue(roundTrip(df.getOWLIrreflexiveObjectPropertyAxiom(inverseOfR()))
            .contains("Irref(r⁻)"));
        // Functionality of an inverse is the one characteristic OWL has a second axiom for.
        // `Func(r⁻)` writes both and reads back as the dedicated one, so this is a
        // normalisation rather than a round trip — equivalent in both directions, and to
        // the spelling OWL itself prefers.
        OWLOntology o = ontology(df.getOWLFunctionalObjectPropertyAxiom(inverseOfR()));
        String body = bodyOf(write(o));
        assertTrue(body.contains("Functional(r⁻)"), () -> "written with the inverse:\n" + body);
        assertTrue(read(write(o)).containsAxiom(df.getOWLInverseFunctionalObjectPropertyAxiom(r)),
            () -> "and normalised to InverseFunctional:\n" + body);
    }

    // ── Disjointness, a key, and a chain's super-property ───────────────────

    @Test
    void disjointnessAcceptsAnInverse() throws Exception {
        assertTrue(roundTrip(df.getOWLDisjointObjectPropertiesAxiom(inverseOfR(), s))
            .contains("⁻"));
    }

    @Test
    void aKeyAcceptsAnInverse() throws Exception {
        assertTrue(roundTrip(df.getOWLHasKeyAxiom(a,
            Collections.singleton(inverseOfR()), Collections.emptySet()))
            .contains("key(r⁻)"));
    }

    @Test
    void aChainSuperPropertyAcceptsAnInverse() throws Exception {
        assertTrue(roundTrip(df.getOWLSubPropertyChainOfAxiom(
            Arrays.asList(r, s), inverseOfR())).contains("⊑ r⁻"));
    }

    /**
     * One property and its inverse are two expressions, so this is not a repeat.
     *
     * <p>The repeat check compares resolved IRIs, and would have called these the same
     * property if it looked at the name alone.
     */
    @Test
    void aPropertyAndItsInverseAreNotARepeat() throws Exception {
        OWLOntology o = read(PREFIX + ROLES + "Disj(r, r⁻)\n");
        assertEquals(1, o.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(),
            () -> o.getLogicalAxioms().toString());
        // The pair itself. A count of one is what `Disj(r, r)` produces too, because the OWL
        // API collapses the repeat — so dropping the inverse marker left this green and the
        // first half of the test could not fail for its stated reason.
        OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
        assertTrue(o.containsAxiom(df.getOWLDisjointObjectPropertiesAxiom(
                r, r.getInverseProperty())),
            () -> "r and its inverse are two different properties: " + o.getLogicalAxioms());
        assertTrue(refusal(PREFIX + ROLES + "Disj(r, r)\n")
            .contains("named twice in this disjointness statement"));
    }

    // ── A data property has no inverse ──────────────────────────────────────

    /**
     * The three characteristics with both forms refuse an inverted data property.
     *
     * <p>The expression is legal syntax; only the property's kind makes it wrong, so this
     * has to be caught after classification rather than by the grammar.
     */
    @Test
    void anInvertedDataPropertyIsRefused() {
        String data = "(a,\"5\"):d\n";
        for (String statement : new String[] {
                "Func(d⁻)", "Disj(d⁻, e)", "A ⊑ key(d⁻)"}) {
            String message = refusal(PREFIX + data + "(b,\"6\"):e\n" + statement + "\n");
            assertTrue(message.contains("has no inverse in OWL"),
                () -> statement + " must say why, got: " + message);
        }
    }

    /** And the object-only characteristics still refuse a data property outright. */
    @Test
    void anObjectOnlyCharacteristicStillRefusesADataProperty() {
        assertTrue(refusal(PREFIX + "(a,\"5\"):d\nTrans(d)\n")
            .contains("Trans applies to object properties only"));
    }

    /** The plain, uninverted forms must all be untouched. */
    @Test
    void thePlainFormsAreUnaffected() throws Exception {
        OWLOntology o = read(PREFIX + ROLES + "Trans(r)\nSym(r)\nRef(r)\nFunc(r)\n"
            + "Disj(r, s)\n");
        assertEquals(1, o.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY).size(), "Trans");
        assertEquals(1, o.getAxioms(AxiomType.SYMMETRIC_OBJECT_PROPERTY).size(), "Sym");
        assertEquals(1, o.getAxioms(AxiomType.REFLEXIVE_OBJECT_PROPERTY).size(), "Ref");
        assertEquals(1, o.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).size(), "Func");
        assertEquals(1, o.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(), "Disj");

        OWLOntology data = read(PREFIX + "(a,\"5\"):d\nFunc(d)\n");
        assertEquals(1, data.getAxioms(AxiomType.FUNCTIONAL_DATA_PROPERTY).size(),
            () -> data.getLogicalAxioms().toString());
    }

    /** A double inverse cancels, as it does everywhere else. */
    @Test
    void aDoubleInverseCancels() throws Exception {
        OWLOntology o = read(PREFIX + ROLES + "Trans(r⁻⁻)\n");
        assertTrue(o.containsAxiom(df.getOWLTransitiveObjectPropertyAxiom(r)),
            () -> o.getLogicalAxioms().toString());
    }

    /**
     * An inverse on the left of a subsumption survives as itself.
     *
     * <p>The writer emits {@code r⁻ ⊑ s} for this — it has always been able to — and the
     * reader refused it: "expected a class expression here, but found ObjectInverseOf(…)".
     * The scanner had the rule for an inverse on the *right* (which forces the left to be a
     * property) and not its mirror, so the right-hand name was never classified, defaulted to
     * a class, and the sub-property branch could not match. A document DLe wrote and could
     * not read.
     */
    @Test
    void anInverseSubPropertySurvives() throws Exception {
        OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
        OWLObjectProperty t = df.getOWLObjectProperty(IRI.create(NS + "s"));
        OWLAxiom axiom = df.getOWLSubObjectPropertyOfAxiom(r.getInverseProperty(), t);

        // Read from the bare statement, not from a round trip. A round trip passes either
        // way: the writer emits `s ⊑ owl:topObjectProperty` beside it, which classifies the
        // right-hand name by a different route and hides whether this rule exists at all.
        // Removing the rule left all twelve tests in this file green until this was the
        // fixture.
        OWLOntology o = read("@prefix : <" + NS + ">\nr\u207b \u2291 s\n");
        assertTrue(o.containsAxiom(axiom),
            () -> "an inverse on the left must survive as itself: " + o.getLogicalAxioms());
        assertFalse(o.containsClassInSignature(IRI.create(NS + "s")),
            () -> "and the right-hand name is a property, not a class: "
                + o.getLogicalAxioms());

        // And the writer's own output still reads, which is the defect this started from.
        String written = roundTrip(axiom);
        assertTrue(statements(written).contains("\u207b \u2291"),
            () -> "expected the inverse to be written on the left:\n" + statements(written));
    }

    /**
     * An assertion over an inverse property is written the other way round.
     *
     * <p>The assertion form has no room for the marker — `(a,b):r` takes a bare name after the
     * colon — so `(a,b):r⁻` was written and refused with {@code extraneous input '⁻'}.
     * Reversing the pair says the same thing by definition: ⟨a,b⟩ ∈ r⁻ is ⟨b,a⟩ ∈ r.
     *
     * <p>So the axiom comes back swapped rather than inverted, which is the same
     * normalisation as {@code InverseFunctional(r⁻)} returning as {@code Functional(r)}.
     * Asserted as the swapped axiom, because that is what equality will find.
     */
    @Test
    void anAssertionOverAnInverseIsWrittenTheOtherWayRound() throws Exception {
        OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
        OWLNamedIndividual aa = df.getOWLNamedIndividual(IRI.create(NS + "aa"));
        OWLNamedIndividual bb = df.getOWLNamedIndividual(IRI.create(NS + "bb"));

        OWLOntology positive = read(write(ontology(
            df.getOWLObjectPropertyAssertionAxiom(r.getInverseProperty(), aa, bb))));
        assertTrue(positive.containsAxiom(df.getOWLObjectPropertyAssertionAxiom(r, bb, aa)),
            () -> "the pair must be reversed and the property named: "
                + positive.getLogicalAxioms());

        OWLOntology negative = read(write(ontology(
            df.getOWLNegativeObjectPropertyAssertionAxiom(r.getInverseProperty(), aa, bb))));
        assertTrue(negative.containsAxiom(
                df.getOWLNegativeObjectPropertyAssertionAxiom(r, bb, aa)),
            () -> "and the same for the negative form: " + negative.getLogicalAxioms());
    }

    /**
     * A property expression in a class position is explained as one.
     *
     * <p>The backstop's advice was hard-coded to talk about datatypes, so it told an author
     * whose document put a property where a class belongs that "a datatype can only be the
     * filler of a data property restriction" — the only guidance offered for the shape, and
     * about the wrong thing entirely.
     */
    @Test
    void aPropertyInAClassPositionIsExplainedAsOne() {
        DLESemanticException e = assertThrows(DLESemanticException.class,
            () -> read("@prefix : <" + NS + ">\n\u2203r.C \u2291 \u2203s.\u22a4 \u2293 t\u207b\n"),
            "a property expression is not a class expression");
        assertFalse(e.getMessage().contains("datatype"),
            () -> "the advice must not be about datatypes here: " + e.getMessage());
    }

    /** The statements, without the header that quotes the syntax it documents. */
    private static String statements(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }
}
