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
 * Every literal the writer emits must be quoted, escaped and tagged.
 *
 * <p>There was no {@code visit(OWLLiteral)} override, so {@code accept(this)} on a literal
 * fell through to the inherited DL renderer's bare lexical form, and two other sites rolled
 * their own quoting. The consequences ranged from silent corruption to documents that would
 * not reload:
 *
 * <ul>
 * <li>{@code DataPropertyAssertion(:p :bob "Robert")} was written {@code (bob,Robert):p} and
 *     read back as an <em>object</em> property assertion with an invented individual
 *     {@code :Robert} — a data property silently became an object property.
 * <li>A value containing a space, a quote, or nothing at all produced a syntax error on
 *     re-read.
 * <li>{@code DataOneOf} dropped language tags and escaped nothing.
 * <li>A self-label carrying a tag was suppressed as redundant and regenerated untagged.
 * </ul>
 *
 * <p>The fixture data matters as much as the assertions here. The test that was supposed to
 * cover this used {@code getOWLLiteral(7)}, and integers and booleans are the only kinds
 * that rendered correctly, so it passed throughout.
 */
class LiteralRenderingTest {

    private static final String NS = "http://example.org/l#";

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

    /** An ontology asserting one data-property value, built through the API. */
    private OWLOntology withValue(OWLLiteral value) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(
            df.getOWLDataProperty(IRI.create(NS + "p")),
            df.getOWLNamedIndividual(IRI.create(NS + "bob")), value));
        return o;
    }

    /**
     * Every string-shaped value survives a round trip.
     *
     * <p>Each of these was either corrupted or unparseable. The spaced and quote-bearing
     * ones are the loudest — they produced a document the reader rejects — and the plain
     * one is the worst, because it produced a different ontology in silence.
     */
    @Test
    void everyStringValueRoundTrips() throws Exception {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLLiteral[] values = {
            df.getOWLLiteral("Robert"),
            df.getOWLLiteral("a b"),
            df.getOWLLiteral(""),
            df.getOWLLiteral("has\"quote"),
            df.getOWLLiteral("back\\slash"),
            df.getOWLLiteral("Bob", "en"),
            df.getOWLLiteral("Hanzi", "zh-Hant"),
            df.getOWLLiteral(7),
            df.getOWLLiteral(true),
        };
        for (OWLLiteral value : values) {
            OWLOntology o = withValue(value);
            String written = write(o);
            OWLOntology back = assertDoesNotThrow(() -> parse(written),
                () -> "must be readable for " + value + ":\n" + statementsOnly(written));
            assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
                () -> "must be unchanged for " + value + ":\n" + statementsOnly(written));
        }
    }

    /** The value keeps its quotes, so the reader still sees a data property. */
    @Test
    void aStringValueStaysADataProperty() throws Exception {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLOntology o = withValue(df.getOWLLiteral("Robert"));
        String body = statementsOnly(write(o));
        assertTrue(body.contains("(bob,\"Robert\"):p"),
            () -> "the value must be quoted:\n" + body);

        OWLOntology back = parse(write(o));
        assertTrue(back.containsDataPropertyInSignature(IRI.create(NS + "p")),
            () -> "p must come back a data property: " + back.getLogicalAxioms());
        assertFalse(back.containsObjectPropertyInSignature(IRI.create(NS + "p")),
            () -> "and not an object property: " + back.getLogicalAxioms());
        assertFalse(back.containsIndividualInSignature(IRI.create(NS + "Robert")),
            () -> "no individual may be invented from the string: "
                + back.getLogicalAxioms());
    }

    /** A negative assertion's value goes through the same path. */
    @Test
    void aNegativeAssertionValueIsAlsoQuoted() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLNegativeDataPropertyAssertionAxiom(
            df.getOWLDataProperty(IRI.create(NS + "p")),
            df.getOWLNamedIndividual(IRI.create(NS + "bob")),
            df.getOWLLiteral("hello", "en")));

        String written = write(o);
        assertTrue(statementsOnly(written).contains("¬(bob,\"hello\"@en):p"),
            () -> statementsOnly(written));
        assertEquals(o.getLogicalAxioms(), parse(written).getLogicalAxioms(),
            () -> statementsOnly(written));
    }

    /** An enumerated data range keeps its tags and escapes its quotes. */
    @Test
    void enumeratedLiteralsAreQuotedEscapedAndTagged() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLEquivalentClassesAxiom(
            df.getOWLClass(IRI.create(NS + "C")),
            df.getOWLDataSomeValuesFrom(df.getOWLDataProperty(IRI.create(NS + "d")),
                df.getOWLDataOneOf(df.getOWLLiteral("say \"hi\""),
                                   df.getOWLLiteral("rouge", "fr")))));

        String written = write(o);
        OWLOntology back = assertDoesNotThrow(() -> parse(written),
            () -> "an escaped quote must not break the document:\n" + statementsOnly(written));
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "the tag and the escape must both survive:\n" + statementsOnly(written));
    }

    /**
     * A self-label with a language tag is not suppressed.
     *
     * <p>The suppression exists because {@code DefaultLabelAdder} regenerates a label equal
     * to the entity's local name. It regenerates an <em>untagged</em> one, so dropping a
     * tagged one replaced the literal with a different literal.
     */
    @Test
    void aTaggedSelfLabelSurvives() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI c = IRI.create(NS + "C");
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c,
            df.getOWLLiteral("C", "en")));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(c),
            df.getOWLClass(IRI.create(NS + "D"))));

        String written = write(o);
        assertTrue(statementsOnly(written).contains("\"C\"@en"),
            () -> "the tagged label must be written:\n" + statementsOnly(written));
        assertTrue(parse(written).axioms(AxiomType.ANNOTATION_ASSERTION)
                .anyMatch(ax -> ax.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) ax.getValue()).hasLang()),
            () -> "and must come back tagged:\n" + statementsOnly(written));
    }

    /** The untagged self-label is still suppressed, since the reader puts it back. */
    @Test
    void anUntaggedSelfLabelIsStillSuppressed() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI c = IRI.create(NS + "C");
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c,
            df.getOWLLiteral("C")));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(c),
            df.getOWLClass(IRI.create(NS + "D"))));

        String written = write(o);
        assertFalse(statementsOnly(written).contains("@label C"),
            () -> "a redundant label is not written:\n" + statementsOnly(written));
        // Only C's own label: the reader also invents one for D, which has none, and that
        // is long-standing behaviour unrelated to suppression.
        assertEquals(o.getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
                .filter(ax -> c.equals(ax.getSubject())).collect(java.util.stream.Collectors.toSet()),
            parse(written).getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
                .filter(ax -> c.equals(ax.getSubject())).collect(java.util.stream.Collectors.toSet()),
            "and the reader regenerates it exactly");
    }
}
