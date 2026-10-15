package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Assertions and keys written as predicates, which is what a language model produces.
 *
 * <p>{@code C(a)}, {@code r(a,b)} and {@code d(a,"x")} are how first-order logic says what DLe
 * says with {@code a : C} and {@code (a,b) : r}, and {@code HasKey(C () (d))} is OWL's own
 * functional syntax for what DLe writes {@code C ⊑ key(d)}. Models reach for all four
 * unprompted and consistently, so they are accepted on input. The writer is unchanged, so a
 * document written this way normalises to the DL spelling on its first round trip.
 *
 * <p>The forms are accepted last in the axiom rule, which is what makes
 * {@code Transitive(parent)} a transitivity declaration rather than an assertion that
 * {@code parent} is an instance of a class named {@code Transitive} — both readings match,
 * and ANTLR takes the first alternative. That is a decision, not an accident, so it is pinned
 * here: a reordering of the grammar would otherwise change the meaning of existing documents
 * in silence.
 */
class PredicateFormTest {

    private static final String NS = "http://example.org/p#";
    private static final String MY = "http://my.org/";
    private static final String PREFIXES =
        "@prefix : <" + NS + ">\n@prefix myns: <" + MY + ">\n";

    private final OWLDataFactory df = OWLManager.getOWLDataFactory();

    private OWLOntology read(String body) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(PREFIXES + body), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLClass cls(String n)      { return df.getOWLClass(IRI.create(NS + n)); }
    private OWLNamedIndividual ind(String n) { return df.getOWLNamedIndividual(IRI.create(NS + n)); }
    private OWLObjectProperty op(String n)   { return df.getOWLObjectProperty(IRI.create(NS + n)); }
    private OWLDataProperty dp(String n)     { return df.getOWLDataProperty(IRI.create(NS + n)); }

    // ---- the three assertion forms ----

    @Test
    void aUnaryPredicateIsAClassAssertion() throws Exception {
        assertTrue(read("Dog(rex)").containsAxiom(
            df.getOWLClassAssertionAxiom(cls("Dog"), ind("rex"))));
    }

    @Test
    void aBinaryPredicateWithTwoNamesIsAnObjectPropertyAssertion() throws Exception {
        assertTrue(read("likes(alice, bob)").containsAxiom(
            df.getOWLObjectPropertyAssertionAxiom(op("likes"), ind("alice"), ind("bob"))));
    }

    @Test
    void aBinaryPredicateWithALiteralIsADataPropertyAssertion() throws Exception {
        assertTrue(read("age(alice, \"42\")").containsAxiom(
            df.getOWLDataPropertyAssertionAxiom(dp("age"), ind("alice"), df.getOWLLiteral("42"))));
    }

