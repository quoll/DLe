package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
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
 * Redundant parentheses are transparent.
 *
 * <p>Generators — LLMs in particular — write {@code ∃(ownerOrg⁻).Self} rather
 * than {@code ∃ownerOrg⁻.Self}. The parentheses are redundant, but a parser has
 * to understand the structure before it can decide that, so they are accepted
 * rather than rejected.
 *
 * <p>The invariant under test: wherever parentheses are redundant, a
 * parenthesised expression produces exactly the same axioms as the same
 * expression written without them. Parentheses that <em>group</em> — as in
 * {@code (A ⊔ B) ⊓ C} — are not redundant and must survive; see
 * {@link #parenthesesThatGroupAreNotStripped()}.
 *
 * <p>The transparency covers role positions, class positions, the {@code Self}
 * filler and predicate-restriction fillers. It deliberately stops at
 * keyword-argument positions such as {@code Trans(r)}; see
 * {@link #keywordArgumentPositionsTakeABareName}.
 */
class ParenthesisTransparencyTest {

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

    /** IRIs of the synthetic dle: classes a document produces. */
    private Set<String> predicateClassIris(String body) throws Exception {
        return parse(body).classesInSignature()
            .map(c -> c.getIRI().toString())
            .filter(iri -> iri.startsWith(DLESyntaxAxiomVisitor.DLE_NS))
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
    void intersectionOperandOrderDoesNotMatter() throws Exception {
        // Commutativity only — OWLAPI holds intersection operands in a set. This
        // says nothing about whether the parentheses were honoured; see below.
        equivalentSpellings("A ≡ (B ⊔ C) ⊓ D\n", "A ≡ D ⊓ (B ⊔ C)\n");
    }

    @Test
    void parenthesesThatGroupAreNotStripped() throws Exception {
        // `(B ⊔ C) ⊓ D` is not `B ⊔ (C ⊓ D)`. Asserted as an inequality against
        // the other grouping, because comparing two spellings of the *same*
        // grouping would pass even if both were parsed wrongly in the same way —
        // which is what made the previous version of this test vacuous.
        assertNotEquals(
            logicalAxioms("A ≡ (B ⊔ C) ⊓ D\n"),
            logicalAxioms("A ≡ B ⊔ (C ⊓ D)\n"),
            "parentheses that change the grouping must change the axioms");

        // Union binds loosest, so the unparenthesised form is `B ⊔ (C ⊓ D)`.
        equivalentSpellings("A ≡ B ⊔ (C ⊓ D)\n", "A ≡ B ⊔ C ⊓ D\n");

        // And the grouped form really is an intersection containing a union.
        String axiom = logicalAxioms("A ≡ (B ⊔ C) ⊓ D\n").stream()
            .filter(a -> a.startsWith("EquivalentClasses")).findFirst().orElseThrow();
        assertTrue(axiom.matches(".*ObjectIntersectionOf\\(.*ObjectUnionOf\\(.*"),
            "expected a union nested inside an intersection, got: " + axiom);
    }

    // ── Class positions get the same transparency as role positions ─────────

    @ParameterizedTest(name = "contains ≡ {0}")
    @ValueSource(strings = {
        "locatedIn⁻",       // the plain spelling, for comparison
        "(locatedIn⁻)",     // parenthesised inverse
        "(locatedIn)⁻",     // inverse outside the parentheses
        "((locatedIn))⁻",
        "((locatedIn)⁻)",
        "locatedIn⁻⁻⁻",     // odd parity is still an inverse
    })
    void inverseInClassPositionAcceptsAnySpelling(String spelling) throws Exception {
        equivalentSpellings("contains ≡ " + spelling + "\n", "contains ≡ locatedIn⁻\n");
    }

    @Test
    void doubledInverseCancelsInClassPositionToo() throws Exception {
        equivalentSpellings("contains ≡ locatedIn⁻⁻\n", "contains ≡ locatedIn\n");
        equivalentSpellings("contains ≡ (locatedIn⁻)⁻\n", "contains ≡ locatedIn\n");
    }

    @Test
    void inverseOnTheLeftOfAnAxiom() throws Exception {
        equivalentSpellings("(contains)⁻ ≡ locatedIn\n", "contains⁻ ≡ locatedIn\n");
    }

    @Test
    void inverseInASubPropertyAxiom() throws Exception {
        equivalentSpellings("contains ⊑ (locatedIn)⁻\n", "contains ⊑ locatedIn⁻\n");
    }

    // ── The Self filler ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "∃owns.{0}")
    @ValueSource(strings = {"Self", "(Self)", "((Self))"})
    void selfFillerMayBeParenthesised(String spelling) throws Exception {
        equivalentSpellings("A ≡ ∃owns." + spelling + "\n", "A ≡ ∃owns.Self\n");
    }

    @Test
    void parenthesesOnBothRoleAndSelfFiller() throws Exception {
        equivalentSpellings("A ≡ ∃(owns⁻).(Self)\n", "A ≡ ∃owns⁻.Self\n");
    }

    // ── Predicate-restriction fillers ───────────────────────────────────────

    private static final String PREDICATE_SETUP =
        "greaterThan(x,y) ≝ x > y\n⊤ ⊑ ∀a.xsd:integer\n⊤ ⊑ ∀b.xsd:integer\n";

    @ParameterizedTest(name = "R ≡ ∃{0}")
    @ValueSource(strings = {
        "a,b.greaterThan",
        "a,b.(greaterThan)",
        "(a),(b).greaterThan",
        "(a),(b).((greaterThan))",
    })
    void multiRolePredicateFillerMayBeParenthesised(String spelling) throws Exception {
        equivalentSpellings(PREDICATE_SETUP + "R ≡ ∃" + spelling + "\n",
                            PREDICATE_SETUP + "R ≡ ∃a,b.greaterThan\n");
    }

    @Test
    void singleRolePredicateFillerMayBeParenthesised() throws Exception {
        equivalentSpellings(PREDICATE_SETUP + "R ≡ ∃a.(greaterThan)\n",
                            PREDICATE_SETUP + "R ≡ ∃a.greaterThan\n");
    }

    @Test
    void parenthesesDoNotChangeThePredicateClassIri() throws Exception {
        // The synthetic class IRI embeds a hash of the expression text, and that
        // IRI is the contract with the Python implementation. Redundant
        // parentheses must not move it.
        assertEquals(
            predicateClassIris(PREDICATE_SETUP + "R ≡ ∃a,b.greaterThan\n"),
            predicateClassIris(PREDICATE_SETUP + "R ≡ ∃(a),(b).((greaterThan))\n"));
    }

    // ── Where transparency deliberately stops ───────────────────────────────

    /**
     * Keyword-argument positions name an entity rather than take an expression,
     * and the keyword's own parentheses already delimit the argument. These are
     * pinned as errors so the boundary cannot drift unnoticed, and so the Python
     * port does not have to guess where transparency ends.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "Trans((locatedIn))",
        "Disj((contains),(locatedIn))",
        "C ⊑ key((id))",
    })
    void keywordArgumentPositionsTakeABareName(String body) {
        // Specifically a syntax error, not any exception: a broad assertThrows
        // would also be satisfied by an internal failure elsewhere.
        assertThrows(OWLParserException.class,
            () -> parse("⊤ ⊑ ∀id.xsd:string\n" + body + "\n"),
            "parentheses are not accepted in keyword-argument positions");
    }

    // ── Writing back out ────────────────────────────────────────────────────

    @Test
    void parsedParenthesesRoundTripThroughTheStorer() throws Exception {
        // Parentheses collapse at parse time, so the storer never sees them and
        // writes the canonical spelling. What matters is that its output parses
        // back to the same axioms.
        // Written with the document's own @prefix, which is the realistic case.
        // This test previously avoided declaring one: the storer discarded the
        // format the parser returned, so the prefix was dropped and every name
        // silently moved to the DLe default namespace. That is fixed, and
        // StorerPrefixTest covers it directly.
        String body = PREFIX
            + "⊤ ⊑ ∀ownerOrg.Org\n"
            + "Flagged ≡ Application ⊓ ¬∃consumedBy.(∃(ownerOrg⁻).Self)\n";

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology original = manager.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(body), original,
            manager.getOntologyLoaderConfiguration());
        Set<OWLLogicalAxiom> originalAxioms = original.getLogicalAxioms();
        assertFalse(originalAxioms.isEmpty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(original, format, new StreamDocumentTarget(out));
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
        assertTrue(written.contains("@prefix : <http://example.org/t#>"),
            "the document's own prefix must survive the round trip:\n" + written);
    }
}
