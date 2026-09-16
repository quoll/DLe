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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Equivalence chains, as the writer has always written it.
 *
 * <p>{@code EquivalentClasses(:A :B :C)} is written {@code A ≡ B ≡ C}, but the rule took
 * exactly two operands, so any three-or-more-way equivalence DLe wrote it could not read
 * back. The identity operators {@code =} and {@code ≠} were made chainable earlier, which
 * left the inconsistency internal to the syntax rather than merely a gap.
 */
class ChainedEquivalenceTest {

    private static final String NS = "http://example.org/e#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private String refusal(String document) {
        Throwable t = assertThrows(Throwable.class, () -> read(document),
            () -> "expected a refusal for:\n" + document);
        return String.valueOf(t.getMessage());
    }

    /** A chain of classes is one axiom with every operand, not a pair. */
    @Test
    void aChainOfClassesIsOneAxiom() throws Exception {
        OWLOntology o = read(PREFIX + "A ≡ B ≡ C\n");
        assertEquals(1, o.getAxioms(AxiomType.EQUIVALENT_CLASSES).size(),
            () -> o.getLogicalAxioms().toString());
        assertEquals(df.getOWLEquivalentClassesAxiom(cls("A"), cls("B"), cls("C")),
            o.getAxioms(AxiomType.EQUIVALENT_CLASSES).iterator().next(),
            () -> "all three operands, in one axiom: " + o.getLogicalAxioms());
    }

    /** And it round-trips, which is the case that could not be read back. */
    @Test
    void anNaryEquivalenceRoundTrips() throws Exception {
        for (int n = 2; n <= 5; n++) {
            OWLClass[] classes = new OWLClass[n];
            for (int i = 0; i < n; i++) classes[i] = cls("C" + i);
            OWLOntology o = manager.createOntology();
            manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(classes));

            String written = write(o);
            final int count = n;
            OWLOntology back = assertDoesNotThrow(() -> read(written),
                () -> count + " operands must reload:\n" + bodyOf(written));
            assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
                () -> count + " operands must be unchanged:\n" + bodyOf(written));
        }
    }

    /** Both property kinds chain too, and each keeps its kind. */
    @Test
    void propertyEquivalencesChain() throws Exception {
        OWLOntology objects = read(PREFIX + "A ⊑ ∃p.B\np ≡ q ≡ r\n");
        assertEquals(1, objects.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES).size(),
            () -> objects.getLogicalAxioms().toString());
        assertEquals(3, objects.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES).iterator()
            .next().properties().count(), "all three properties");

        OWLOntology data = read(PREFIX + "∃d.xsd:string ⊑ A\nd ≡ e ≡ f\n");
        assertEquals(1, data.getAxioms(AxiomType.EQUIVALENT_DATA_PROPERTIES).size(),
            () -> data.getLogicalAxioms().toString());
        assertEquals(0, data.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES).size(),
            () -> "and not the object form: " + data.getLogicalAxioms());
    }

    /** A complex operand may appear anywhere in the chain. */
    @Test
    void aChainMayHoldComplexOperands() throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(Arrays.asList(
            cls("A"),
            df.getOWLObjectIntersectionOf(cls("B"), cls("C")),
            df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(IRI.create(NS + "r")),
                cls("D")))));
        String written = write(o);
        assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
            () -> bodyOf(written));
    }

    /**
     * A chain mixing the property kinds is a contradiction, not a choice.
     *
     * <p>Reported from the evidence, so the message can name both properties and both lines
     * rather than only the statement that happened to bring them together.
     */
    @Test
    void aChainMixingPropertyKindsIsRefused() {
        String message = refusal(PREFIX + "(a,\"5\"):d\nA ⊑ ∃r.B\nd ≡ r ≡ e\n");
        assertTrue(message.contains("Equivalence holds between properties of one kind"),
            () -> "got: " + message);
        assertTrue(message.contains("line 2") && message.contains("line 3"),
            () -> "both lines must be named: " + message);
    }

    /**
     * The kind reaches every member of a group, wherever the evidence sits.
     *
     * <p>A name with no evidence of its own defaults to an object property, so an
     * equivalence to a data property mixed the kinds — and the old two-operand path handled
     * that by making *both* names classes, silently. Resolved in the scanner's fixpoint pass
     * rather than as the statement is read, so the answer does not depend on whether the
     * evidence sits above or below the equivalence.
     */
    @Test
    void theKindReachesEveryMemberWhicheverOrder() throws Exception {
        String evidence = "∃d.xsd:string ⊑ A\n";
        String chain = "d ≡ e ≡ f\n";
        for (String document : new String[] {PREFIX + evidence + chain,
                                             PREFIX + chain + evidence}) {
            OWLOntology o = read(document);
            assertEquals(1, o.getAxioms(AxiomType.EQUIVALENT_DATA_PROPERTIES).size(),
                () -> "all three are data properties in:\n" + document
                    + o.getLogicalAxioms());
            assertEquals(0, o.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES).size(),
                () -> "and none is an object property:\n" + document
                    + o.getLogicalAxioms());
            assertEquals(0, o.getAxioms(AxiomType.EQUIVALENT_CLASSES).size(),
                () -> "and none became a class:\n" + document + o.getLogicalAxioms());
        }
    }

    /** And it reaches through a second equivalence, since groups chain. */
    @Test
    void theKindReachesThroughAChainOfGroups() throws Exception {
        OWLOntology o = read(PREFIX + "d ≡ e\ne ≡ f\n∃f.xsd:string ⊑ A\n");
        assertEquals(2, o.getAxioms(AxiomType.EQUIVALENT_DATA_PROPERTIES).size(),
            () -> "evidence on f must reach d through e: " + o.getLogicalAxioms());
        for (String name : new String[] {"d", "e", "f"}) {
            assertTrue(o.containsDataPropertyInSignature(IRI.create(NS + name)),
                () -> name + " must be a data property: " + o.getLogicalAxioms());
        }
    }

    /**
     * An equivalence of one operand is not written, because a lone name is no statement.
     *
     * <p>{@code EquivalentClasses(:A :A)} is vacuous, and OWL API collapses it to a single
     * operand. The inherited renderer then wrote a bare {@code A} on its own line and the
     * whole document failed to load — the same defect as the one-property {@code Disj(p)}.
     */
    @Test
    void aDegenerateEquivalenceIsNotWritten() throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(cls("A"), cls("A")));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(cls("A"), cls("Z")));

        String written = write(o);
        assertDoesNotThrow(() -> read(written),
            () -> "the rest of the document must still load:\n" + bodyOf(written));
        assertTrue(read(written).containsAxiom(df.getOWLSubClassOfAxiom(cls("A"), cls("Z"))),
            () -> "and keep its other axioms:\n" + bodyOf(written));
    }

    /** The same for each property kind, which reach it by a different route. */
    @Test
    void aDegeneratePropertyEquivalenceIsNotWritten() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLOntology objects = manager.createOntology();
        manager.addAxiom(objects, df.getOWLEquivalentObjectPropertiesAxiom(p, p));
        manager.addAxiom(objects, df.getOWLSubClassOfAxiom(cls("A"),
            df.getOWLObjectSomeValuesFrom(p, cls("B"))));
        String writtenObjects = write(objects);
        assertDoesNotThrow(() -> read(writtenObjects), () -> bodyOf(writtenObjects));

        OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
        OWLOntology data = manager.createOntology();
        manager.addAxiom(data, df.getOWLEquivalentDataPropertiesAxiom(d, d));
        manager.addAxiom(data, df.getOWLDataPropertyAssertionAxiom(d,
            df.getOWLNamedIndividual(IRI.create(NS + "a")), df.getOWLLiteral("x")));
        String writtenData = write(data);
        assertDoesNotThrow(() -> read(writtenData), () -> bodyOf(writtenData));
    }

    /** A name repeated in a chain is vacuous, not contradictory, so it is accepted. */
    @Test
    void aRepeatInAChainIsAccepted() throws Exception {
        OWLOntology o = read(PREFIX + "A ≡ B ≡ A\n");
        assertEquals(df.getOWLEquivalentClassesAxiom(cls("A"), cls("B")),
            o.getAxioms(AxiomType.EQUIVALENT_CLASSES).iterator().next(),
            () -> "A ≡ B ≡ A says A ≡ B: " + o.getLogicalAxioms());
    }

    /** The forms that share the operator must not have been broken by the chaining. */
    @Test
    void theOtherUsesOfTheOperatorStillWork() throws Exception {
        // `q ≡ r⁻` is read as the dedicated inverse axiom: DL spells
        // InverseObjectProperties and an inverse-shaped EquivalentObjectProperties the same
        // way, so one of them has to be the answer, and it is the more specific.
        OWLOntology inverse = read(PREFIX + "A ⊑ ∃r.B\nq ≡ r⁻\n");
        assertEquals(1, inverse.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES).size(),
            () -> inverse.getLogicalAxioms().toString());
        // And a chain of inverses on both sides is not that shape, so it stays general.
        OWLOntology both = read(PREFIX + "A ⊑ ∃r.B\nA ⊑ ∃s.B\nr⁻ ≡ s⁻\n");
        assertEquals(1, both.getAxioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES).size(),
            () -> both.getLogicalAxioms().toString());

        OWLOntology chain = read(PREFIX + "A ⊑ ∃p.B\nA ⊑ ∃q.B\nd ≡ p ∘ q\n");
        assertEquals(1, chain.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF).size(),
            () -> chain.getLogicalAxioms().toString());

        OWLOntology pair = read(PREFIX + "A ≡ B ⊓ C\n");
        assertEquals(1, pair.getAxioms(AxiomType.EQUIVALENT_CLASSES).size(),
            () -> pair.getLogicalAxioms().toString());
    }
}
