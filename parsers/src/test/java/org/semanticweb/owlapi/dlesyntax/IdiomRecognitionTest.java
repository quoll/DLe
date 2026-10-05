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
 * <p>Description logic has no separate notation for a domain or a range — they are said with
 * a subsumption — so the writer spells them that way and the reader recognises the shape.
 * Without that, {@code ObjectPropertyDomain} came back as the subsumption it was written as,
 * with {@code owl:Thing} added to the signature for good measure.
 *
 * <p><b>Functionality is no longer one of these.</b> It used to be: {@code ⊤ ⊑ ≤1 r} was read
 * as {@code FunctionalObjectProperty(r)}. The two are equivalent, so the recognition was
 * sound in itself, but it was one-to-many in the wrong direction — the writer has two
 * spellings and the reader had one meaning — so {@code SubClassOf(⊤ ObjectMaxCardinality(1
 * r))} could not survive a round trip at all. Each axiom now has one spelling:
 * {@code Func(r)}, {@code Func(d)} and {@code Func(r⁻)} for the three dedicated axioms, the
 * cardinality syntax for the cardinality axioms, and nothing to recognise back.
 *
 * <p>One normalisation remains, and it is a normalisation rather than a recognition:
 * {@code FunctionalObjectProperty(ObjectInverseOf(r))} and
 * {@code InverseFunctionalObjectProperty(r)} are the same statement, OWL has a dedicated
 * axiom for it, and both are written {@code Func(r⁻)}, so both come back as the dedicated
 * one. Equivalent in both directions, which is the line: normalise across an equivalence,
 * never across a one-way entailment.
 */
class IdiomRecognitionTest {

    private static final String NS = "http://example.org/i#";
    private static final String PREFIX_R = "@prefix : <" + NS + ">\nA ⊑ ∃r.B\n";

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

    /**
     * The axiom this issue was raised about, now carried by the keyword form.
     *
     * <p>It was written {@code ⊤ ⊑ ≤ 1 r⁻}, which is also how
     * {@code SubClassOf(⊤ ObjectMaxCardinality(1 r⁻))} is written — so one of the two had to
     * lose. {@code Func(r⁻)} belongs to the dedicated axiom and the cardinality form to the
     * cardinality axiom, and both survive.
     */
    @Test
    void inverseFunctionalityComesBackAsItself() throws Exception {
        OWLAxiom axiom = df.getOWLInverseFunctionalObjectPropertyAxiom(r);
        OWLOntology o = ontology(axiom);
        String written = write(o);
        assertTrue(bodyOf(written).contains("Functional(r⁻)"),
            () -> "written with the keyword form:\n" + bodyOf(written));

        OWLOntology back = read(written);
        assertTrue(back.containsAxiom(axiom),
            () -> "and read back as the axiom it was:\n" + back.getLogicalAxioms());
        assertEquals(0, back.getAxioms(AxiomType.SUBCLASS_OF).stream()
            .filter(ax -> ax.getSubClass().isOWLThing()).count(),
            () -> "with no owl:Thing subsumption left over: " + back.getLogicalAxioms());
    }

    /**
     * The cardinality spelling is the cardinality axiom, and keeps its own identity.
     *
     * <p>The inverse of what this test used to assert. `⊤ ⊑ ≤1 r` was read as functionality,
     * which is an equivalent statement but not the same axiom, so an ontology holding both
     * came back holding one. Both survive now, because each has its own spelling.
     */
    @Test
    void theCardinalitySpellingIsNotFunctionality() throws Exception {
        OWLOntology objects = read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\n⊤ ⊑ ≤1 r\n");
        assertTrue(objects.containsAxiom(df.getOWLSubClassOfAxiom(df.getOWLThing(),
                df.getOWLObjectMaxCardinality(1, r, df.getOWLThing()))),
            () -> objects.getLogicalAxioms().toString());
        assertTrue(objects.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).isEmpty(),
            () -> "and is not also asserted functional: " + objects.getLogicalAxioms());

