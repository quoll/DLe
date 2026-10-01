package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An entity may be named for a word the grammar keeps.
 *
 * <p>{@code Self} is the {@code ObjectHasSelf} filler, {@code true} and {@code false} are
 * the boolean literals, and {@code key} opens a key expression. None can be written bare, so
 * an entity named for one produced a document that would not reload — and in one case one
 * that reloaded meaning something else: a class {@code :Self} as a restriction filler was
 * written {@code A ⊑ ∃r.Self} and came back {@code ObjectHasSelf(:r)}, the class gone and
 * the axiom changed, with the document loading cleanly.
 *
 * <p>They are only reserved bare. Prefixed, they are ordinary names, so the writer gives the
 * namespace a second prefix and uses it for that name alone. The keywords themselves are
 * untouched — which is the point: {@code ∃r.Self} and {@code ∃r.ns1:Self} are now different
 * things, and a document can say both.
 */
class ReservedNameTest {

    private static final String NS = "http://example.org/w#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));

    private OWLOntology ontology(OWLAxiom... axioms) throws Exception {
        OWLOntology o = manager.createOntology();
        for (OWLAxiom axiom : axioms) manager.addAxiom(o, axiom);
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private String roundTrip(OWLOntology o) throws Exception {
        String written = write(o);
        String body = bodyOf(written);
        OWLOntology back = assertDoesNotThrow(() -> read(written),
            () -> "must reload:\n" + body);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "and mean the same:\n" + body);
        return body;
    }

    /** A class named for each reserved word survives, in every position. */
    @Test
    void aClassNamedForAReservedWordSurvives() throws Exception {
        for (String word : new String[] {"Self", "true", "false", "key"}) {
            OWLClass named = df.getOWLClass(IRI.create(NS + word));
            String body = roundTrip(ontology(
                df.getOWLSubClassOfAxiom(named, df.getOWLClass(IRI.create(NS + "C"))),
                df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(r, named))));
            assertTrue(body.contains(":" + word),
                () -> word + " must be written with a prefix:\n" + body);
        }
    }

    /** A property and an individual too, since the words are reserved everywhere. */
    @Test
    void aPropertyOrIndividualNamedForAReservedWordSurvives() throws Exception {
        for (String word : new String[] {"Self", "true", "key"}) {
            roundTrip(ontology(df.getOWLSubClassOfAxiom(a,
                df.getOWLObjectSomeValuesFrom(
                    df.getOWLObjectProperty(IRI.create(NS + word)),
                    df.getOWLClass(IRI.create(NS + "D"))))));
            roundTrip(ontology(df.getOWLClassAssertionAxiom(a,
                df.getOWLNamedIndividual(IRI.create(NS + word)))));
        }
    }

    /**
     * The keyword still means what it meant, and is now distinguishable from the name.
     *
     * <p>This is the case that used to be silent: both axioms below were written
     * {@code ∃r.Self}, so one of them was lost on every round trip.
     */
    @Test
    void theKeywordAndTheNameCanBothAppear() throws Exception {
        OWLClass self = df.getOWLClass(IRI.create(NS + "Self"));
        OWLOntology o = ontology(
            df.getOWLSubClassOfAxiom(a, df.getOWLObjectHasSelf(r)),
            df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "B")),
                df.getOWLObjectSomeValuesFrom(r, self)));

        String body = roundTrip(o);
        assertTrue(body.contains("∃r.Self"),
            () -> "the keyword stays bare:\n" + body);
        assertTrue(body.contains("∃r.ns1:Self"),
            () -> "and the class takes a prefix, so they differ:\n" + body);
    }

    /** ObjectHasSelf on its own is untouched: no prefix is minted for it. */
    @Test
    void theKeywordAloneMintsNothing() throws Exception {
        String body = roundTrip(ontology(df.getOWLSubClassOfAxiom(a,
            df.getOWLObjectHasSelf(r))));
        assertTrue(body.contains("A ⊑ ∃r.Self"), () -> body);
        assertFalse(body.contains("@prefix ns"),
            () -> "nothing here needs a second prefix:\n" + body);
    }

    /**
     * A document with no reserved name is written exactly as before.
     *
     * <p>The prefix is minted only when it is needed, and used only for the name that needs
     * it, so one awkwardly named entity does not reprefix every line of the document.
     */
    @Test
    void anOrdinaryDocumentIsUnaffected() throws Exception {
        String body = roundTrip(ontology(
            df.getOWLSubClassOfAxiom(a, df.getOWLClass(IRI.create(NS + "C"))),
            df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(r,
                df.getOWLClass(IRI.create(NS + "B"))))));
        assertTrue(body.contains("A ⊑ C"), () -> body);
        assertFalse(body.contains("ns1:"),
            () -> "no prefix should be minted:\n" + body);
    }

    /** And only the reserved name is prefixed, not everything beside it. */
    @Test
    void onlyTheReservedNameIsPrefixed() throws Exception {
        String body = roundTrip(ontology(
            df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "Self")),
                df.getOWLClass(IRI.create(NS + "C")))));
        assertTrue(body.contains("ns1:Self ⊑ C"),
            () -> "the neighbour keeps its bare name:\n" + body);
    }

    /** An existing prefix on the namespace is used rather than a new one invented. */
    @Test
    void anExistingPrefixIsReused() throws Exception {
        OWLOntology o = ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "Self")),
            df.getOWLClass(IRI.create(NS + "C"))));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        format.setPrefix("alt:", NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        String body = bodyOf(new String(out.toByteArray(), StandardCharsets.UTF_8));

        assertTrue(body.contains("alt:Self"),
            () -> "alt: already names this namespace:\n" + body);
        assertFalse(body.contains("ns1:"),
            () -> "so nothing needs inventing:\n" + body);
    }

    /** Bare, the words are still the keywords the grammar expects. */
    @Test
    void theWordsAreStillKeywords() throws Exception {
        assertEquals(1, read("@prefix : <" + NS + ">\nA ⊑ ∃r.B\n⊤ ⊑ ∃r.Self\n")
            .getAxioms(AxiomType.REFLEXIVE_OBJECT_PROPERTY).size(), "⊤ ⊑ ∃r.Self");
        assertEquals(1, read("@prefix : <" + NS + ">\n(a,true):d\n")
            .getAxioms(AxiomType.DATA_PROPERTY_ASSERTION).size(), "a boolean value");
        assertTrue(read("@prefix : <" + NS + ">\n⊤ ⊑ ∀id.xsd:string\nC ⊑ key(id)\n")
            .getAxioms(AxiomType.HAS_KEY).size() == 1, "a key expression");
    }
}
