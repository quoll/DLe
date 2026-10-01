package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The findings and the name sets must agree on which names hold which kinds.
 *
 * <p>Two representations of one thing can drift, and drift is what this area has suffered
 * from — a guess in the same set as a fact, a writer modelling a reader. {@link Findings} is
 * the evidence and the sets are the resolution propagation walks; until the sets are retired,
 * checking that they agree is the cheapest guarantee that they have not diverged.
 *
 * <p>This is deliberately a scanner-level test rather than a document round trip: it looks at
 * the two structures directly, which a round trip cannot do.
 */
class FindingsAgreementTest {

    /** Runs both scanner passes over a document and hands back the scanner. */
    private static EntityTypeScanner scan(String document) {
        DLESyntaxLexer lexer = new DLESyntaxLexer(CharStreams.fromString(document));
        DLESyntaxParser parser = new DLESyntaxParser(new CommonTokenStream(lexer));
        DLESyntaxParser.OntologyContext tree = parser.ontology();
        EntityTypeScanner scanner = new EntityTypeScanner();
        scanner.collectDatatypeDefinitions(tree);
        scanner.visit(tree);
        scanner.propagatePropertyTypes();
        return scanner;
    }

    /** Runs both scanner passes over a document and returns any disagreements. */
    private static List<String> disagreements(String document) {
        return scan(document).findingsDisagreements();
    }

    /** One of the scanner's private name sets, by field name. */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> nameSet(EntityTypeScanner scanner, String field)
            throws Exception {
        java.lang.reflect.Field f = EntityTypeScanner.class.getDeclaredField(field);
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(scanner);
    }

    /** The scanner's private findings store. */
    private static Findings findingsOf(EntityTypeScanner scanner) throws Exception {
        java.lang.reflect.Field f = EntityTypeScanner.class.getDeclaredField("findings");
        f.setAccessible(true);
        return (Findings) f.get(scanner);
    }

    /**
     * The check detects a drift it is given, in each of the four kinds it compares.
     *
     * <p>Without this, every assertion in this file is that nothing was reported, which a
     * check that compares nothing at all also satisfies — and one did: replacing
     * {@code findingsDisagreements} with {@code return new ArrayList<>()} passed the whole
     * suite. The drift is injected reflectively rather than by a document, because a
     * document that genuinely drifts is the bug this exists to find; there is no such
     * document to write once the production code is right.
     *
     * <p>One drift must yield exactly one complaint: a name in a set with no finding is a
     * disagreement about that kind only, not about the three it says nothing about.
     */
    @Test
    void aNameInASetWithNoFindingIsReported() throws Exception {
        String[][] cases = {
            {"objectPropertyNames", "OBJECT_PROPERTY"},
            {"dataPropertyNames",   "DATA_PROPERTY"},
            {"mustBeClass",         "CLASS"},
            {"datatypeNames",       "DATATYPE"},
        };
        for (String[] c : cases) {
            EntityTypeScanner scanner = scan("@prefix : <http://example.org/a#>\nA \u2291 B\n");
            assertTrue(scanner.findingsDisagreements().isEmpty(),
                () -> "the fixture must start clean, before " + c[0] + " is disturbed");
            nameSet(scanner, c[0]).add("ghost");
            List<String> found = scanner.findingsDisagreements();
            assertEquals(1, found.size(),
                () -> "a name in " + c[0] + " with no finding is one disagreement, got " + found);
            assertTrue(found.get(0).contains("ghost"),
                () -> "the complaint must name the drifting name: " + found.get(0));
            assertTrue(found.get(0).contains(c[1]),
                () -> "the complaint must name the kind " + c[1] + ": " + found.get(0));
        }
    }

    /** And the other direction: evidence for a kind, with the name absent from that set. */
    @Test
    void aFindingWithNoNameSetMembershipIsReported() throws Exception {
        EntityTypeScanner scanner = scan("@prefix : <http://example.org/a#>\nA \u2291 B\n");
        findingsOf(scanner).record("ghost", Findings.Kind.DATA_PROPERTY,
            Findings.Certainty.STATED, 7);
        List<String> found = scanner.findingsDisagreements();
        assertEquals(1, found.size(), () -> "expected one disagreement, got " + found);
        assertTrue(found.get(0).contains("ghost") && found.get(0).contains("DATA_PROPERTY"),
            () -> "the complaint must name both the name and the kind: " + found.get(0));
    }

