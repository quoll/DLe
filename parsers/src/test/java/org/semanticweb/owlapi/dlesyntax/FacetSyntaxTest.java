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

    @Test
    void everyFacetKeywordTakesTheCallForm() throws Exception {
        String[][] cases = {
            {"matches(\"[a-f]+\")", "xsd:string"},
            {"length(4)", "xsd:string"},
            {"minLength(2)", "xsd:string"},
            {"maxLength(8)", "xsd:string"},
            {"min(1)", "xsd:integer"},
            {"max(9)", "xsd:integer"},
            {"minExclusive(0)", "xsd:integer"},
            {"maxExclusive(10)", "xsd:integer"},
            {"totalDigits(5)", "xsd:decimal"},
            {"fractionDigits(2)", "xsd:decimal"},
        };
        for (String[] c : cases) {
            String doc = HEAD + "⊤ ⊑ ∀v.[" + c[1] + " ⊓ [" + c[0] + "]]\n";
            assertFalse(parse(doc).getLogicalAxioms().isEmpty(), () -> "failed: " + c[0]);
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
            Exception thrown = assertThrows(Exception.class,
                () -> parse(HEAD + "⊤ ⊑ ∀v.[xsd:string ⊓ [" + facet + "]]\n"));
            assertTrue(thrown.getMessage().contains("facet"),
                () -> "should name the problem: " + thrown.getMessage());
        }
    }
}
