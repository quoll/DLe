package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name under {@code ⊓}, {@code ⊔} or {@code ¬} is a class.
 *
 * <p>The three Boolean constructors build class expressions and nothing else — OWL has no
 * intersection, union or complement of properties — so the position states the kind rather
 * than suggesting it. The case convention used to have the last word instead, and this is
 * what that cost:
 *
 * <pre>
 * a ⊑ b
 * a ≡ b ⊓ c
 * </pre>
 *
 * <p>Both names on line 1 are lower case, so the pair heuristic guessed them roles and wrote
 * them into the role sets. Line 2 marks all three names classes — that rule already
 * existed — but nothing withdrew the guess, so the equivalence was still built between
 * properties, and {@code OWLObjectIntersectionOf} was cast to
 * {@code OWLObjectPropertyExpression}. What the author saw was a Java internal, for a
 * document whose only fault was never saying what its names are.
 *
 * <p>The datatype reading needs no rule of its own here and cannot collide with this one: a
 * data range is recognised from its operands already being datatypes, so a name that is not
 * known to be one can never be the operand that makes the expression a data range.
 */
class BooleanOperandKindTest {

    private static final String PREFIX = "@prefix : <http://example.org/b#>\n";

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    /** The document's own class names — owl:Thing arrives from `⊑ ⊤` and is not one. */
    private static Set<String> classes(OWLOntology o) {
        return o.classesInSignature()
            .filter(c -> c.getIRI().toString().startsWith("http://example.org/b#"))
            .map(c -> c.getIRI().getFragment())
            .filter(f -> f != null)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The local names declared as object properties. */
    private static Set<String> objectProperties(OWLOntology o) {
        return o.objectPropertiesInSignature()
            .map(p -> p.getIRI().getFragment())
            .filter(f -> f != null)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Each of the three constructors makes its operands classes, whatever their spelling.
     *
     * <p>All three crashed identically before, and the subsumption is what made them do it:
     * without it there is no guess to contradict.
     */
    @Test
    void aBooleanOperandIsAClassDespiteTheCaseConvention() throws Exception {
        for (String equivalence : new String[] {"a ≡ b ⊓ c\n", "a ≡ b ⊔ c\n", "a ≡ ¬b ⊓ c\n"}) {
            OWLOntology o = read(PREFIX + "a ⊑ b\n" + equivalence);
            assertEquals(Set.of("a", "b", "c"), classes(o),
                () -> "every name in " + equivalence.trim() + " is a class: " + classes(o));
            assertTrue(objectProperties(o).isEmpty(),
                () -> "and none of them is a property: " + objectProperties(o));
        }
    }

    /** And it does not matter which line comes first. */
    @Test
    void theAnswerDoesNotDependOnReadingOrder() throws Exception {
        String subsumption = "a ⊑ b\n";
        String equivalence = "a ≡ b ⊓ c\n";
        assertEquals(classes(read(PREFIX + subsumption + equivalence)),
                     classes(read(PREFIX + equivalence + subsumption)),
            "a guess withdrawn in one order must be withdrawn in the other");
    }

    /**
     * The whole vocabulary, lower case throughout, reads correctly.
     *
     * <p>The case that started this: a document that names its classes the way its author
     * chose rather than the way the convention expects, and says enough about them to be
     * unambiguous.
     */
    @Test
    void aLowerCaseVocabularyReadsCorrectly() throws Exception {
        OWLOntology o = read(PREFIX
            + "person ⊑ ⊤\n"
            + "employee ⊑ person\n"
            + "employee ≡ person ⊓ ∃worksFor.organisation\n");
        assertEquals(Set.of("employee", "organisation", "person"), classes(o),
            () -> "the classes: " + classes(o));
        assertEquals(Set.of("worksFor"), objectProperties(o),
            () -> "and only the role is a role: " + objectProperties(o));
    }

    /**
     * A Boolean inside a restriction filler is reached too.
     *
     * <p>These are the shapes the equivalence rule cannot see. {@code A ≡ (complex)} marks
     * the name beside the complex expression, which is enough when the Boolean is the whole
     * of one side — but {@code X ≡ ∃r.(a ⊓ b)} buries it, and the names inside were left to
     * the case convention. Each of these fails without the operand rule, whatever the
     * equivalence rule does.
     */
    @Test
    void aBooleanBuriedInARestrictionIsReachedAsWell() throws Exception {
        List<String> documents = List.of(
            "X ≡ ∃r.(a ⊓ b)\n",
            "X ⊑ ∀r.(a ⊔ b)\n",
            "∃r.⊤ ⊑ a ⊓ b\n");
        for (String use : documents) {
            OWLOntology o = read(PREFIX + "a ⊑ b\n" + use);
            assertTrue(classes(o).containsAll(Set.of("a", "b")),
                () -> use.trim() + " makes a and b classes: " + classes(o));
            assertEquals(Set.of("r"), objectProperties(o),
                () -> "and only r is a property: " + objectProperties(o));
        }
    }

    /**
     * A datatype the document defines is not marked a class by being a union operand.
     *
     * <p>The parenthesised form shares its grammar with class expressions, so without the
     * datatype guard {@code ∀d.(MyType ⊔ xsd:integer)} marked {@code MyType} a class and the
     * document was refused — "MyType is used as a datatype on line 2 and as a class on line
     * 3" — for saying nothing of the kind. A built-in is caught by the same guard; this one
     * is the case that needs the document's own definitions to be known first.
     */
    @Test
    void aDefinedDatatypeIsNotMarkedAClassByAUnion() throws Exception {
        OWLOntology o = read(PREFIX
            + "MyType ≡ xsd:string\n"
            + "⊤ ⊑ ∀d.(MyType ⊔ xsd:integer)\n");
        assertTrue(classes(o).isEmpty(),
            () -> "MyType is a datatype, not a class: " + classes(o));
        assertEquals(1, o.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
            () -> "and the range is a data range: " + o.getLogicalAxioms());
    }

    /**
     * {@code p ⊓ q ⊑ ⊥} is the one place a {@code ⊓} operand is not a class.
     *
     * <p>DLe spells property disjointness that way, so the rule has to leave that shape to
     * the subsumption visitor, which weighs the case convention itself. Marking its operands
     * classes here would have made {@code Disj(p, q)} and {@code p ⊓ q ⊑ ⊥} — the same
     * axiom — disagree again.
     */
    @Test
    void theDisjointPropertyIdiomIsUntouched() throws Exception {
        OWLOntology o = read(PREFIX + "p ⊓ q ⊑ ⊥\n");
        assertEquals(Set.of("p", "q"), objectProperties(o),
            () -> "p and q stay properties: " + objectProperties(o));
        assertEquals(1, o.getAxioms(AxiomType.DISJOINT_OBJECT_PROPERTIES).size(),
            () -> "and the axiom is still a disjointness: " + o.getLogicalAxioms());
    }

    /**
     * A data range is recognised from its operands, so its names are not marked classes.
     *
     * <p>Both spellings: the parenthesised form, which shares the {@code ⊓}/{@code ⊔}
     * grammar with class expressions, and the bracketed facet form.
     */
    @Test
    void aDataRangeKeepsItsDatatypes() throws Exception {
        for (String range : new String[] {
                "⊤ ⊑ ∀d.(xsd:string ⊔ xsd:integer)\n",
                "⊤ ⊑ ∀d.[xsd:string ⊓ [minLength 3]]\n"}) {
            OWLOntology o = read(PREFIX + range);
            assertEquals(1, o.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
                () -> range.trim() + " is a data property range: " + o.getLogicalAxioms());
            assertTrue(classes(o).isEmpty(),
                () -> "and no datatype became a class: " + classes(o));
        }
    }

    /**
     * A property the document states, equated to a class expression, is a DLe error.
     *
     * <p>The evidence settles the cases that arose from a guess; this is what a stated kind
     * can still build, and it must be reported as something the author can act on. Before,
     * the cast ran and the message named an OWL API implementation class.
     */
    @Test
    void aStatedPropertyEquatedToAClassExpressionIsReported() throws Exception {
        List<String> documents = List.of(
            PREFIX + "a ⊑ owl:topObjectProperty\na ≡ b ⊓ c\n",
            PREFIX + "a ⊑ owl:topDataProperty\na ≡ b ⊔ c\n");
        for (String document : documents) {
            Exception thrown = assertThrows(Exception.class, () -> read(document),
                () -> "this cannot be built: " + document);
            String message = String.valueOf(rootCause(thrown).getMessage());
            assertFalse(message.contains("cannot be cast"),
                () -> "the author must not be shown a cast failure: " + message);
            assertTrue(message.contains("class expression"),
                () -> "the message must say what is wrong: " + message);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }
}
