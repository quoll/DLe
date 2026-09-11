package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLFacet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a datatype facet names its value.
 *
 * <p>A facet is a function of one argument, and every other syntax that has them — XML
 * Schema, SPARQL, SHACL — writes them as a call. So {@code matches("[A-Z]{3}")} is what
 * people and generators reach for, and it is accepted alongside {@code matches "[A-Z]{3}"}.
 *
 * <p>Two spellings of one construct, not a second construct: the two parse to the same
 * axiom, and a facet only ever appears inside the brackets of a datatype restriction, so
 * the parentheses cannot be confused with a grouped class expression or with the argument
 * list of a predicate definition.
 */
class FacetSyntaxTest {

    private static final String HEAD =
        "@ontology <http://example.org/o>\n@prefix : <http://example.org/o#>\n";

    /**
     * Every facet the ontology actually uses, by the name OWL API prints for it.
     *
     * <p>Matched on the rendered form rather than the IRI: a facetRestriction prints as
     * {@code facetRestriction(pattern "…")}, using the facet's short form, so asserting on
     * the IRI silently never matches — which is how a first version of this test passed
     * while checking nothing.
     */
    private java.util.Set<OWLFacet> facetsOf(OWLOntology o) {
        java.util.Set<OWLFacet> found = new java.util.HashSet<>();
        String text = o.getLogicalAxioms().toString();
        for (OWLFacet f : OWLFacet.values()) {
            if (text.contains("facetRestriction(" + f.getShortForm() + " ")) found.add(f);
        }
        return found;
    }

