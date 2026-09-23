package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Assertions about individuals, in the spelling used by <em>Introduction to Description
 * Logic</em>: {@code a:C}, {@code (a,b):r}, {@code ¬(a,b):r} and the data forms.
 *
 * <p>Before this, the writer emitted the inherited {@code C(a)} and {@code r(a,b)}, which
 * the grammar cannot read at all — {@code Animal(bob)} is the head of a predicate
 * definition and fails asking for {@code ≝}. So any ontology containing an individual saved
 * successfully and then could not be loaded. Negative assertions were worse: the inherited
 * renderer wrote {@code ¬} twice, and two signs for one negation reads as none.
 *
 * <p>The awkward part is that {@code a:C} is character-for-character a prefixed name. The
 * lexer cannot tell them apart, so the reader decides afterwards, from the prefixes the
 * document declares — which is sound because the grammar puts every {@code @prefix} before
 * every statement.
 */
class AssertionSyntaxTest {

    private static final String NS = "http://example.org/a#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            manager.getOntologyLoaderConfiguration());
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private String refusal(String document) {
        Throwable t = assertThrows(Throwable.class, () -> parse(document),
            () -> "expected a refusal for:\n" + document);
        return String.valueOf(t.getMessage());
    }

    private IRI iri(String local) {
        return IRI.create(NS + local);
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @Test
    void everyAssertionFormIsRead() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLNamedIndividual bob = df.getOWLNamedIndividual(iri("bob"));
        OWLNamedIndividual rex = df.getOWLNamedIndividual(iri("rex"));
        OWLObjectProperty hasPet = df.getOWLObjectProperty(iri("hasPet"));
        OWLDataProperty age = df.getOWLDataProperty(iri("age"));

        OWLOntology o = parse(PREFIX
            + "bob : Animal\n"
            + "(bob,rex):hasPet\n"
            + "¬(rex,bob):hasPet\n"
            + "(bob,7):age\n"
            + "¬(rex,3):age\n");

        Set<OWLLogicalAxiom> axioms = o.getLogicalAxioms();
        assertTrue(axioms.contains(df.getOWLClassAssertionAxiom(
            df.getOWLClass(iri("Animal")), bob)), () -> "a:C — " + axioms);
        assertTrue(axioms.contains(
            df.getOWLObjectPropertyAssertionAxiom(hasPet, bob, rex)), () -> "(a,b):r — " + axioms);
        assertTrue(axioms.contains(
            df.getOWLNegativeObjectPropertyAssertionAxiom(hasPet, rex, bob)),
            () -> "¬(a,b):r — " + axioms);
        assertTrue(axioms.contains(
            df.getOWLDataPropertyAssertionAxiom(age, bob, df.getOWLLiteral(7))),
            () -> "(a,v):d — " + axioms);
        assertTrue(axioms.contains(
            df.getOWLNegativeDataPropertyAssertionAxiom(age, rex, df.getOWLLiteral(3))),
            () -> "¬(a,v):d — " + axioms);
    }

