package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parentheses around a property expression in a role position.
 *
 * <p>Generators — LLMs in particular — write {@code ∃(ownerOrg⁻).Self} rather
 * than {@code ∃ownerOrg⁻.Self}. The parentheses are redundant here, but a parser
 * has to understand the structure before it can decide that, so they are
 * accepted rather than rejected.
 *
 * <p>The property is that parentheses are <em>transparent</em>: a parenthesised
 * expression must produce exactly the same axioms as the same expression written
 * without them.
 */
class ParenthesisedPropertyExprTest {

    private static final String PREFIX = "@prefix : <http://example.org/t#>\n";

    private OWLOntology parse(String body) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(
            new StringDocumentSource(PREFIX + body),
            ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

    /** Logical axioms as strings, so two documents can be compared for sameness. */
    private Set<String> logicalAxioms(String body) throws Exception {
        return parse(body).getLogicalAxioms().stream()
            .map(OWLAxiom::toString)
            .collect(Collectors.toSet());
    }

    private void equivalentSpellings(String parenthesised, String plain) throws Exception {
        Set<String> expected = logicalAxioms(plain);
        // Without this, two documents that both parsed to nothing would compare
        // equal and the test would prove nothing.
        assertFalse(expected.isEmpty(),
            "\"" + plain + "\" produced no logical axioms, so there is nothing to compare");
        assertEquals(expected, logicalAxioms(parenthesised),
            "\"" + parenthesised + "\" must mean the same as \"" + plain + "\"");
    }

    // ── The reported expression ──────────────────────────────────────────────

    @Test
    void reportedExpression_parses() throws Exception {
        // The expression that could not be parsed before, verbatim.
        OWLOntology ontology = parse(
            "⊤ ⊑ ∀ownerOrg.Org\n"
            + "⊤ ⊑ ∀consumedBy.Application\n"
            + "FlaggedOwnerOrgNotConsumer ≡ Application ⊓ ∃ownerOrg.⊤ ⊓ ∃consumedBy.⊤ "
            + "⊓ ¬∃consumedBy.(∃(ownerOrg⁻).Self)\n");
        assertFalse(ontology.isEmpty());
    }

    @Test
    void reportedExpression_matchesTheParenFreeSpelling() throws Exception {
        String shared =
            "⊤ ⊑ ∀ownerOrg.Org\n"
            + "⊤ ⊑ ∀consumedBy.Application\n"
            + "FlaggedOwnerOrgNotConsumer ≡ Application ⊓ ∃ownerOrg.⊤ ⊓ ∃consumedBy.⊤ "
            + "⊓ ¬∃consumedBy.(∃%s.Self)\n";
        equivalentSpellings(String.format(shared, "(ownerOrg⁻)"),
                            String.format(shared, "ownerOrg⁻"));
    }

    // ── Transparency across every role position ─────────────────────────────

    @Test
    void someValuesFrom() throws Exception {
        equivalentSpellings("A ≡ ∃(owns⁻).B\n", "A ≡ ∃owns⁻.B\n");
    }

    @Test
    void allValuesFrom() throws Exception {
        equivalentSpellings("A ≡ ∀(owns⁻).B\n", "A ≡ ∀owns⁻.B\n");
    }

    @Test
    void hasSelf() throws Exception {
        equivalentSpellings("A ≡ ∃(owns⁻).Self\n", "A ≡ ∃owns⁻.Self\n");
    }

    @Test
    void cardinalityRestriction() throws Exception {
        equivalentSpellings("A ≡ ≥2 (owns⁻).B\n", "A ≡ ≥2 owns⁻.B\n");
    }

    @Test
    void unqualifiedCardinalityRestriction() throws Exception {
        equivalentSpellings("A ≡ ≤3 (owns⁻)\n", "A ≡ ≤3 owns⁻\n");
    }

    @Test
    void implicitSomeValuesFrom() throws Exception {
        equivalentSpellings("A ≡ (owns⁻).B\n", "A ≡ owns⁻.B\n");
    }

    @Test
    void propertyChain() throws Exception {
        equivalentSpellings("(a⁻) ∘ b ⊑ c\n", "a⁻ ∘ b ⊑ c\n");
    }

    @Test
    void functionalPropertyAxiom() throws Exception {
        equivalentSpellings("≤1 (owns⁻).⊤\n", "≤1 owns⁻.⊤\n");
    }

    @Test
    void nonInverseRoleMayAlsoBeParenthesised() throws Exception {
        equivalentSpellings("A ≡ ∃(owns).B\n", "A ≡ ∃owns.B\n");
    }

    // ── Nesting and cancellation ────────────────────────────────────────────

    @Test
    void inverseOutsideTheParenthesesMeansTheSame() throws Exception {
        equivalentSpellings("A ≡ ∃(owns)⁻.B\n", "A ≡ ∃owns⁻.B\n");
    }

    @Test
    void nestedParenthesesAreTransparent() throws Exception {
        equivalentSpellings("A ≡ ∃(((owns⁻))).B\n", "A ≡ ∃owns⁻.B\n");
    }

    @Test
    void doubledInverseCancels() throws Exception {
        // r⁻⁻ is r. OWL has no nested inverse, so this must reduce rather than
        // produce an inverse of an inverse.
        equivalentSpellings("A ≡ ∃(owns⁻)⁻.B\n", "A ≡ ∃owns.B\n");
    }

    @Test
    void tripledInverseIsAnInverse() throws Exception {
        equivalentSpellings("A ≡ ∃((owns⁻)⁻)⁻.B\n", "A ≡ ∃owns⁻.B\n");
    }

    // ── Entity typing must be unaffected ────────────────────────────────────

    @Test
    void parenthesisedRoleIsStillClassifiedAsAnObjectProperty() throws Exception {
        OWLOntology ontology = parse("A ≡ ∃(owns⁻).B\n");
        assertTrue(ontology.getObjectPropertiesInSignature().stream()
                .anyMatch(p -> p.getIRI().toString().endsWith("#owns")),
            "owns must be an object property: " + ontology.getObjectPropertiesInSignature());
    }

    @Test
    void parenthesesDoNotTurnADataPropertyIntoAnObjectProperty() throws Exception {
        OWLOntology ontology = parse("⊤ ⊑ ∀(age).xsd:integer\n");
        assertTrue(ontology.getDataPropertiesInSignature().stream()
                .anyMatch(p -> p.getIRI().toString().endsWith("#age")),
            "age must be a data property: " + ontology.getDataPropertiesInSignature());
    }

    // ── Predicate restrictions hash their expression into an IRI ────────────

    @Test
    void predicateRestrictionIrisIgnoreRedundantParentheses() throws Exception {
        // The synthetic class IRI embeds a hash of the expression text. If that
        // text kept the parentheses, the same expression written two ways would
        // mint two different classes.
        equivalentSpellings(
            "greaterThan(x,y) ≝ x > y\n"
                + "⊤ ⊑ ∀a.xsd:integer\n⊤ ⊑ ∀b.xsd:integer\n"
                + "Rising ≡ ∃(a),(b).greaterThan\n",
            "greaterThan(x,y) ≝ x > y\n"
                + "⊤ ⊑ ∀a.xsd:integer\n⊤ ⊑ ∀b.xsd:integer\n"
                + "Rising ≡ ∃a,b.greaterThan\n");
    }

    // ── Class-position parentheses must keep working ────────────────────────

    @Test
    void parenthesisedClassExpressionsAreUnaffected() throws Exception {
        equivalentSpellings("A ≡ (B ⊔ C) ⊓ D\n", "A ≡ D ⊓ (B ⊔ C)\n");
    }

    @Test
    void parenthesisedInverseInClassPositionStillParses() throws Exception {
        equivalentSpellings("contains ≡ (locatedIn⁻)\n", "contains ≡ locatedIn⁻\n");
    }

    // ── Writing back out ────────────────────────────────────────────────────

    @Test
    void parsedParenthesesRoundTripThroughTheStorer() throws Exception {
        // Parentheses collapse at parse time, so the storer never sees them and
        // writes the canonical spelling. What matters is that its output parses
        // back to the same axioms.
        // No @prefix here, deliberately: names resolve to the DLe default
        // namespace on both sides of the round trip. Declaring a custom default
        // prefix would drag prefix handling into a test about role expressions —
        // and the storer takes its prefix map from the ontology's recorded
        // format, which a directly-invoked parser does not set.
        String body = "⊤ ⊑ ∀ownerOrg.Org\n"
            + "Flagged ≡ Application ⊓ ¬∃consumedBy.(∃(ownerOrg⁻).Self)\n";

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology original = manager.createOntology();
        new DLEOntologyParser().parse(
            new StringDocumentSource(body), original,
            manager.getOntologyLoaderConfiguration());
        Set<OWLLogicalAxiom> originalAxioms = original.getLogicalAxioms();
        assertFalse(originalAxioms.isEmpty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(original, new DLESyntaxDocumentFormat(),
            new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        // The canonical output carries no redundant parentheses around the role.
        assertTrue(written.contains("ownerOrg\u207B"),
            "expected an inverse role in the output:\n" + written);
        assertFalse(written.contains("(ownerOrg\u207B)"),
            "output should not re-introduce redundant parentheses:\n" + written);

        OWLOntologyManager manager2 = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = manager2.createOntology();
        new DLEOntologyParser().parse(
            new StreamDocumentSource(new ByteArrayInputStream(out.toByteArray())),
            reloaded,
            manager2.getOntologyLoaderConfiguration());

        assertEquals(originalAxioms, reloaded.getLogicalAxioms(),
            "written DLe must parse back to the same axioms:\n" + written);
    }
}
