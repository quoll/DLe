package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A punned ontology that arrives as OWL survives being written as DLe and read back.
 *
 * <p>{@link PunnedNameTest} covers punning as written by hand, in DLe text. This covers the
 * other direction, which is how a third party's ontology actually arrives: built through the
 * API or parsed from OWL, written as DLe, read again. The two meet in the middle and the
 * failure modes differ — here nothing is hand-written, so every kind the document needs has
 * to have been *stated by the writer*, and a kind it omits is one the reader must infer or
 * lose.
 *
 * <p>What is asserted is that the model survives, not the spelling. The round trip is free to
 * change representation: it may complete a signature the source left implicit, reorder the
 * operands of an unordered axiom, and add or drop the vacuous subsumptions —
 * {@code SubClassOf(X owl:Thing)} and the two property tops — because those are exactly the
 * kind markers DLe writes, so they are representation here rather than content. What it may
 * not do is lose a declaration, which would change an entity's kind, or lose an axiom.
 */
class PunnedImportRoundTripTest {

    private static final String NS = "http://example.org/p#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private IRI iri(String local) {
        return IRI.create(NS + local);
    }

    /** Writes the ontology as DLe and reads it back, as converting a file would. */
    private OWLOntology roundTrip(OWLOntology source) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.getOWLOntologyManager().saveOntology(source, format, new StreamDocumentTarget(out));
        String document = new String(out.toByteArray(), StandardCharsets.UTF_8);

        OWLOntology result = OWLManager.createOWLOntologyManager().createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), result,
            result.getOWLOntologyManager().getOntologyLoaderConfiguration());
        return result;
    }

    /**
     * Whether an axiom says nothing: every class is under {@code owl:Thing} and every
     * property under its own top. DLe writes exactly these as its kind markers, so they
     * cross the round trip in both directions and carry no meaning either way.
     */
    private static boolean vacuous(OWLAxiom axiom) {
        if (axiom instanceof OWLSubClassOfAxiom) {
            return ((OWLSubClassOfAxiom) axiom).getSuperClass().isOWLThing();
        }
        if (axiom instanceof OWLSubObjectPropertyOfAxiom) {
            return ((OWLSubObjectPropertyOfAxiom) axiom).getSuperProperty().isOWLTopObjectProperty();
        }
        if (axiom instanceof OWLSubDataPropertyOfAxiom) {
            return ((OWLSubDataPropertyOfAxiom) axiom).getSuperProperty().isOWLTopDataProperty();
        }
        return false;
    }

    /**
     * Every entity keeps its kind, and every meaningful axiom survives.
     *
     * <p>Kinds are checked against the signature rather than against the presence of a
     * {@code Declaration} axiom. An entity declared outright and one declared by being used
     * are the same model, and which of the two a round trip produces is representation —
     * the first version of this test demanded the axiom and failed on a class that had
     * simply come back implied by its own subsumptions.
     */
    private void assertModelSurvives(OWLOntology source) throws Exception {
        OWLOntology result = roundTrip(source);

        List<String> lost = new ArrayList<>();
        source.axioms(AxiomType.DECLARATION)
            .map(OWLDeclarationAxiom::getEntity)
            .filter(e -> !result.containsEntityInSignature(e))
            .forEach(e -> lost.add(e.getEntityType() + " " + e.getIRI().getShortForm()));
        source.logicalAxioms()
            .filter(a -> !vacuous(a))
            .filter(a -> !result.containsAxiom(a))
            .forEach(a -> lost.add("axiom " + a));

        assertTrue(lost.isEmpty(), () -> "the round trip lost:\n  " + String.join("\n  ", lost));
    }

    /** An ontology holding the given axioms, in a manager of its own. */
    private OWLOntology ontology(OWLAxiom... axioms) throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager().createOntology();
        for (OWLAxiom axiom : axioms) o.getOWLOntologyManager().addAxiom(o, axiom);
        return o;
    }

    // ── The shape a third party actually sends ──────────────────────────────

    /**
     * One IRI as a class and as an object property, with a class hierarchy beneath it.
     *
     * <p>The hierarchy is the part that can go wrong: role classification crosses a pun
     * downward, so a class under one is read as a role unless the document says otherwise.
     * The writer has to state enough for the reader to get it back.
     */
    @Test
    void aClassAndObjectPropertyWithClassesBeneathIt() throws Exception {
        OWLClass reference = df.getOWLClass(iri("Reference"));
        OWLObjectProperty asRole = df.getOWLObjectProperty(iri("Reference"));
        OWLClass dependency = df.getOWLClass(iri("Dependency"));
        OWLClass child = df.getOWLClass(iri("Child"));
        OWLClass component = df.getOWLClass(iri("Component"));

        assertModelSurvives(ontology(
            df.getOWLDeclarationAxiom(reference),
            df.getOWLDeclarationAxiom(asRole),
            df.getOWLDeclarationAxiom(dependency),
            df.getOWLDeclarationAxiom(child),
            df.getOWLDeclarationAxiom(component),
            df.getOWLSubClassOfAxiom(dependency, reference),
            df.getOWLSubClassOfAxiom(child, dependency),
            df.getOWLSubClassOfAxiom(component,
                df.getOWLObjectSomeValuesFrom(asRole, component))));
    }

    /** The same, punned with a data property instead. */
    @Test
    void aClassAndDataProperty() throws Exception {
        OWLClass name = df.getOWLClass(iri("Name"));
        OWLDataProperty asProperty = df.getOWLDataProperty(iri("Name"));
        OWLClass thing = df.getOWLClass(iri("Component"));

        assertModelSurvives(ontology(
            df.getOWLDeclarationAxiom(name),
            df.getOWLDeclarationAxiom(asProperty),
            df.getOWLDeclarationAxiom(thing),
            df.getOWLSubClassOfAxiom(thing,
                df.getOWLDataSomeValuesFrom(asProperty, df.getStringOWLDatatype())),
            df.getOWLSubClassOfAxiom(name, thing)));
    }

    /** A pun carrying axioms of both kinds at once. */
    @Test
    void aPunWithDomainRangeAndACharacteristic() throws Exception {
        OWLClass asClass = df.getOWLClass(iri("Reference"));
        OWLObjectProperty asRole = df.getOWLObjectProperty(iri("Reference"));
        OWLClass source = df.getOWLClass(iri("Source"));
        OWLClass target = df.getOWLClass(iri("Target"));

        assertModelSurvives(ontology(
            df.getOWLDeclarationAxiom(asClass),
            df.getOWLDeclarationAxiom(asRole),
            df.getOWLDeclarationAxiom(source),
            df.getOWLDeclarationAxiom(target),
            df.getOWLObjectPropertyDomainAxiom(asRole, source),
            df.getOWLObjectPropertyRangeAxiom(asRole, target),
            df.getOWLTransitiveObjectPropertyAxiom(asRole),
            df.getOWLSubClassOfAxiom(asClass, source)));
    }

    /** Unordered axioms may come back with their operands in another order. */
    @Test
    void aPunInADisjointness() throws Exception {
        OWLClass asClass = df.getOWLClass(iri("Reference"));
        OWLObjectProperty asRole = df.getOWLObjectProperty(iri("Reference"));
        OWLClass other = df.getOWLClass(iri("Other"));
        OWLObjectProperty q = df.getOWLObjectProperty(iri("q"));

        assertModelSurvives(ontology(
            df.getOWLDeclarationAxiom(asClass),
            df.getOWLDeclarationAxiom(asRole),
            df.getOWLDeclarationAxiom(other),
            df.getOWLDeclarationAxiom(q),
            df.getOWLDisjointClassesAxiom(asClass, other),
            df.getOWLSubObjectPropertyOfAxiom(asRole, q)));
    }

    /** A class punned with an individual, which OWL 2 DL also allows. */
    @Test
    void aClassAndNamedIndividual() throws Exception {
        OWLClass asClass = df.getOWLClass(iri("Thing1"));
        OWLNamedIndividual asIndividual = df.getOWLNamedIndividual(iri("Thing1"));
        OWLClass other = df.getOWLClass(iri("Other"));

        assertModelSurvives(ontology(
            df.getOWLDeclarationAxiom(asClass),
            df.getOWLDeclarationAxiom(asIndividual),
            df.getOWLDeclarationAxiom(other),
            df.getOWLClassAssertionAxiom(other, asIndividual),
            df.getOWLSubClassOfAxiom(asClass, other)));
    }

    /** Several puns at once, which is what "extensive use" means. */
    @Test
    void severalPunsInOneOntology() throws Exception {
        List<OWLAxiom> axioms = new ArrayList<>();
        OWLClass top = df.getOWLClass(iri("Component"));
        axioms.add(df.getOWLDeclarationAxiom(top));
        for (String local : new String[] {"Reference", "Contains", "DependsOn"}) {
            OWLClass asClass = df.getOWLClass(iri(local));
            OWLObjectProperty asRole = df.getOWLObjectProperty(iri(local));
            OWLClass sub = df.getOWLClass(iri(local + "Kind"));
            axioms.add(df.getOWLDeclarationAxiom(asClass));
            axioms.add(df.getOWLDeclarationAxiom(asRole));
            axioms.add(df.getOWLDeclarationAxiom(sub));
            axioms.add(df.getOWLSubClassOfAxiom(sub, asClass));
            axioms.add(df.getOWLSubClassOfAxiom(top,
                df.getOWLObjectSomeValuesFrom(asRole, top)));
        }
        assertModelSurvives(ontology(axioms.toArray(new OWLAxiom[0])));
    }

    /** Writing twice changes nothing: the first pass settles the representation. */
    @Test
    void theRoundTripIsStable() throws Exception {
        OWLClass asClass = df.getOWLClass(iri("Reference"));
        OWLObjectProperty asRole = df.getOWLObjectProperty(iri("Reference"));
        OWLClass dependency = df.getOWLClass(iri("Dependency"));
        OWLOntology source = ontology(
            df.getOWLDeclarationAxiom(asClass),
            df.getOWLDeclarationAxiom(asRole),
            df.getOWLDeclarationAxiom(dependency),
            df.getOWLSubClassOfAxiom(dependency, asClass),
            df.getOWLSubClassOfAxiom(dependency,
                df.getOWLObjectSomeValuesFrom(asRole, dependency)));

        OWLOntology once = roundTrip(source);
        OWLOntology twice = roundTrip(once);
        assertEquals(once.getLogicalAxiomCount(), twice.getLogicalAxiomCount(),
            "a second pass should change nothing");
        once.logicalAxioms().forEach(a ->
            assertTrue(twice.containsAxiom(a), () -> "second pass lost " + a));
    }
}