    /** The predicate form says exactly what the DL form says, so both must land on one axiom. */
    @Test
    void thePredicateFormAndTheDlFormAgree() throws Exception {
        assertEquals(
            read("Dog(rex)").getAxioms(AxiomType.CLASS_ASSERTION),
            read("rex : Dog").getAxioms(AxiomType.CLASS_ASSERTION));
        assertEquals(
            read("likes(alice, bob)").getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION),
            read("(alice,bob) : likes").getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION));
    }

    // ---- HasKey in functional syntax ----

    @Test
    void aFunctionalSyntaxKeyWithOnlyDataPropertiesIsRead() throws Exception {
        assertTrue(read("HasKey(ComponentType () (id))").containsAxiom(
            df.getOWLHasKeyAxiom(cls("ComponentType"), dp("id"))));
    }

    @Test
    void aFunctionalSyntaxKeyWithOnlyObjectPropertiesIsRead() throws Exception {
        assertTrue(read("HasKey(Part (hasSerial) ())").containsAxiom(
            df.getOWLHasKeyAxiom(cls("Part"), op("hasSerial"))));
    }

    /**
     * Both groups at once. This is the case that proves the split is read from the
     * parentheses rather than guessed: nothing else in the document says which kind either
     * property is, and the two must still land on opposite sides.
     */
    @Test
    void bothKeyGroupsAreKeptApart() throws Exception {
        OWLOntology o = read("HasKey(C (hasPart) (serial))");
        assertTrue(o.containsAxiom(
            df.getOWLHasKeyAxiom(cls("C"), op("hasPart"), dp("serial"))));
        assertTrue(o.containsEntityInSignature(op("hasPart")), "hasPart is an object property");
        assertTrue(o.containsEntityInSignature(dp("serial")), "serial is a data property");
    }

    @Test
    void theFunctionalSyntaxKeyAndTheDlKeyAgree() throws Exception {
        assertEquals(
            read("HasKey(ComponentType () (id))").getAxioms(AxiomType.HAS_KEY),
            read("ComponentType ⊑ key(id)\nid ⊑ owl:topDataProperty").getAxioms(AxiomType.HAS_KEY));
    }

    // ---- the keyword rule ----

    /**
     * Every role-characteristic keyword, in both spellings, beats the assertion reading.
     * {@code name} includes these keywords, so {@code Transitive(parent)} matches the generic
     * {@code name '(' name ')'} alternative just as well as the TRANS one; the keyword wins
     * because its alternative is listed first. Reordering the grammar would turn every one of
     * these into a class assertion without any error being raised.
     */
    @Test
    void aRoleCharacteristicKeywordIsNeverAnAssertion() throws Exception {
        for (String word : Arrays.asList("Transitive", "Trans")) {
            assertTrue(read(word + "(parent)").containsAxiom(
                df.getOWLTransitiveObjectPropertyAxiom(op("parent"))), word);
        }
        for (String word : Arrays.asList("Symmetric", "Sym")) {
            assertTrue(read(word + "(knows)").containsAxiom(
                df.getOWLSymmetricObjectPropertyAxiom(op("knows"))), word);
        }
        for (String word : Arrays.asList("Asymmetric", "Asym")) {
            assertTrue(read(word + "(taller)").containsAxiom(
                df.getOWLAsymmetricObjectPropertyAxiom(op("taller"))), word);
        }
        for (String word : Arrays.asList("Reflexive", "Ref")) {
            assertTrue(read(word + "(sameAs)").containsAxiom(
                df.getOWLReflexiveObjectPropertyAxiom(op("sameAs"))), word);
        }
        for (String word : Arrays.asList("Irreflexive", "Irref")) {
            assertTrue(read(word + "(parentOf)").containsAxiom(
                df.getOWLIrreflexiveObjectPropertyAxiom(op("parentOf"))), word);
        }
        for (String word : Arrays.asList("Functional", "Func")) {
            assertTrue(read(word + "(hasMother)").containsAxiom(
                df.getOWLFunctionalObjectPropertyAxiom(op("hasMother"))), word);
        }
    }

    /** No keyword reading produces a class assertion as a side effect. */
    @Test
    void aKeywordFormAssertsNothingAboutAnyClass() throws Exception {
        assertTrue(read("Transitive(parent)").getAxioms(AxiomType.CLASS_ASSERTION).isEmpty());
    }

    /** {@code Disj} takes two properties, so it shadows the binary form as well. */
    @Test
    void theDisjointKeywordBeatsTheBinaryForm() throws Exception {
        OWLOntology o = read("Disj(a, b)");
        assertTrue(o.containsAxiom(df.getOWLDisjointObjectPropertiesAxiom(op("a"), op("b"))));
        assertTrue(o.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION).isEmpty());
    }

    /**
     * The way out, for anyone who genuinely means a class called Transitive. The bare word is
     * reserved; a namespaced name is not a keyword and reads as the assertion.
     */
    @Test
    void aNamespacedKeywordIsAnOrdinaryClass() throws Exception {
        assertTrue(read("myns:Transitive(parent)").containsAxiom(
            df.getOWLClassAssertionAxiom(
                df.getOWLClass(IRI.create(MY + "Transitive")), ind("parent"))));
    }

    /** {@code HasKey} is reserved outright, unlike the role words, so it cannot be an entity. */
    @Test
    void hasKeyIsReserved() {
        assertThrows(Exception.class, () -> read("HasKey ⊑ Thing"));
    }

    // ---- what the new forms must not disturb ----

    /**
     * A predicate definition is the same shape followed by {@code ≝}, and it still wins.
     * This one is settled by the trailing token rather than by alternative order, so it is
     * the case that would break if the lookahead were ever narrowed.
     */
    @Test
    void aPredicateDefinitionIsStillADefinition() throws Exception {
        OWLOntology o = read("Foo(x, y) ≝ x is related to y");
        assertTrue(o.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION).isEmpty(),
            "a definition is not an assertion");
        assertTrue(write(o).contains("≝"), "the definition survives the round trip");
    }

    /**
     * The case this arrived in: a class that is also an instance of another class. Both kinds
     * have to survive on the one name.
     */
    @Test
    void aPunnedNameKeepsBothKinds() throws Exception {
        OWLOntology o = read("Person ⊑ PersonComponent\nComponentType(Person)\n");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(cls("Person"), cls("PersonComponent"))));
        assertTrue(o.containsAxiom(
            df.getOWLClassAssertionAxiom(cls("ComponentType"), ind("Person"))));
        assertTrue(o.containsEntityInSignature(cls("Person")), "still a class");
        assertTrue(o.containsEntityInSignature(ind("Person")), "and an individual");
    }

    /** Input-only: what comes back out is the DL spelling, not the one that went in. */
    @Test
    void thePredicateFormsNormaliseOnOutput() throws Exception {
        String written = write(read(
            "Dog(rex)\nlikes(alice, bob)\nage(alice, \"42\")\nHasKey(C () (id))\n"));
        assertTrue(written.contains("rex : Dog"), written);
        assertTrue(written.contains("(alice,bob):likes"), written);
        assertTrue(written.contains("C ⊑ key(id)"), written);
        assertFalse(written.contains("Dog(rex)"), "the predicate form is not written back");
        assertFalse(written.contains("HasKey("), "the functional form is not written back");
    }
}
