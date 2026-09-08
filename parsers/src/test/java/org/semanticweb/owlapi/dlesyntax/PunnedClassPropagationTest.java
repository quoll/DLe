package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name that is both a class and a property must not drag the classes above it into
 * the property hierarchy.
 *
 * <p>SNOMED-CT punts its attribute hierarchy onto its concept hierarchy: 762705008 is
 * declared {@code owl:Class} and is also the {@code rdfs:subPropertyOf} parent of every
 * concept-model attribute. DLe writes class subsumption and sub-property subsumption
 * alike as {@code a ⊑ b}, so on the way back in the reader has to work out which
 * hierarchy a chain of them belongs to, and it did so by following the roles it could
 * identify: this name is the parent of a property, so it is a property, so its parent
 * is a property too. That walked straight out of the attribute hierarchy and turned
 * four SNOMED concepts into object properties.
 *
 * <p>The document has to say which names are classes, because suppressed declarations
 * and an identical {@code ⊑} leave no other trace of it. {@code X ⊑ ⊤} is how a DLe
 * document already says so, and the writer now emits it for a punned name.
 */
class PunnedClassPropagationTest {

    private static final String NS = "http://example.org/t#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    /**
     * The shape of the defect: {@code X} is punned, {@code role} is unambiguously a
     * property because it appears in a restriction, and {@code A} and {@code B} are
     * ordinary classes above {@code X}. {@code sibling} has no role use of its own, so
     * the only thing that makes it a property is {@code X}.
     */
    private static final String PUNNED_DOCUMENT = PREFIX
        + "Tumour ≡ ∃role.Malignant\n"
        + "@label role \"a role\"\n"
        + "role ⊑ X\n"
        + "@label sibling \"a sibling\"\n"
        + "sibling ⊑ X\n"
        + "X ⊑ ⊤\n"
        + "X ⊑ A\n"
        + "A ⊑ B\n";

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

    private String write(OWLOntology ontology) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setPrefix(":", NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ontology.getOWLOntologyManager().saveOntology(ontology, format,
            new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static IRI iri(String local) {
        return IRI.create(NS + local);
    }

    private static void assertClassOnly(OWLOntology o, String local) {
        assertTrue(o.containsClassInSignature(iri(local)), local + " must be a class");
        assertFalse(o.containsObjectPropertyInSignature(iri(local)),
            local + " must not be an object property");
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * The whole point: the punned name keeps its property classification, and the
     * classes above it keep theirs.
     */
    @Test
    void roleClassificationStopsAtADeclaredClass() throws Exception {
        OWLOntology o = read(PUNNED_DOCUMENT);

        assertTrue(o.containsObjectPropertyInSignature(iri("X")),
            "X is the parent of a property, so it is one: the pun is real");
        assertTrue(o.containsClassInSignature(iri("X")), "X is also a class");
        assertClassOnly(o, "A");
        assertClassOnly(o, "B");
    }

    /** Downward, though, it must still propagate: a sub-property of X is a property. */
    @Test
    void roleClassificationStillReachesBelowADeclaredClass() throws Exception {
        OWLOntology o = read(PUNNED_DOCUMENT);

        assertTrue(o.containsObjectPropertyInSignature(iri("sibling")),
            "sibling has no role use of its own; being a sub-property of X is what "
                + "makes it a property, and that is the reason X is punned");
    }

    /** The axioms that flip, stated as axioms rather than as signature membership. */
    @Test
    void theSubsumptionsAboveADeclaredClassStayClassSubsumptions() throws Exception {
        OWLOntology o = read(PUNNED_DOCUMENT);
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();

        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(iri("X")), df.getOWLClass(iri("A")))),
            "X ⊑ A is class subsumption");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(iri("A")), df.getOWLClass(iri("B")))),
            "A ⊑ B is class subsumption");
        assertTrue(o.containsAxiom(df.getOWLSubObjectPropertyOfAxiom(
                df.getOWLObjectProperty(iri("role")), df.getOWLObjectProperty(iri("X")))),
            "role ⊑ X is sub-property subsumption");
    }

    /**
     * {@code X ⊑ ⊤} for a punned name is the declaration it stands for and nothing else.
     * Keeping {@code SubClassOf(X, owl:Thing)} would add a tautology to every document
     * that round-trips through DLe, and the axiom tally would report the addition as a
     * loss of fidelity.
     */
    @Test
    void theMarkerAddsNoAxiom() throws Exception {
        OWLOntology o = read(PUNNED_DOCUMENT);
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();

        assertFalse(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(iri("X")), df.getOWLThing())),
            "the marker is a declaration, not a subsumption");
        assertTrue(o.containsAxiom(df.getOWLDeclarationAxiom(df.getOWLClass(iri("X")))),
            "but it must still declare X a class — that is all it is for");
    }

    /**
     * For an ordinary class, {@code X ⊑ ⊤} is a statement in the document and stays one.
     * Five of them open syntax-coverage.dle.
     */
    @Test
    void anOrdinaryClassKeepsItsSubsumptionUnderTop() throws Exception {
        OWLOntology o = read(PREFIX + "Station ⊑ ⊤\n");
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();

        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(iri("Station")), df.getOWLThing())),
            "Station is not a property, so nothing about it is a marker");
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * The reader can only act on what the writer leaves it, and the flip happens on the
     * way in from DLe text that names no types at all.
     */
    @Test
    void thePunIsWrittenDown() throws Exception {
        OWLOntology o = punnedOntology();

        String written = write(o);

        assertTrue(written.contains("X ⊑ ⊤"),
            "a name that is both a class and a property must be written as a class:\n"
                + written);
    }

    /** And the whole trip: build the SNOMED shape, write it, read it back. */
    @Test
    void aPunSurvivesARoundTrip() throws Exception {
        OWLOntology round = read(write(punnedOntology()));

        assertTrue(round.containsObjectPropertyInSignature(iri("X")), "X stays a property");
        assertTrue(round.containsClassInSignature(iri("X")), "X stays a class");
        assertClassOnly(round, "A");
        assertClassOnly(round, "B");
    }

    /**
     * An ontology shaped like the SNOMED attribute root: {@code X} declared a class and
     * used as the property parent of {@code role}, with the class hierarchy {@code A},
     * {@code B} above it. {@code role} carries a label because an unannotated
     * sub-property is pushed down by {@link DualDeclarationResolver} instead, which is a
     * different question; in SNOMED-CT every attribute is labelled.
     */
    private OWLOntology punnedOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();

        OWLClass xClass = df.getOWLClass(iri("X"));
        OWLObjectProperty xProp = df.getOWLObjectProperty(iri("X"));
        OWLObjectProperty role = df.getOWLObjectProperty(iri("role"));

        manager.addAxiom(o, df.getOWLDeclarationAxiom(xClass));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(xProp));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(role, xProp));
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getRDFSLabel(), iri("role"), df.getOWLLiteral("a role")));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(xClass, df.getOWLClass(iri("A"))));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(iri("A")), df.getOWLClass(iri("B"))));
        // Something has to establish `role` as a role in the written document, exactly as
        // the equivalent-class expression does in the SNOMED examples.
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(
            df.getOWLClass(iri("Tumour")),
            df.getOWLObjectSomeValuesFrom(role, df.getOWLClass(iri("Malignant")))));
        return o;
    }
}
