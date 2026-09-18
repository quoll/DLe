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
 * Language tags on string literals, as Turtle spells them: {@code "Neoplasm"@en}.
 *
 * <p>They used to be dropped, silently and everywhere. Of the 292 annotation assertions in
 * one corpus document, 177 are tagged, and every one came back a plain string — a different
 * literal, so a different axiom. Any multilingual vocabulary lost every language it had.
 *
 * <p>The tag is part of the {@code STRING} token rather than a token of its own, which
 * settles two problems at once. A separate {@code '@' [a-zA-Z]+ …} rule would collide with
 * every annotation keyword — {@code @label}, {@code @doc}, {@code @db} — and {@code doc}
 * and {@code ann} are real ISO 639-3 codes, so that collision is not hypothetical. And a
 * tag must abut its string, or a {@code "x"} ending one line could swallow the
 * {@code @label} opening the next. A token is a contiguous run of characters by definition,
 * so folding the tag in gives both for nothing.
 */
class LanguageTagTest {

    private static final String NS = "http://example.org/t#";
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

    /** Every tagged annotation assertion, which is what these tests are about. */
    private static java.util.Set<OWLAnnotationAssertionAxiom> tagged(OWLOntology o) {
        return o.axioms(AxiomType.ANNOTATION_ASSERTION)
            .filter(ax -> ax.getValue() instanceof OWLLiteral
                && ((OWLLiteral) ax.getValue()).hasLang())
            .collect(java.util.stream.Collectors.toSet());
    }

    /** The one tagged literal in an ontology, whatever property carries it. */
    private OWLLiteral onlyLiteral(OWLOntology o) {
        return o.axioms(AxiomType.ANNOTATION_ASSERTION)
            .filter(ax -> ax.getValue() instanceof OWLLiteral)
            .map(ax -> (OWLLiteral) ax.getValue())
            .filter(OWLLiteral::hasLang)
            .findFirst().orElseThrow(() -> new AssertionError(
                "no tagged literal in " + o.getAxioms(AxiomType.ANNOTATION_ASSERTION)));
    }

    @Test
    void aTagIsReadOnEveryAnnotationForm() throws Exception {
        assertEquals("en", onlyLiteral(parse(PREFIX + "@label A \"Neoplasm\"@en\n")).getLang());
        assertEquals("fr", onlyLiteral(parse(PREFIX + "@doc A \"texte\"@fr\n")).getLang());
        assertEquals("de", onlyLiteral(parse(PREFIX + "@storage A \"Lager\"@de\n")).getLang());
        assertEquals("cy", onlyLiteral(parse(PREFIX + "@db A \"tabl\"@cy\n")).getLang());
        assertEquals("nl",
            onlyLiteral(parse(PREFIX + "@ann A rdfs:comment \"tekst\"@nl\n")).getLang());
    }

    /** A region or script subtag is part of the tag. */
    @Test
    void aSubtagIsKept() throws Exception {
        assertEquals("zh-hant",
            onlyLiteral(parse(PREFIX + "@label A \"Hanzi\"@zh-Hant\n")).getLang(),
            "OWL API lower-cases the tag, which BCP 47 permits");
        assertEquals("pt-br", onlyLiteral(parse(PREFIX + "@label A \"nome\"@pt-BR\n")).getLang());
    }

    /**
     * A tag whose spelling matches an annotation keyword still works.
     *
     * <p>This is what a separate token could not have done. {@code doc} and {@code ann} are
     * assigned ISO 639-3 codes, so a lexer rule for {@code '@' [a-zA-Z]+} declared after
     * {@code AT_DOC} would have lost them to the keyword on a length tie.
     */
    @Test
    void aTagThatLooksLikeAKeywordStillWorks() throws Exception {
        for (String tag : new String[] {"doc", "ann", "db", "label", "prefix", "import"}) {
            OWLOntology o = parse(PREFIX + "@label A \"text\"@" + tag + "\n");
            assertEquals(tag, onlyLiteral(o).getLang(),
                () -> "@" + tag + " must be read as a tag here: "
                    + o.getAxioms(AxiomType.ANNOTATION_ASSERTION));
        }
    }

