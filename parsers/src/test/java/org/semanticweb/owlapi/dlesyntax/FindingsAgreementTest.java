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

    /** Runs both scanner passes over a document and returns any disagreements. */
    private static List<String> disagreements(String document) {
        DLESyntaxLexer lexer = new DLESyntaxLexer(CharStreams.fromString(document));
        DLESyntaxParser parser = new DLESyntaxParser(new CommonTokenStream(lexer));
        DLESyntaxParser.OntologyContext tree = parser.ontology();
        EntityTypeScanner scanner = new EntityTypeScanner();
        scanner.collectDatatypeDefinitions(tree);
        scanner.visit(tree);
        scanner.propagatePropertyTypes();
        return scanner.findingsDisagreements();
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