    /**
     * A negative assertion stays negative.
     *
     * <p>The inherited renderer wrote {@code ¬} twice for one negation, which the grammar
     * rejects — fortunately, because a double negative reads as the positive, so anything
     * that accepted it would have asserted what the document denied.
     */
    @Test
    void aNegativeAssertionIsNotInverted() throws Exception {
        OWLOntology o = parse(PREFIX + "¬(rex,bob):hasPet\n");
        assertEquals(1, o.getAxioms(AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION).size(),
            () -> "must be negative: " + o.getLogicalAxioms());
        assertEquals(0, o.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION).size(),
            () -> "and not positive: " + o.getLogicalAxioms());
        String written = write(o);
        assertTrue(statementsOnly(written).contains("¬(rex,bob):hasPet"),
            () -> "with exactly one ¬:\n" + written);
    }

    /** The textbook allows a concept expression on the right, not just a name. */
    @Test
    void theClassSideMayBeAnExpression() throws Exception {
        OWLOntology o = parse(PREFIX + "bob : Cat ⊓ Pet\nrex : ¬Animal\nbob : ∃hasPet.Cat\n");
        assertEquals(3, o.getAxioms(AxiomType.CLASS_ASSERTION).size(),
            () -> o.getLogicalAxioms().toString());
        assertTrue(o.getAxioms(AxiomType.CLASS_ASSERTION).stream()
                .anyMatch(ax -> ax.getClassExpression() instanceof OWLObjectIntersectionOf),
            () -> o.getLogicalAxioms().toString());
    }

    /**
     * A digit-initial property needs two colons, and that is not the same as one.
     *
     * <p>The property is spelled {@code :116676008} — the colon is part of the name — and
     * the assertion contributes a separator of its own, so the statement carries both.
     * {@code (a,b):116676008} is a different thing: bare, {@code 116676008} lexes as a
     * number, and a number cannot be a property, so it is refused.
     */
    @Test
    void aDigitInitialPropertyTakesTwoColons() throws Exception {
        OWLOntology o = parse(PREFIX + "(bob,rex)::116676008\n");
        assertTrue(o.containsObjectPropertyInSignature(iri("116676008")),
            () -> o.getLogicalAxioms().toString());

        assertThrows(Throwable.class, () -> parse(PREFIX + "(bob,rex):116676008\n"),
            "a bare number cannot be a property");
    }

    // ── Which reading of a:C ────────────────────────────────────────────────

    /**
     * {@code a:C} is an assertion when {@code a:} is not a declared prefix.
     *
     * <p>It arrives as a single token, indistinguishable from a prefixed name. A lone
     * prefixed name has never been a legal statement, which is what leaves room for this
     * reading — and if the prefix <em>is</em> declared, the statement is refused rather
     * than guessed at.
     */
    @Test
    void theCompactFormIsAnAssertionUnlessThePrefixIsDeclared() throws Exception {
        OWLOntology o = parse(PREFIX + "bob:Animal\n");
        assertTrue(o.getAxioms(AxiomType.CLASS_ASSERTION).stream()
                .anyMatch(ax -> ax.getIndividual().asOWLNamedIndividual().getIRI()
                    .equals(iri("bob"))),
            () -> o.getLogicalAxioms().toString());

        String message = refusal("@prefix bob: <http://example.org/b#>\n" + PREFIX + "bob:Animal\n");
        assertTrue(message.contains("is not a statement"), () -> "got: " + message);
    }

    /**
     * Where both splits are possible, the declared prefixes decide; where both are
     * declared, nothing can decide and it is refused.
     */
    @Test
    void theDeclaredPrefixesChooseTheSplit() throws Exception {
        String ex = "@prefix ex: <http://example.org/e#>\n";
        String a = "@prefix a: <http://example.org/A#>\n";

        OWLOntology onlyEx = parse(ex + PREFIX + "ex:a:C\n");
        assertTrue(onlyEx.getAxioms(AxiomType.CLASS_ASSERTION).stream()
                .anyMatch(x -> x.getIndividual().asOWLNamedIndividual().getIRI()
                    .equals(IRI.create("http://example.org/e#a"))),
            () -> "with only ex: declared the individual is ex:a — " + onlyEx.getLogicalAxioms());

        OWLOntology onlyA = parse(a + PREFIX + "ex:a:C\n");
        assertTrue(onlyA.getAxioms(AxiomType.CLASS_ASSERTION).stream()
                .anyMatch(x -> x.getClassExpression().asOWLClass().getIRI()
                    .equals(IRI.create("http://example.org/A#C"))),
            () -> "with only a: declared the class is a:C — " + onlyA.getLogicalAxioms());

        String message = refusal(ex + a + PREFIX + "ex:a:C\n");
        assertTrue(message.contains("ambiguous"), () -> "got: " + message);
        assertTrue(message.contains("space"), () -> "and it must say what to do: " + message);
    }

    /** A space says which split was meant, and overrides the prefix lookup. */
    @Test
    void aSpaceOverridesTheLookup() throws Exception {
        String both = "@prefix ex: <http://example.org/e#>\n"
            + "@prefix a: <http://example.org/A#>\n" + PREFIX;
        OWLOntology o = parse(both + "ex:a : C\n");
        assertTrue(o.getAxioms(AxiomType.CLASS_ASSERTION).stream()
                .anyMatch(x -> x.getIndividual().asOWLNamedIndividual().getIRI()
                    .equals(IRI.create("http://example.org/e#a"))),
            () -> o.getLogicalAxioms().toString());
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Every form round-trips, and writing is idempotent from the first pass.
     *
     * <p>Built through the API rather than parsed, so the writer is what is under test:
     * this is the path that used to produce a document the reader could not read.
     */
    @Test
    void everyAssertionFormRoundTrips() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLNamedIndividual bob = df.getOWLNamedIndividual(iri("bob"));
        OWLNamedIndividual rex = df.getOWLNamedIndividual(iri("rex"));
        OWLObjectProperty hasPet = df.getOWLObjectProperty(iri("hasPet"));
        OWLDataProperty age = df.getOWLDataProperty(iri("age"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(df.getOWLClass(iri("Animal")), bob));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(
            df.getOWLObjectComplementOf(df.getOWLClass(iri("Animal"))), rex));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(hasPet, bob, rex));
        m.addAxiom(o, df.getOWLNegativeObjectPropertyAssertionAxiom(hasPet, rex, bob));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(age, bob, df.getOWLLiteral(7)));
        m.addAxiom(o, df.getOWLNegativeDataPropertyAssertionAxiom(age, rex, df.getOWLLiteral(3)));

        String written = write(o);
        OWLOntology back = assertDoesNotThrow(() -> parse(written),
            () -> "the writer must not produce something it cannot read:\n" + written);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "and nothing may change:\n" + written);
        assertEquals(statementsOnly(written), statementsOnly(write(back)),
            () -> "writing must be idempotent from the first pass:\n" + written);
    }

    /**
     * The class assertion is always written with the space.
     *
     * <p>{@code a:C} would depend on the individual's name not being a declared prefix, and
     * the writer has no business producing output whose meaning turns on that.
     */
    @Test
    void theWriterAlwaysSpacesTheClassAssertion() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLClassAssertionAxiom(df.getOWLClass(iri("Animal")),
            df.getOWLNamedIndividual(iri("bob"))));

        String body = statementsOnly(write(o));
        assertTrue(body.contains("bob : Animal"), () -> body);
        assertFalse(body.contains("bob:Animal"), () -> "never the ambiguous spelling:\n" + body);
    }

    // ── Identity and distinctness ───────────────────────────────────────────

    /**
     * {@code a = b} and {@code a ≠ b}, both n-ary.
     *
     * <p>`=` reuses the token that exists for the cardinality operator in {@code =n r.C},
     * because two lexer rules matching `=` would be a conflict and the first would silently
     * win. There is no parser ambiguity: a cardinality begins with the operator and this
     * begins with a name. `≠` needed a token of its own — nothing used U+2260 before.
     */
    @Test
    void identityAndDistinctnessAreRead() throws Exception {
        OWLOntology same = parse(PREFIX + "bob = rex\n");
        assertEquals(1, same.getAxioms(AxiomType.SAME_INDIVIDUAL).size(),
            () -> same.getLogicalAxioms().toString());

        OWLOntology different = parse(PREFIX + "bob ≠ rex\n");
        assertEquals(1, different.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS).size(),
            () -> different.getLogicalAxioms().toString());
    }

    /** Both are n-ary in OWL, so the operator chains. */
    @Test
    void theOperatorsChain() throws Exception {
        OWLOntology o = parse(PREFIX + "bob = rex = fido\nzed ≠ ada ≠ eve\n");
        assertEquals(3, o.getAxioms(AxiomType.SAME_INDIVIDUAL).iterator().next()
            .getIndividuals().size(), () -> o.getLogicalAxioms().toString());
        assertEquals(3, o.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS).iterator().next()
            .getIndividuals().size(), () -> o.getLogicalAxioms().toString());
    }

    /**
     * One individual named twice is refused, and the two statements fail differently.
     *
     * <p>OWL API requires at least two members, so a collapsed set has to be caught rather
     * than handed over. {@code a = a} states nothing; {@code a ≠ a} states something no
     * interpretation can satisfy, and saying "the same as itself" for that would be wrong.
     */
    @Test
    void oneIndividualNamedTwiceIsRefusedAndDescribedCorrectly() {
        assertTrue(refusal(PREFIX + "bob = bob\n").contains("same as itself"),
            () -> refusal(PREFIX + "bob = bob\n"));
        String distinct = refusal(PREFIX + "bob ≠ bob\n");
        assertTrue(distinct.contains("distinct from itself"), () -> distinct);
        assertFalse(distinct.contains("same as itself"),
            () -> "a contradiction is not a tautology: " + distinct);
    }

    /** Both forms round-trip, including the chained spelling. */
    @Test
    void identityRoundTrips() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLSameIndividualAxiom(
            df.getOWLNamedIndividual(iri("bob")), df.getOWLNamedIndividual(iri("rex")),
            df.getOWLNamedIndividual(iri("fido"))));
        m.addAxiom(o, df.getOWLDifferentIndividualsAxiom(
            df.getOWLNamedIndividual(iri("zed")), df.getOWLNamedIndividual(iri("ada"))));

        String written = write(o);
        OWLOntology back = assertDoesNotThrow(() -> parse(written),
            () -> "the writer must not produce something it cannot read:\n" + written);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "and nothing may change:\n" + written);
        assertEquals(statementsOnly(written), statementsOnly(write(back)),
            () -> "idempotent from the first pass:\n" + written);
    }

    /**
     * The cardinality operator still works, sharing the `=` token.
     *
     * <p>Both spellings are exact cardinalities. The bare one abbreviates `⊤ ⊑ …` — it used
     * to be read as functionality, which loses the `≥1` half of an exactly-one.
     */
    @Test
    void theCardinalityOperatorIsUnaffected() throws Exception {
        OWLOntology o = parse(PREFIX + "A ⊑ =2 hasPet.Cat\n=1 hasPet.⊤\n");
        assertEquals(2, o.getLogicalAxioms().stream()
                .filter(ax -> ax.toString().contains("Exact")).count(),
            () -> o.getLogicalAxioms().toString());
        assertTrue(o.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).isEmpty(),
            () -> "an exactly-one is not a functionality axiom: " + o.getLogicalAxioms());
    }

    // ── Validation the new forms used to route around ───────────────────────

    /**
     * The separator must be a bare colon.
     *
     * <p>The grammar spells it {@code PNAME_NS}, which is {@code NameChar* ':'} and so also
     * matches {@code foo:}. Nothing checked the label was empty, so every form accepted one
     * and silently discarded it.
     */
    @Test
    void anythingOtherThanABareColonIsRefused() {
        for (String statement : new String[] {
                "a foo: C", "(a,b) foo: r", "(a,7) zz: d", "¬(a,b) zz: r"}) {
            String message = refusal(PREFIX + statement + "\n");
            assertTrue(message.contains("is not the separator of an assertion"),
                () -> statement + " — got: " + message);
        }
    }

    /**
     * A datatype cannot be the class of an assertion, in any spelling.
     *
     * <p>The spaced form refused this through the ordinary class coercion; the compact and
     * re-split paths built the class directly and so accepted it — and then wrote the spaced
     * spelling back out, producing a document this very reader rejects.
     */
    @Test
    void anIndividualCannotBeAssertedToBeADatatype() {
        for (String statement : new String[] {"bob:xsd:string", "bob : xsd:string"}) {
            String message = refusal(PREFIX + statement + "\n");
            assertTrue(message.contains("datatype"),
                () -> statement + " — got: " + message);
        }
    }

    /**
     * The class IRI comes from the class side's name, not its raw text.
     *
     * <p>The reading-B gate uses {@code loneName}, which sees through parentheses, but the
     * IRI was built from {@code getText()} — so {@code ex:a:(C)} minted an IRI with a
     * parenthesis in it.
     */
    @Test
    void parenthesesAroundTheClassDoNotReachTheIri() throws Exception {
        OWLOntology o = parse("@prefix a: <http://example.org/A#>\n" + PREFIX + "ex:a:(C)\n");
        assertTrue(o.containsClassInSignature(IRI.create("http://example.org/A#C")),
            () -> "the class is a:C — " + o.getLogicalAxioms());
        assertTrue(o.getAxioms(AxiomType.DECLARATION).stream()
                .noneMatch(ax -> ax.getEntity().getIRI().toString().contains("(")),
            () -> "and no IRI may contain a parenthesis: " + o.getAxioms(AxiomType.DECLARATION));
    }

    /**
     * A repeated name in a distinctness chain is refused.
     *
     * <p>{@code a ≠ b ≠ a} contains the unsatisfiable pair {@code a ≠ a}, and the set
     * silently collapsed to {@code DifferentIndividuals(:a :b)} — dropping exactly that.
     * {@code =} is idempotent, so a repeat there is harmless and stays allowed.
     */
    @Test
    void aRepeatedNameInADistinctnessChainIsRefused() throws Exception {
        String message = refusal(PREFIX + "a ≠ b ≠ a\n");
        assertTrue(message.contains("named twice"), () -> message);
        assertTrue(message.contains("distinct from itself"), () -> message);

        OWLOntology same = parse(PREFIX + "a = b = a\n");
        assertEquals(1, same.getAxioms(AxiomType.SAME_INDIVIDUAL).size(),
            () -> "identity is idempotent, so this is fine: " + same.getLogicalAxioms());
    }

    /**
     * A repeated property in a disjointness statement is refused.
     *
     * <p>{@code Disj(p, p)} built a unary axiom — a profile violation — which the writer
     * then emitted as {@code Disj(p)}, a form the grammar does not accept.
     */
    @Test
    void aRepeatedPropertyInDisjointnessIsRefused() {
        String message = refusal(PREFIX + "A ⊑ ∃p.B\nDisj(p, p)\n");
        assertTrue(message.contains("named twice"), () -> message);
    }
}