    /**
     * A tag must abut its string, so a keyword on the next line is still a keyword.
     *
     * <p>Two {@code @label} statements in a row is the case that matters: if a tag could be
     * separated from its string, the first statement's value would swallow the second
     * statement's keyword.
     */
    @Test
    void aTagMustAbutItsString() throws Exception {
        OWLOntology two = parse(PREFIX + "@label A \"x\"\n@label B \"y\"\n");
        assertEquals(2, two.getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
            .filter(ax -> ax.getProperty().isLabel()).count(),
            () -> "both labels must survive: " + two.getAxioms(AxiomType.ANNOTATION_ASSERTION));
        assertTrue(two.getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
                .filter(ax -> ax.getValue() instanceof OWLLiteral)
                .noneMatch(ax -> ((OWLLiteral) ax.getValue()).hasLang()),
            () -> "and neither may have picked up a tag: "
                + two.getAxioms(AxiomType.ANNOTATION_ASSERTION));

        assertThrows(Throwable.class, () -> parse(PREFIX + "@label A \"x\" @en\n"),
            "a detached tag is not a tag");
    }

    /** An untagged string is still untagged — the tag is optional, not implied. */
    @Test
    void anUntaggedStringStaysUntagged() throws Exception {
        OWLOntology o = parse(PREFIX + "@label A \"plain\"\n");
        // The label has to be there before "it has no tag" means anything: `noneMatch` is
        // satisfied by an ontology with no annotation in it, and dropping every annotation
        // assertion left this green.
        OWLDataFactory f = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), IRI.create(NS + "A"), f.getOWLLiteral("plain"))),
            () -> "the untagged label itself must be there: "
                + o.getAxioms(AxiomType.ANNOTATION_ASSERTION));
        assertTrue(o.getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
                .filter(ax -> ax.getValue() instanceof OWLLiteral)
                .noneMatch(ax -> ((OWLLiteral) ax.getValue()).hasLang()),
            () -> o.getAxioms(AxiomType.ANNOTATION_ASSERTION).toString());
    }

    // ── Where a tag cannot mean anything ────────────────────────────────────

    /**
     * A facet value cannot carry a tag.
     *
     * <p>OWL has no facet that compares against a language, so keeping it produced a
     * datatype restriction no reasoner will accept — {@code xsd:pattern "…"@en}.
     */
    @Test
    void aTaggedFacetValueIsRefused() {
        Throwable t = assertThrows(Throwable.class, () -> parse(PREFIX
            + "⊤ ⊑ ∀code.[xsd:string ⊓ [matches \"[A-Z]{3}\"@en]]\n"));
        assertTrue(String.valueOf(t.getMessage()).contains("a facet value cannot carry a language tag"),
            () -> "got: " + t.getMessage());
    }

    /** An import reference names a document, not text. */
    @Test
    void aTaggedImportReferenceIsRefused() {
        Throwable t = assertThrows(Throwable.class,
            () -> parse(PREFIX + "@import \"vocab.dle\"@en\nA ⊑ B\n"));
        assertTrue(String.valueOf(t.getMessage()).contains("an import reference cannot carry a language tag"),
            () -> "got: " + t.getMessage());
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * A tagged literal round-trips, built through the API so the writer is under test.
     *
     * <p>This is the path that lost them: every literal was written as a bare quoted
     * string, so the tag never reached the document at all.
     */
    @Test
    void aTaggedLiteralRoundTrips() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI subject = IRI.create(NS + "A");
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), subject,
            df.getOWLLiteral("Neoplasm", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), subject,
            df.getOWLLiteral("tumeur", "fr")));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(subject),
            df.getOWLClass(IRI.create(NS + "B"))));

        String written = write(o);
        assertTrue(statementsOnly(written).contains("\"Neoplasm\"@en"),
            () -> "the tag must be written:\n" + written);

        OWLOntology back = parse(written);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(), () -> written);
        // Only the tagged ones: the writer also invents a label for any entity that has
        // none, which is long-standing behaviour and nothing to do with tags.
        assertEquals(tagged(o), tagged(back),
            () -> "the tagged annotations must be unchanged:\n" + written);
        assertEquals(statementsOnly(written), statementsOnly(write(back)),
            () -> "idempotent from the first pass:\n" + written);
    }

    /** A literal with no tag must not gain one on the way out. */
    @Test
    void anUntaggedLiteralGainsNothing() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
            IRI.create(NS + "A"), df.getOWLLiteral("plain")));

        String body = statementsOnly(write(o));
        assertTrue(body.contains("\"plain\""), () -> body);
        assertFalse(body.contains("\"plain\"@"), () -> "no tag may appear:\n" + body);
    }
}
