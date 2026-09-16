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
        assertTrue(roundTrip(df.getOWLFunctionalObjectPropertyAxiom(inverseOfR()))
            .contains("Func(r⁻)"));
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
}