        OWLOntology both = read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\n⊤ ⊑ ≤1 r\nFunc(r)\n");
        assertEquals(1, both.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).size(),
            () -> "both axioms survive together: " + both.getLogicalAxioms());
        assertEquals(2, both.getAxioms(AxiomType.SUBCLASS_OF).size(),
            () -> "both axioms survive together: " + both.getLogicalAxioms());
    }

    /** And the keyword form still carries functionality, for both kinds of property. */
    @Test
    void theKeywordFormIsFunctionality() throws Exception {
        OWLOntology objects = read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\nFunc(r)\n");
        assertTrue(objects.containsAxiom(df.getOWLFunctionalObjectPropertyAxiom(r)),
            () -> objects.getLogicalAxioms().toString());

        OWLOntology data = read("@prefix : <" + NS + ">\n∃d.xsd:string ⊑ A\nFunctional(d)\n");
        assertTrue(data.containsAxiom(df.getOWLFunctionalDataPropertyAxiom(d)),
            () -> "the long spelling too: " + data.getLogicalAxioms());
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

    /**
     * The keyword is what the writer chooses for plain functionality.
     *
     * <p>Written out rather than the textbook's {@code Func}. They are one token and both
     * read; the long spelling asks less of a reader that has not been told what the
     * abbreviation stands for.
     */
    @Test
    void plainFunctionalityStillUsesTheKeyword() throws Exception {
        String body = bodyOf(write(ontology(df.getOWLFunctionalObjectPropertyAxiom(r))));
        assertTrue(body.contains("Functional(r)"),
            () -> "the keyword form is what DLe writes:\n" + body);
        OWLOntology shortSpelling = read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\nFunc(r)\n");
        assertTrue(shortSpelling.containsAxiom(df.getOWLFunctionalObjectPropertyAxiom(r)),
            () -> "and the short spelling still reads: " + shortSpelling.getLogicalAxioms());
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

    /**
     * {@code r ∘ r ⊑ r} is transitivity, which is what OWL has a dedicated axiom for.
     *
     * <p>A chain from a property to itself says exactly what {@code TransitiveObjectProperty}
     * says, and the chain is the odd way of saying it — so the reader translates toward the
     * dedicated axiom rather than away from it. That is the opposite direction to the
     * functionality case above, and deliberately: there, both spellings were things an author
     * writes and the cardinality form generalises, so collapsing them lost a distinction. Here
     * there is no generalisation to protect, and `SubObjectPropertyOf(ObjectPropertyChain(r r)
     * r)` is not a thing anyone writes in preference.
     *
     * <p>The cost is accepted and stated: the chain axiom does not survive a round trip, it
     * becomes the transitivity axiom. Nothing is lost semantically.
     *
     * <p>Untested until now. Deleting the whole recognition left all 777 tests green, so the
     * behaviour could have been removed by anyone tidying up and nothing would have said so.
     */
    @Test
    void aChainFromAPropertyToItselfIsTransitivity() throws Exception {
        OWLOntology o = read(PREFIX_R + "r ∘ r ⊑ r\n");
        assertTrue(o.containsAxiom(df.getOWLTransitiveObjectPropertyAxiom(r)),
            () -> "r ∘ r ⊑ r is transitivity: " + o.getLogicalAxioms());
        assertTrue(o.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF).isEmpty(),
            () -> "and not also a chain: " + o.getLogicalAxioms());
    }

    /** An inverse composed with itself is the transitivity of the inverse. */
    @Test
    void theSameHoldsForAnInverse() throws Exception {
        OWLOntology o = read(PREFIX_R + "r⁻ ∘ r⁻ ⊑ r⁻\n");
        assertTrue(o.containsAxiom(df.getOWLTransitiveObjectPropertyAxiom(
                df.getOWLObjectInverseOf(r))),
            () -> o.getLogicalAxioms().toString());
    }

    /**
     * Every neighbouring chain stays the chain it is.
     *
     * <p>The recognition is exactly "two links, both the super-property". These sit on each
     * of its boundaries, and each must fall through: a different second link, a different
     * super-property, three links rather than two, and a super-property that is the inverse
     * of the links. Without them the test above would pass just as well against a rule that
     * turned every chain into transitivity.
     */
    @Test
    void aChainThatIsNotTransitivityStaysAChain() throws Exception {
        String[] chains = {"r ∘ s ⊑ r", "r ∘ r ⊑ s", "r ∘ r ∘ r ⊑ r", "r ∘ r ⊑ r⁻"};
        for (String chain : chains) {
            OWLOntology o = read(PREFIX_R + "A ⊑ ∃s.B\n" + chain + "\n");
            assertEquals(1, o.getAxioms(AxiomType.SUB_PROPERTY_CHAIN_OF).size(),
                () -> chain + " is a chain: " + o.getLogicalAxioms());
            assertTrue(o.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY).isEmpty(),
                () -> chain + " is not transitivity: " + o.getLogicalAxioms());
        }
    }

    /** And a genuine chain survives a round trip, since nothing rewrites it. */
    @Test
    void aGenuineChainRoundTrips() throws Exception {
        OWLAxiom chain = df.getOWLSubPropertyChainOfAxiom(
            java.util.List.of(r, df.getOWLObjectProperty(IRI.create(NS + "s"))),
            df.getOWLObjectProperty(IRI.create(NS + "t")));
        OWLOntology back = read(write(ontology(chain)));
        assertTrue(back.containsAxiom(chain),
            () -> "r ∘ s ⊑ t is not an idiom and must come back as itself: "
                + back.getLogicalAxioms());
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
