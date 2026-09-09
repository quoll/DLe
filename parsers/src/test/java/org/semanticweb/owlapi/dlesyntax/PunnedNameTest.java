package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name that is both a class and a role — a pun — must survive a round trip, and must
 * not drag the classes above it into the role hierarchy.
 *
 * <p>DL writes class subsumption and sub-property subsumption identically as {@code a ⊑ b},
 * so the reader infers which hierarchy a pair belongs to. Structure settles it where there
 * is any; otherwise the convention that concepts are capitalised does. SNOMED CT defeats
 * both: its identifiers are numeric, and its attribute roots are declared classes while
 * also parenting object properties. Left alone, one such name turned four ancestor classes
 * into object properties.
 *
 * <p>The kinds are therefore stated outright, in existing DL and OWL vocabulary:
 * {@code X ⊑ ⊤} and {@code X ⊑ owl:topObjectProperty}. A name carrying both is punned.
 */
class PunnedNameTest {

    private static final String NS = "http://example.org/t#";
    /** Numeric identifiers live under their own prefix, as SNOMED CT's do under sct:. */
    private static final String N = "http://example.org/n#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

    /** Looks in both namespaces: numeric identifiers live under n:, named ones under the default. */
    private boolean isClass(OWLOntology o, String local) {
        return o.containsClassInSignature(IRI.create(N + local))
            || o.containsClassInSignature(IRI.create(NS + local));
    }

