package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Inverting a data property is a logical error, and must be reported as one.
 *
 * <p>{@code ⊤ ⊑ ∀age⁻.xsd:integer} is grammatical. It is nonetheless impossible:
 * the inverse of a data property would put a literal in the subject position of
 * a triple, so OWL has no such construct. Previously this escaped the axiom
 * visitor as {@code IllegalStateException: Expected class expression, got:
 * xsd:integer}, which named neither the property at fault nor the reason.
 */
class DataPropertyInverseTest {

    private static final String PREFIX = "@prefix : <http://example.org/t#>\n";

    private OWLOntology parse(String body) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(
            new StringDocumentSource(PREFIX + body), ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

    // ── The reported expression ──────────────────────────────────────────────

    @Test
    void reportedExpression_isADiagnosticNotAnInternalError() {
        DLESemanticException error = assertThrows(DLESemanticException.class,
            () -> parse("⊤ ⊑ ∀age⁻.xsd:integer\n"));

        String message = error.getMessage();
        // The author needs to know which property, and why it cannot work.
        assertTrue(message.contains("'age'"), message);
        assertTrue(message.contains("inverse"), message);
        assertTrue(message.contains("data property"), message);
        assertTrue(message.contains("subject position"), message);
        // And where to look.
        assertTrue(message.contains("2:"), "expected a line:column reference: " + message);
    }

    @Test
    void isNotReportedAsASyntaxError() {
        // The document parses; the problem is what it means. Conflating the two
        // sends the author looking for a typo.
        DLESemanticException error = assertThrows(DLESemanticException.class,
            () -> parse("⊤ ⊑ ∀age⁻.xsd:integer\n"));
        assertTrue(error.getMessage().startsWith("DLE semantic error"), error.getMessage());
        assertFalse(error.getMessage().contains("syntax"), error.getMessage());
    }

    // ── Every route to the same conflict ────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "⊤ ⊑ ∀age⁻.xsd:integer",              // range position
        "A ≡ ∃age⁻.xsd:integer",               // existential
        "A ≡ ≥2 age⁻.xsd:integer",             // qualified cardinality
        "A ≡ age⁻.xsd:integer",                // implicit existential
        "A ≡ ∃(age⁻).xsd:integer",             // parenthesised role
        "A ≡ ∃(age)⁻.xsd:integer",             // inverse outside the parentheses
    })
    void everyRoleFormIsCaught(String body) {
        assertThrows(DLESemanticException.class, () -> parse(body + "\n"));
    }

    @Test
    void conflictSpreadAcrossTwoAxiomsIsCaught() {
        // The inverse and the datatype evidence need not be in one expression:
        // `age` is a data property here by virtue of the first axiom.
        assertThrows(DLESemanticException.class,
            () -> parse("⊤ ⊑ ∀age.xsd:integer\nA ≡ ∃age⁻.B\n"));
    }

    @Test
    void conflictReachedThroughSubPropertyPropagationIsCaught() {
        // `birthYear` becomes a data property only via propagation over
        // `birthYear ⊑ age`, which is why validation runs after it.
        assertThrows(DLESemanticException.class,
            () -> parse("⊤ ⊑ ∀age.xsd:integer\nbirthYear ⊑ age\nA ≡ ∃birthYear⁻.B\n"));
    }

    // ── What must keep working ──────────────────────────────────────────────

    @Test
    void inverseOfAnObjectPropertyIsFine() throws Exception {
        OWLOntology ontology = parse("⊤ ⊑ ∀owns.Animal\nA ≡ ∃owns⁻.Person\n");
        assertFalse(ontology.getLogicalAxioms().isEmpty());
    }

    @Test
    void dataPropertyWithoutAnInverseIsFine() throws Exception {
        OWLOntology ontology = parse("⊤ ⊑ ∀age.xsd:integer\nA ≡ ∃age.xsd:integer\n");
        assertFalse(ontology.getLogicalAxioms().isEmpty());
    }

    @Test
    void namedInverseOfAnObjectPropertyIsFine() throws Exception {
        OWLOntology ontology = parse("⊤ ⊑ ∀locatedIn.Place\ncontains ≡ locatedIn⁻\n");
        assertFalse(ontology.getLogicalAxioms().isEmpty());
    }

    @Test
    void inverseInAPropertyChainIsFine() throws Exception {
        OWLOntology ontology = parse(
            "⊤ ⊑ ∀a.C\n⊤ ⊑ ∀b.C\n⊤ ⊑ ∀c.C\na ∘ b⁻ ⊑ c\n");
        assertFalse(ontology.getLogicalAxioms().isEmpty());
    }

    @Test
    void topFillerDoesNotImplyADataProperty() throws Exception {
        // ⊤ is valid for either kind, so `∃age⁻.⊤` must not be rejected on the
        // strength of the filler alone.
        OWLOntology ontology = parse("A ≡ ∃age⁻.⊤\n");
        assertFalse(ontology.getLogicalAxioms().isEmpty());
    }

    // ── The generic backstop still reads as a diagnostic ────────────────────

    /**
     * Other well-formed-but-impossible documents must report the same way.
     *
     * <p>The point of the change is not one message but a rule: a problem with the
     * *document* is a diagnostic, never an internal exception leaking out of the
     * visitor. These reach three different code paths — the data-range check, the
     * prefix table and the facet table — and previously all three threw
     * {@code IllegalStateException}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "⊤ ⊑ ∀owns.Animal\nA ≡ ∃owns.xsd:integer",          // class and datatype fillers
        "A ≡ nosuch:Thing",                                   // undeclared prefix
        "⊤ ⊑ ∀id.[xsd:string ⊓ [notAFacet \"x\"]]",           // unknown facet
    })
    void documentProblemsAreDiagnosticsNotInternalErrors(String body) {
        DLESemanticException error =
            assertThrows(DLESemanticException.class, () -> parse(body + "\n"));
        assertTrue(error.getMessage().startsWith("DLE semantic error"), error.getMessage());
        // Long enough to actually say something; the old messages were bare
        // "Expected data range, got: X" internal-error text.
        assertTrue(error.getMessage().length() > 40, error.getMessage());
    }
}