    /**
     * Every shape that classifies a name, in one document per shape.
     *
     * <p>One per shape rather than one big document, so a disagreement names the shape that
     * caused it.
     */
    private static final String[] SHAPES = {
        "A ⊑ B",
        "A ⊑ ∃r.B",
        "A ⊑ ∀r.B",
        "A ⊑ ∃d.xsd:string",
        "A ⊑ ∀d.xsd:integer",
        "A ⊑ ∃d.{\"x\"}",
        "A ⊑ ∃d.¬xsd:string",
        "A ⊑ ∃d.[xsd:string ⊓ [minLength 3]]",
        "A ⊑ ∃r⁻.B",
        "A ⊑ ≥2 r.B",
        "A ⊑ ≥2 r",
        "A ⊑ ≥2 d.xsd:string",
        "∃r.⊤ ⊑ A",
        "⊤ ⊑ ∀r.B",
        "⊤ ⊑ ∀d.xsd:string",
        "p ∘ q ⊑ t",
        "Func(p)",
        "Trans(p)",
        "Disj(p, q)",
        "p ⊓ q ⊑ ⊥",
        "A ⊑ key(id)\n⊤ ⊑ ∀id.xsd:string",
        "r ⊑ s\nA ⊑ ∃r.B",
        "d ⊑ e\nA ⊑ ∃d.xsd:string",
        "A ≡ B",
        "A ≡ B ≡ C",
        "p ≡ q\nA ⊑ ∃p.B",
        "d ≡ e\nA ⊑ ∃d.xsd:string",
        "q ≡ r⁻\nA ⊑ ∃r.B",
        "MyType ≡ [xsd:string ⊓ [minLength 3]]\n⊤ ⊑ ∀d.MyType",
        "MyType ≡ xsd:string\n⊤ ⊑ ∀d.MyType",
        "Upper ⊑ owl:topObjectProperty",
        "Score ⊑ owl:topDataProperty",
        "Attr ⊑ ⊤\nAttr ⊑ owl:topObjectProperty\nA ⊑ Attr",
        "@ann C note \"v\"\nA ⊑ B",
        "@label A \"x\"\nA ⊑ B",
        "bob : A",
        "(bob,ann):r",
        "(bob,\"v\"):d",
        "greaterThan(x,y) ≝ x > y\n⊤ ⊑ ∀a.xsd:integer\nR ≡ ∃a.greaterThan",
        "A ⊑ ∃r.lowerC",
        "A ⊑ {b,c}",
        "A ⊑ ∃r.Self",
        "A ⊑ B ⊓ ¬C ⊔ ∃r.(D ⊓ ∀s.E)",
    };

    /**
     * The same check over a real document, which the shapes above cannot stand in for.
     *
     * <p>A corpus document exercises the shapes together — propagation chains, puns, a name
     * classified from one statement and used in another — and it is their interaction that
     * the constructed one-shape documents cannot reach.
     */
    @Test
    void theTwoRepresentationsAgreeOnACorpusDocument() throws Exception {
        java.net.URL resource =
            getClass().getResource("/data/wildlife-reserve-test.dle");
        assertNotNull(resource, "the corpus document must be on the test classpath");
        String document = new String(
            java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(resource.toURI())),
            java.nio.charset.StandardCharsets.UTF_8);
        List<String> found = disagreements(document);
        assertTrue(found.isEmpty(),
            () -> "findings and name sets disagree on the corpus document:\n  "
                + String.join("\n  ", found));
    }

    @Test
    void theTwoRepresentationsAgreeOnEveryShape() {
        List<String> failures = new ArrayList<>();
        for (String shape : SHAPES) {
            String document = "@prefix : <http://example.org/a#>\n" + shape + "\n";
            List<String> found;
            try {
                found = disagreements(document);
            } catch (RuntimeException e) {
                failures.add(shape.replace('\n', ';') + "  threw " + e.getMessage());
                continue;
            }
            if (!found.isEmpty()) {
                failures.add(shape.replace('\n', ';') + "  ->  " + found);
            }
        }
        assertTrue(failures.isEmpty(),
            () -> "findings and name sets disagree on " + failures.size() + " of "
                + SHAPES.length + " shapes:\n  " + String.join("\n  ", failures));
    }
}