    private boolean isRole(OWLOntology o, String local) {
        for (IRI iri : new IRI[] {IRI.create(N + local), IRI.create(NS + local)}) {
            if (o.containsObjectPropertyInSignature(iri)
                    || o.containsDataPropertyInSignature(iri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pun on a numeric identifier, the SNOMED CT shape: {@code n:7} parents a role and is
     * also a class, with two ordinary classes above it.
     *
     * <p>The identifiers must be numeric to be representative. A local name beginning with a
     * lower-case *letter* is classified a role by convention before any of this is reached,
     * so a name like {@code n7} would not exercise the ambiguity at all — which is what a
     * first version of this fixture did.
     */
    private static final String PUN =
        PREFIX
        + "@prefix n: <" + N + ">\n"
        + "Tumour ≡ ∃n:1.Malignant\n"
        + "@label n:1 \"an attribute\"\n"
        + "n:1 ⊑ n:7\n"
        + "n:7 ⊑ ⊤\n"
        + "n:7 ⊑ owl:topObjectProperty\n"
        + "n:7 ⊑ n:8\n"
        + "n:8 ⊑ n:9\n";

    // ── Review findings, each of these failed a first time ──────────────────

    /**
     * The pun's children must keep their sub-property axioms even with no annotations.
     *
     * <p>{@link DualDeclarationResolver} pushes a dual declaration down to any unannotated
     * sub-property, rewriting {@code SubObjectPropertyOf(Y,X)} as {@code SubClassOf(Y,X)} —
     * the exact corruption the kind statements exist to prevent. It is now told to leave a
     * stated kind alone. Before that, the mechanism worked only on documents whose entities
     * all carried labels, which every SNOMED CT extract does, so it looked correct.
     */
    @Test
    void aStatedPunIsNotUndoneWhenItsChildIsUnannotated() throws Exception {
        OWLOntology o = parse(PREFIX
            + "@prefix n: <" + N + ">\n"
            + "n:1 ⊑ owl:topObjectProperty\n"
            + "n:1 ⊑ ⊤\n"
            + "n:2 ⊑ n:1\n");
        assertTrue(o.containsAxiom(subObjectPropertyOf("2", "1")),
            () -> "the child must stay a sub-property: " + o.getLogicalAxioms());
        assertFalse(o.containsAxiom(subClassOf("2", "1")),
            "the pun must not be pushed down to the child");
    }

    /** The statement is recognised by IRI, so any prefix bound to the OWL namespace works. */
    @Test
    void theOwlNamespaceIsRecognisedUnderAnyPrefix() throws Exception {
        OWLOntology o = parse(PREFIX
            + "@prefix o: <http://www.w3.org/2002/07/owl#>\n"
            + "@prefix n: <" + N + ">\n"
            + "n:1 ⊑ o:topObjectProperty\n"
            + "n:2 ⊑ n:1\n");
        assertTrue(isRole(o, "1"), "o: is the OWL namespace, so this states a role");
        assertTrue(o.containsAxiom(subObjectPropertyOf("2", "1")),
            () -> o.getLogicalAxioms().toString());
    }

    /**
     * And the converse: a document that binds {@code owl:} elsewhere means what it says.
     *
     * <p>Matching the spelling rather than the IRI consumed this line, destroying the
     * subsumption and turning a class into a property, silently.
     */
    @Test
    void aRebound0wlPrefixIsNotAKindStatement() throws Exception {
        OWLOntology o = parse(PREFIX
            + "@prefix owl: <http://example.org/fake#>\n"
            + "MyClass ⊑ owl:topObjectProperty\n");
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "MyClass")),
                df.getOWLClass(IRI.create("http://example.org/fake#topObjectProperty")))),
            () -> "the subsumption must survive against the bound namespace: "
                + o.getLogicalAxioms());
        assertFalse(o.containsObjectPropertyInSignature(IRI.create(NS + "MyClass")),
            "MyClass must not be reclassified as a property");
    }

    /** A `⊤` subsumption an author wrote must not be eaten, nor written twice. */
    @Test
    void anAuthoredTopSubsumptionSurvivesAndIsWrittenOnce() throws Exception {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLAxiom authored = df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "lowerCase")), df.getOWLThing());

        String written = rewrite(PREFIX + "lowerCase ⊑ ⊤\nlowerCase ⊑ Other\n");
        assertEquals(1, countOccurrences(written, "lowerCase ⊑ ⊤"),
            () -> "the kind statement and the axiom must not both be written:\n" + written);
        OWLOntology back = parse(written);
        assertTrue(back.containsAxiom(authored),
            () -> "the ⊤ subsumption must survive: " + back.getLogicalAxioms());
        assertEquals(written, rewrite(written), "writing must be idempotent");
    }

    /** An IRI that is a property and also an individual must state its kind once, not twice. */
    @Test
    void aPropertyThatIsAlsoAnIndividualStatesItsKindOnce() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI iri = IRI.create(NS + "Studies");
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(iri)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLNamedIndividual(iri)));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(iri), df.getOWLObjectProperty(IRI.create(NS + "rel"))));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(1, countOccurrences(written, "Studies ⊑ owl:topObjectProperty"),
            () -> "the property pass and the individual pass both wrote it:\n" + written);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private OWLAxiom subObjectPropertyOf(String sub, String sup) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        return df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(IRI.create(N + sub)),
            df.getOWLObjectProperty(IRI.create(N + sup)));
    }

    // ── The defect ──────────────────────────────────────────────────────────

    @Test
    void thePunIsBothAClassAndARole() throws Exception {
        OWLOntology o = parse(PUN);
        assertTrue(isClass(o, "7"), "the punned name must still be a class");
        assertTrue(isRole(o, "7"), "the punned name must still be a role");
    }

    @Test
    void theClassesAboveThePunStayClasses() throws Exception {
        OWLOntology o = parse(PUN);
        for (String above : new String[] {"8", "9"}) {
            assertTrue(isClass(o, above), above + " must stay a class");
            assertFalse(isRole(o, above), above + " must not become a role");
        }
    }

    @Test
    void theSubsumptionsAboveThePunStayClassSubsumptions() throws Exception {
        OWLOntology o = parse(PUN);
        assertTrue(o.containsAxiom(subClassOf("7", "8")), o.getLogicalAxioms().toString());
        assertTrue(o.containsAxiom(subClassOf("8", "9")), o.getLogicalAxioms().toString());
    }

    @Test
    void theRolesBelowThePunStayRoles() throws Exception {
        // Downward classification must still cross the pun: being the parent of roles is
        // what makes the name punned in the first place.
        OWLOntology o = parse(PUN);
        assertTrue(isRole(o, "1"), "a role below the pun must stay a role");
    }

    // ── The statements are consumed, not turned into axioms ─────────────────

    @Test
    void theKindStatementsAddNoAxioms() throws Exception {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLOntology o = parse(PUN);
        assertFalse(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(N + "7")), df.getOWLThing())),
            "X ⊑ ⊤ for a punned name is a declaration, not a subsumption to owl:Thing");
        assertFalse(o.getLogicalAxioms().toString().contains("topObjectProperty"),
            "owl:topObjectProperty must not appear in an axiom: " + o.getLogicalAxioms());
    }

    // ── What must not change ────────────────────────────────────────────────

    @Test
    void anOrdinaryClassKeepsItsSubsumptionUnderTop() throws Exception {
        // Every corpus document opens its blocks with this. It must keep meaning exactly
        // what it has always meant — treating it as a pun marker is what made an earlier
        // attempt reject valid documents.
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLOntology o = parse(PREFIX + "Station ⊑ ⊤\n");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "Station")), df.getOWLThing())),
            "Station ⊑ ⊤ must stay a subsumption: " + o.getLogicalAxioms());
    }

    @Test
    void aRoleUnderACapitalisedClassDoesNotMakeItARole() throws Exception {
        // The case that an earlier attempt turned into a hard parse failure, because it
        // exempted every name written with `⊑ ⊤` from the class protection.
        OWLOntology o = parse(PREFIX
            + "Station ⊑ ⊤\n"
            + "∃connectsTo.⊤ ⊑ Station\n"
            + "⊤ ⊑ ∀connectsTo.Station\n"
            + "connectsTo ⊑ Station\n");
        assertTrue(isClass(o, "Station"), "Station must stay a class");
        assertFalse(isRole(o, "Station"), "Station must not become a role");
    }

    @Test
    void anOrdinaryRoleHierarchyStillPropagatesUpward() throws Exception {
        // Load-bearing for hand-written DLe: a parent role with no domain or range of its
        // own is normal, and must still be recognised as a role.
        OWLOntology o = parse(PREFIX
            + "Tumour ≡ ∃monitors.Malignant\n"
            + "@label monitors \"monitors\"\n"
            + "monitors ⊑ responsibility\n");
        assertTrue(isRole(o, "responsibility"),
            "an uncapitalised parent of a role must be a role");
    }

    // ── The writer states the kinds, and only where needed ──────────────────

    @Test
    void theWriterStatesAPunAndNothingElse() throws Exception {
        String written = rewrite(PUN);
        assertTrue(written.contains("n:7 ⊑ owl:topObjectProperty"),
            "the pun's role kind must be stated:\n" + written);
        assertTrue(written.contains("n:7 ⊑ ⊤"),
            "the pun's class kind must be stated:\n" + written);
        // n8 and n9 are unambiguous classes; n1 an unambiguous role.
        for (String quiet : new String[] {"n:8 ⊑ owl:top", "n:9 ⊑ owl:top", "n:1 ⊑ owl:top"}) {
            assertFalse(written.contains(quiet), quiet + " should not be stated:\n" + written);
        }
    }

    @Test
    void theWriterStatesNothingForAnUnambiguousDocument() throws Exception {
        String written = rewrite(PREFIX
            + "Station ⊑ ⊤\n"
            + "∃connectsTo.⊤ ⊑ Station\n"
            + "⊤ ⊑ ∀connectsTo.Station\n");
        assertFalse(written.contains("owl:topObjectProperty"), written);
        assertFalse(written.contains("owl:topDataProperty"), written);
    }

    @Test
    void aPunSurvivesARoundTrip() throws Exception {
        OWLOntology first = parse(PUN);
        OWLOntology second = parse(rewrite(PUN));
        assertEquals(first.getLogicalAxioms(), second.getLogicalAxioms(),
            "a punned document must round-trip with the same axioms");
        assertTrue(isClass(second, "7") && isRole(second, "7"),
            "the pun must survive the round trip");
    }

    private String rewrite(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLAxiom subClassOf(String sub, String sup) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(N + sub)),
                                        df.getOWLClass(IRI.create(N + sup)));
    }
}
