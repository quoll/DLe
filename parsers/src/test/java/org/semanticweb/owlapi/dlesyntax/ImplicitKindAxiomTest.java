package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `X ⊑ owl:topObjectProperty` and `X ⊑ owl:topDataProperty` are implicit, and removed.
 *
 * <p>DL cannot say what kind of thing a name is, so DLe says it with the one statement that
 * is always true of a property: that it lies beneath the top property of its kind. Every OWL
 * model holds that implicitly, which is why no real document writes it — Turtle never does,
 * and functional syntax says the same thing with {@code Declaration(...)}.
 *
 * <p>So the statement is read as the axiom it is, and removed once parsing is complete,
 * leaving the declaration it meant. It is a declaration and not a deletion: removing the
 * axiom alone would take the name out of the signature whenever the statement was its only
 * mention, which is exactly how a declaration-only property arrives.
 *
 * <p>{@code X ⊑ ⊤} is deliberately left alone. It is a tautology too, but
 * {@code ClassName ⊑ ⊤} is how a class is declared in DL and appears throughout real
 * documents.
 *
 * <p>The cost, accepted knowingly: a document stating one of these two axioms on purpose
 * loses it. They are tautologies, so nothing that was entailed stops being entailed, and OWL
 * re-derives them. See #32 — specified rather than fixed.
 */
class ImplicitKindAxiomTest {

    private static final String NS = "http://example.org/i#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
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

    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** The statement is gone, and the declaration is there in its place. */
    @Test
    void theStatementBecomesADeclaration() throws Exception {
        OWLOntology o = read(PREFIX
            + "Upper ⊑ owl:topObjectProperty\nScore ⊑ owl:topDataProperty\nA ⊑ B\n");

        assertEquals(0, o.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).size(),
            () -> "the object statement is implicit: " + o.getLogicalAxioms());
        assertEquals(0, o.getAxioms(AxiomType.SUB_DATA_PROPERTY).size(),
            () -> "and so is the data one: " + o.getLogicalAxioms());

        assertTrue(o.containsObjectPropertyInSignature(IRI.create(NS + "Upper")),
            () -> "Upper must survive as an object property: " + o.getAxioms());
        assertTrue(o.containsDataPropertyInSignature(IRI.create(NS + "Score")),
            () -> "Score must survive as a data property: " + o.getAxioms());
    }

    /**
     * A name whose only statement is the marker still exists afterwards.
     *
     * <p>This is why the pass adds a declaration rather than only removing the axiom: the
     * axiom was the name's only mention, so removing it alone would have taken the name out
     * of the signature entirely.
     */
    @Test
    void aNameWithNoOtherStatementSurvives() throws Exception {
        OWLOntology o = read(PREFIX + "Upper ⊑ owl:topObjectProperty\n");
        assertTrue(o.containsObjectPropertyInSignature(IRI.create(NS + "Upper")),
            () -> "the declaration is what keeps it: " + o.getAxioms());
        assertEquals(0, o.getLogicalAxioms().size(),
            () -> "and it leaves no logical axiom behind: " + o.getLogicalAxioms());
    }

    /**
     * A declaration-only property round-trips, twice.
     *
     * <p>The loop only closes because the writer re-emits the marker for a property it has
     * no other way to mention. Two passes rather than one, because a single pass would not
     * show the marker being regenerated from the declaration the previous pass left.
     */
    @Test
    void aDeclarationOnlyPropertyRoundTripsTwice() throws Exception {
        OWLOntology o = manager.createOntology();
        OWLObjectProperty upper = df.getOWLObjectProperty(IRI.create(NS + "Upper"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(upper));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")), df.getOWLClass(IRI.create(NS + "B"))));

        String first = write(o);
        assertTrue(bodyOf(first).contains("Upper ⊑ owl:topObjectProperty"),
            () -> "the kind has to be stated, or the name cannot be written:\n" + first);

        OWLOntology back = read(first);
        assertTrue(back.containsObjectPropertyInSignature(upper.getIRI()),
            () -> "still an object property: " + back.getAxioms());

        String second = write(back);
        assertEquals(bodyOf(first), bodyOf(second),
            () -> "and the second pass must reproduce the first:\n" + first + "\n---\n"
                + second);
    }

    /** The direction matters: top as the sub-property is not a tautology. */
    @Test
    void topAsTheSubPropertyIsNotRemoved() throws Exception {
        OWLOntology o = read(PREFIX + "owl:topObjectProperty ⊑ r\nA ⊑ ∃r.B\n");
        assertEquals(1, o.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).size(),
            () -> "`owl:topObjectProperty ⊑ r` says something and must survive: "
                + o.getLogicalAxioms());
    }

    /**
     * {@code X ⊑ ⊤} is untouched, being how a class is declared in DL.
     *
     * <p>It is as much a tautology as the two property forms, and is deliberately treated
     * differently: real documents use it to introduce a class, so removing it would discard
     * statements people write on purpose.
     */
    @Test
    void theClassFormIsUntouched() throws Exception {
        OWLOntology o = read(PREFIX + "ClassName ⊑ ⊤\nA ⊑ B\n");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "ClassName")), df.getOWLThing())),
            () -> "`ClassName ⊑ ⊤` is an ordinary axiom: " + o.getLogicalAxioms());
    }

    /**
     * OWL's built-in properties are properties, which nothing else here would work out.
     *
     * <p>The vocabulary rule covers {@code rdf:} and {@code rdfs:} but not {@code owl:}, so
     * {@code owl:topObjectProperty} fell through to a class — which is how
     * {@code X ⊑ owl:topObjectProperty} came to read as a class subsumption, with
     * {@code Declaration(Class(owl:topObjectProperty))} beside it.
     */
    @Test
    void theOwlBuiltInPropertiesAreProperties() throws Exception {
        OWLOntology o = read(PREFIX + "owl:topObjectProperty ⊑ r\nA ⊑ ∃r.B\n");
        assertFalse(o.containsClassInSignature(
                OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI()),
            () -> "owl:topObjectProperty is not a class: " + o.getAxioms());
        assertTrue(o.containsObjectPropertyInSignature(
                OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI()),
            () -> "it is an object property: " + o.getAxioms());
    }

    /**
     * Both markers on one IRI is a conflict, and is reported.
     *
     * <p>It would otherwise establish the object/data pun (#43) in silence — two
     * declarations of one IRI, which OWL 2 DL forbids and no reasoner will load.
     */
    @Test
    void bothMarkersOnOneNameAreReported() {
        Throwable t = assertThrows(Throwable.class,
            () -> read(PREFIX + "P ⊑ owl:topObjectProperty\nP ⊑ owl:topDataProperty\n"),
            "one IRI cannot be both kinds of property");
        String message = String.valueOf(t.getMessage());
        assertTrue(message.contains("object property") && message.contains("data property"),
            () -> "the message must name both: " + message);
    }

    /** The evidence still reaches the reader, since the scan sees the statement first. */
    @Test
    void theKindIsStillKnownAfterRemoval() throws Exception {
        // Upper breaks the case convention, so only the statement can settle its kind.
        OWLOntology o = read(PREFIX + "Upper ⊑ owl:topObjectProperty\nA ⊑ ∃Upper.B\n");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "A")),
                df.getOWLObjectSomeValuesFrom(
                    df.getOWLObjectProperty(IRI.create(NS + "Upper")),
                    df.getOWLClass(IRI.create(NS + "B"))))),
            () -> "Upper is an object property in the restriction too: "
                + o.getLogicalAxioms());
    }
}
