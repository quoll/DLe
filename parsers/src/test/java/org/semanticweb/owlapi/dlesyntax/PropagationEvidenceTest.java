package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A kind propagates from evidence, never from a guess.
 *
 * <p>The case convention is allowed to put a name in a name set on nothing but its spelling.
 * That is useful and it stays. What it may not do is become a source: a guess carried across
 * a subsumption edge and recorded as {@code PROPAGATED} is indistinguishable from a kind the
 * document stated, and it then outranks the real thing.
 *
 * <p>That is not hypothetical. It refused this document, which is sound:
 *
 * <pre>
 * appState ⊑ calcList
 * calcList ⊑ calcValue
 * ⊤ ⊑ ∀appState.{"Managed","Owned"}
 * </pre>
 *
 * <p>All three names are lower case, so the pair heuristic guessed {@code calcList} and
 * {@code calcValue} to be object properties before line 3 was read. Propagation re-exported
 * that guess as evidence, and the literal enumeration on line 3 — which says outright that
 * {@code appState} is a data property — could not get past it. The diagnostic blamed the one
 * statement in the document that was certainly right. Moving line 3 up to line 2 made the same
 * document parse, which is the signature of a resolution that depends on reading order.
 *
 * <p>So propagation reads {@link Findings}, and the guess is recorded at {@code GUESSED},
 * below the evidence threshold. These tests pin both halves: the guess still classifies, and
 * it no longer propagates.
 */
class PropagationEvidenceTest {

    private static final String PREFIX = "@prefix : <http://example.org/p#>\n";

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    /** The local names the ontology holds as properties of the given kind. */
    private List<String> namesOf(OWLOntology o, EntityType<?> type) {
        List<String> names = new ArrayList<>();
        o.signature().filter(e -> e.getEntityType() == type)
            .forEach(e -> e.getIRI().getRemainder().ifPresent(names::add));
        names.sort(String::compareTo);
        return names;
    }

    private EntityTypeScanner scan(String document) {
        DLESyntaxLexer lexer = new DLESyntaxLexer(CharStreams.fromString(document));
        DLESyntaxParser parser = new DLESyntaxParser(new CommonTokenStream(lexer));
        DLESyntaxParser.OntologyContext tree = parser.ontology();
        EntityTypeScanner scanner = new EntityTypeScanner();
        scanner.collectDatatypeDefinitions(tree);
        scanner.visit(tree);
        scanner.propagatePropertyTypes();
        return scanner;
    }

    /** The shape that was refused, in the order that refused it. */
    @Test
    void evidenceReachesTwoHopsUpAGuessedChain() throws Exception {
        OWLOntology o = read(PREFIX
            + "appState ⊑ calcList\n"
            + "calcList ⊑ calcValue\n"
            + "⊤ ⊑ ∀appState.{\"Managed\",\"Owned\"}\n");
        assertEquals(Arrays.asList("appState", "calcList", "calcValue"),
            namesOf(o, EntityType.DATA_PROPERTY),
            "the literal enumeration makes appState a data property, and a data property's"
                + " parents are data properties too");
        assertEquals(List.of(), namesOf(o, EntityType.OBJECT_PROPERTY),
            "nothing in this document is an object property");
    }

    /**
     * And the answer does not depend on where the evidence sits in the file.
     *
     * <p>All six orderings of the three statements, because the defect was invisible in two of
     * them: with the evidence on line 2 the document always parsed.
     */
    @Test
    void theAnswerIsTheSameInEveryOrder() throws Exception {
        String[] lines = {
            "appState ⊑ calcList",
            "calcList ⊑ calcValue",
            "⊤ ⊑ ∀appState.{\"Managed\",\"Owned\"}",
        };
        int[][] orders = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        List<String> failures = new ArrayList<>();
        for (int[] order : orders) {
            String document = PREFIX + lines[order[0]] + "\n" + lines[order[1]] + "\n"
                + lines[order[2]] + "\n";
            String shape = order[0] + "" + order[1] + order[2];
            try {
                List<String> data = namesOf(read(document), EntityType.DATA_PROPERTY);
                if (!data.equals(Arrays.asList("appState", "calcList", "calcValue"))) {
                    failures.add(shape + " -> data properties " + data);
                }
            } catch (RuntimeException e) {
                failures.add(shape + " -> threw " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(),
            () -> "the resolution depends on statement order in " + failures.size()
                + " of 6 orderings:\n  " + String.join("\n  ", failures));
    }

    /** The same hole reached downward, from a data property named in a restriction. */
    @Test
    void evidenceReachesDownAGuessedChain() throws Exception {
        OWLOntology o = read(PREFIX
            + "A ⊑ ∃f.xsd:string\n"
            + "d ⊑ e\n"
            + "e ⊑ f\n");
        assertEquals(Arrays.asList("d", "e", "f"), namesOf(o, EntityType.DATA_PROPERTY),
            "the xsd:string filler makes f a data property, and its sub-properties follow");
    }

    /** The guess still does its own job: with nothing else to go on, a pair is roles. */
    @Test
    void aGuessedPairIsStillReadAsObjectProperties() throws Exception {
        OWLOntology o = read(PREFIX + "hasPart ⊑ relatedTo\n");
        assertEquals(Arrays.asList("hasPart", "relatedTo"),
            namesOf(o, EntityType.OBJECT_PROPERTY),
            "two camelCase names either side of ⊑ are properties, on the convention alone");
    }

    /**
     * And the guess is recorded as a guess.
     *
     * <p>{@code GUESSED} had no producer at all, so the tier that exists to keep a spelling
     * from being mistaken for a fact was unreachable, and the scanner's own agreement check
     * could not see the case it most needed to.
     */
    @Test
    void theCaseGuessIsRecordedBelowTheEvidenceThreshold() {
        Findings findings = findingsOf(scan(PREFIX + "hasPart ⊑ relatedTo\n"));
        for (String name : new String[] {"hasPart", "relatedTo"}) {
            Findings.Finding f = findings.firmestOf(name, Findings.Kind.OBJECT_PROPERTY);
            assertNotNull(f, () -> name + " must carry a finding, not silently sit in a set");
            assertEquals(Findings.Certainty.GUESSED, f.certainty,
                () -> name + " is a guess from its spelling, and must be recorded as one");
            assertFalse(f.certainty.isEvidence(),
                () -> name + "'s guess must not count as evidence");
            assertTrue(f.line > 0,
                () -> name + "'s finding must name the line it came from, not line 0");
        }
    }

    /** A stated kind still beats the convention outright. */
    @Test
    void aStatedKindStillOutranksTheGuess() throws Exception {
        OWLOntology o = read(PREFIX
            + "hasPart ⊑ relatedTo\n"
            + "hasPart ⊑ owl:topDataProperty\n");
        assertEquals(Arrays.asList("hasPart", "relatedTo"),
            namesOf(o, EntityType.DATA_PROPERTY),
            "the statement settles hasPart, and relatedTo follows it rather than the spelling");
    }

    private static Findings findingsOf(EntityTypeScanner scanner) {
        try {
            java.lang.reflect.Field f = EntityTypeScanner.class.getDeclaredField("findings");
            f.setAccessible(true);
            return (Findings) f.get(scanner);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