    private String rewrite(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        org.semanticweb.owlapi.model.OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        manager.saveOntology(ontology, format,
            new org.semanticweb.owlapi.io.StreamDocumentTarget(out));
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The document without its explanatory header, whose prose contains parentheses. */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

    /** The two spellings must be indistinguishable once parsed. */
    @Test
    void theCallFormMeansExactlyWhatTheSpaceFormMeans() throws Exception {
        String spaced = HEAD + "⊤ ⊑ ∀code.[xsd:string ⊓ [matches \"[A-Z]{3}\"]]\n";
        String called = HEAD + "⊤ ⊑ ∀code.[xsd:string ⊓ [matches(\"[A-Z]{3}\")]]\n";
        assertEquals(parse(spaced).getLogicalAxioms(), parse(called).getLogicalAxioms());
    }

    /**
     * Every keyword in call form, asserting the facet it maps to.
     *
     * <p>Asserting only that some axiom appeared would pass even if every keyword mapped to
     * the wrong facet, which is the one thing worth checking here.
     */
    @Test
    void everyFacetKeywordTakesTheCallForm() throws Exception {
        Object[][] cases = {
            {"matches(\"[a-f]+\")", "xsd:string",  OWLFacet.PATTERN},
            {"length(4)",           "xsd:string",  OWLFacet.LENGTH},
            {"minLength(2)",        "xsd:string",  OWLFacet.MIN_LENGTH},
            {"maxLength(8)",        "xsd:string",  OWLFacet.MAX_LENGTH},
            {"min(1)",              "xsd:integer", OWLFacet.MIN_INCLUSIVE},
            {"max(9)",              "xsd:integer", OWLFacet.MAX_INCLUSIVE},
            {"minExclusive(0)",     "xsd:integer", OWLFacet.MIN_EXCLUSIVE},
            {"maxExclusive(10)",    "xsd:integer", OWLFacet.MAX_EXCLUSIVE},
            {"totalDigits(5)",      "xsd:decimal", OWLFacet.TOTAL_DIGITS},
            {"fractionDigits(2)",   "xsd:decimal", OWLFacet.FRACTION_DIGITS},
            {"langRange(\"en\")",   "rdf:PlainLiteral", OWLFacet.LANG_RANGE},
        };
        assertEquals(OWLFacet.values().length, cases.length,
            "a facet was added to OWL API and is not covered here");
        for (Object[] c : cases) {
            String doc = HEAD + "⊤ ⊑ ∀v.[" + c[1] + " ⊓ [" + c[0] + "]]\n";
            OWLOntology o = parse(doc);
            assertTrue(facetsOf(o).contains((OWLFacet) c[2]),
                () -> c[0] + " should be " + c[2] + ", got " + facetsOf(o));
        }
    }

    /**
     * The written form does not change. This is the compatibility claim of the whole
     * change — accepting a second spelling must not start rewriting every document that
     * uses the first — and it is the one thing here whose breakage would be silent.
     */
    @Test
    void theWriterStillEmitsTheSpaceForm() throws Exception {
        for (String facet : new String[] {"matches(\"[A-Z]{3}\")", "minLength(2)"}) {
            String written = rewrite(HEAD + "⊤ ⊑ ∀v.[xsd:string ⊓ [" + facet + "]]\n");
            assertFalse(statementsOnly(written).contains("("),
                () -> "output must keep the space form:\n" + written);
        }
        // And the two spellings must write identically, since they parse identically.
        assertEquals(rewrite(HEAD + "⊤ ⊑ ∀v.[xsd:string ⊓ [matches \"x\"]]\n"),
                     rewrite(HEAD + "⊤ ⊑ ∀v.[xsd:string ⊓ [matches(\"x\")]]\n"));
    }

    /** The rejections the new alternative makes possible, each with its own shape. */
    @Test
    void malformedCallFormsAreRejected() {
        for (String facet : new String[] {
                "matches()", "matches((\"x\"))", "matches(\"x\", \"y\")", "matches(\"x\"",
                "matches(xsd:string)"}) {
            assertThrows(Exception.class,
                () -> parse(HEAD + "⊤ ⊑ ∀v.[xsd:string ⊓ [" + facet + "]]\n"),
                () -> "should not parse: " + facet);
        }
    }

    /** matches is a synonym for xsd:pattern, which is the point of having it. */
    @Test
    void matchesIsASynonymForXsdPattern() throws Exception {
        String keyword = HEAD + "⊤ ⊑ ∀code.[xsd:string ⊓ [matches(\"[A-Z]{3}\")]]\n";
        String iri     = HEAD + "⊤ ⊑ ∀code.[xsd:string ⊓ [xsd:pattern(\"[A-Z]{3}\")]]\n";
        assertEquals(parse(keyword).getLogicalAxioms(), parse(iri).getLogicalAxioms());
        assertTrue(parse(keyword).getLogicalAxioms().toString().contains(OWLFacet.PATTERN.getIRI().toString())
                || parse(keyword).getLogicalAxioms().toString().contains("pattern"),
            "the facet must be xsd:pattern");
    }

    @Test
    void spellingsMayBeMixedInOneRestriction() throws Exception {
        OWLOntology o = parse(HEAD
            + "⊤ ⊑ ∀s.[xsd:string ⊓ [minLength(2)] ⊓ [maxLength 8] ⊓ [matches(\"[a-z]+\")]]\n");
        assertFalse(o.getLogicalAxioms().isEmpty());
        assertEquals(1, o.getLogicalAxioms().size());
    }

    /** The compact numeric bounds are operators, not calls, and are untouched. */
    @Test
    void theCompactNumericFormStillWorks() throws Exception {
        assertFalse(parse(HEAD + "⊤ ⊑ ∀n.xsd:integer[≥1 ⊓ ≤40]\n").getLogicalAxioms().isEmpty());
        assertFalse(parse(HEAD + "⊤ ⊑ ∀n.xsd:decimal[>0 ⊓ <10]\n").getLogicalAxioms().isEmpty());
    }

    @Test
    void anUnknownFacetIsStillRejectedInEitherForm() {
        for (String facet : new String[] {"nonsense \"x\"", "nonsense(\"x\")"}) {
            DLESemanticException thrown = assertThrows(DLESemanticException.class,
                () -> parse(HEAD + "⊤ ⊑ ∀v.[xsd:string ⊓ [" + facet + "]]\n"));
            assertTrue(thrown.getMessage().contains("facet"),
                () -> "should name the problem: " + thrown.getMessage());
        }
    }
}
