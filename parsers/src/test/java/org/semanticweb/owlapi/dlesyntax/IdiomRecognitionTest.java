package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DL writes one thing where OWL has a dedicated axiom, and the reader puts it back.
 *
 * <p>Description logic has no separate notation for functionality or a domain — they are
 * said with a subsumption — so the writer has to spell them that way. Reading the
 * subsumption literally meant the axiom type was lost on every round trip:
 * {@code InverseFunctionalObjectProperty(:r)} came back as
 * {@code SubClassOf(owl:Thing ObjectMaxCardinality(1 ObjectInverseOf(:r)))}, with
 * {@code owl:Thing} added to the signature for good measure. Equivalent, and unpleasant to
 * read as OWL.
 *
 * <p>Recognising the shape on the way in is what the domain, range, reflexivity and
 * irreflexivity idioms already did; these are the same technique applied to the rest.
 *
 * <p>The general form stays reachable, which is what keeps this a recognition rather than a
 * restriction: any cardinality other than one, any filler, or a subject other than
 * {@code ⊤} falls through to the subsumption it is.
 */
class IdiomRecognitionTest {

    private static final String NS = "http://example.org/i#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
    private final OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
    private final OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
    private final OWLClass b = df.getOWLClass(IRI.create(NS + "B"));

    /** Enough context that the scanner can classify r and d without guessing. */
    private OWLOntology ontology(OWLAxiom underTest) throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, underTest);
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(r, b)));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLDataSomeValuesFrom(d,
            df.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#string")))));
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

    /** The axiom this issue was raised about. */
    @Test
    void inverseFunctionalityComesBackAsItself() throws Exception {
        OWLAxiom axiom = df.getOWLInverseFunctionalObjectPropertyAxiom(r);
        OWLOntology o = ontology(axiom);
        String written = write(o);
        assertTrue(bodyOf(written).contains("⊤ ⊑ ≤ 1 r⁻"),
            () -> "written as the DL idiom:\n" + bodyOf(written));

        OWLOntology back = read(written);
        assertTrue(back.containsAxiom(axiom),
            () -> "and read back as the axiom it was:\n" + back.getLogicalAxioms());
        assertEquals(0, back.getAxioms(AxiomType.SUBCLASS_OF).stream()
            .filter(ax -> ax.getSubClass().isOWLThing()).count(),
            () -> "with no owl:Thing subsumption left over: " + back.getLogicalAxioms());
    }

    /** The bare cardinality spelling of functionality, which a person may well write. */
    @Test
    void theCardinalitySpellingOfFunctionalityIsRecognised() throws Exception {
        OWLOntology objects = read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\n⊤ ⊑ ≤1 r\n");
        assertTrue(objects.containsAxiom(df.getOWLFunctionalObjectPropertyAxiom(r)),
            () -> objects.getLogicalAxioms().toString());

        OWLOntology data = read("@prefix : <" + NS + ">\n∃d.xsd:string ⊑ A\n⊤ ⊑ ≤1 d\n");
        assertTrue(data.containsAxiom(df.getOWLFunctionalDataPropertyAxiom(d)),
            () -> data.getLogicalAxioms().toString());
    }

    /** Inverse functionality on an inverse is functionality, which is worth keeping right. */
    @Test
    void aDoubleInverseCancels() throws Exception {
        OWLOntology o = ontology(df.getOWLInverseFunctionalObjectPropertyAxiom(
            df.getOWLObjectInverseOf(r)));
        OWLOntology back = read(write(o));
        assertTrue(back.containsAxiom(df.getOWLFunctionalObjectPropertyAxiom(r)),
            () -> "inverse-functional on r⁻ is functional on r: " + back.getLogicalAxioms());
    }

    /** Func(p) must still be the spelling the writer chooses for plain functionality. */
    @Test
    void plainFunctionalityStillUsesTheKeyword() throws Exception {
        String body = bodyOf(write(ontology(df.getOWLFunctionalObjectPropertyAxiom(r))));
        assertTrue(body.contains("Func(r)"),
            () -> "the keyword is shorter and is what DLe writes:\n" + body);
    }

    // ── The general form has to stay reachable ──────────────────────────────

    /** A cardinality other than one is not functionality. */
    @Test
    void anotherCardinalityIsStillASubsumption() throws Exception {
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(df.getOWLThing(),
            df.getOWLObjectMaxCardinality(2, r));
        assertTrue(read(write(ontology(axiom))).containsAxiom(axiom),
            "⊤ ⊑ ≤2 r says something else and must survive as itself");
    }

    /** A qualified bound is not functionality either: it restricts only that filler. */
    @Test
    void aQualifiedBoundIsStillASubsumption() throws Exception {
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(df.getOWLThing(),
            df.getOWLObjectMaxCardinality(1, r, b));
        assertTrue(read(write(ontology(axiom))).containsAxiom(axiom),
            "⊤ ⊑ ≤1 r.B bounds r only for B");
    }

    /** And a bound on a named class is an ordinary axiom about that class. */
    @Test
    void aBoundOnANamedClassIsStillASubsumption() throws Exception {
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(a, df.getOWLObjectMaxCardinality(1, r));
        assertTrue(read(write(ontology(axiom))).containsAxiom(axiom),
            "A ⊑ ≤1 r is about A, not about r");
    }

    /** A minimum is not a maximum. */
    @Test
    void aMinimumIsStillASubsumption() throws Exception {
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(df.getOWLThing(),
            df.getOWLObjectMinCardinality(1, r));
        assertTrue(read(write(ontology(axiom))).containsAxiom(axiom),
            "⊤ ⊑ ≥1 r is a minimum");
    }

    /** The idioms that already worked must keep working. */
    @Test
    void theExistingIdiomsAreUnaffected() throws Exception {
        assertTrue(read("@prefix : <" + NS + ">\n∃r.⊤ ⊑ A\n")
            .containsAxiom(df.getOWLObjectPropertyDomainAxiom(r, a)), "domain");
        assertTrue(read("@prefix : <" + NS + ">\n⊤ ⊑ ∀r.B\n")
            .containsAxiom(df.getOWLObjectPropertyRangeAxiom(r, b)), "range");
        assertTrue(read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\n⊤ ⊑ ∃r.Self\n")
            .containsAxiom(df.getOWLReflexiveObjectPropertyAxiom(r)), "reflexive");
        assertTrue(read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\n∃r.Self ⊑ ⊥\n")
            .containsAxiom(df.getOWLIrreflexiveObjectPropertyAxiom(r)), "irreflexive");
    }
}
