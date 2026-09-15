package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code #} comments must survive being written back out.
 *
 * <p>A comment is attached to whatever entity follows it, and written back as
 * {@code #} lines before that entity's block. That works for OWL entities,
 * because the storer is called once per entity in the signature. It did not work
 * for a predicate: a predicate IRI is not an entity, so nothing ever wrote its
 * comments, and {@code endWritingOntology} discarded them on the grounds that
 * {@code dle:comment} is "internal and never emitted standalone". The result was
 * that a section heading above a block of {@code ≝} definitions disappeared.
 */
class CommentPreservationTest {

    private static final String PREFIX = "@prefix : <http://example.org/t#>\n";

    private String rewrite(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Comment lines in a document, ignoring the leading header block and rule-offs. */
    private List<String> comments(String document) {
        List<String> out = new ArrayList<>();
        String[] lines = document.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].startsWith("#")) i++;   // skip the header
        for (; i < lines.length; i++) {
            if (!lines[i].startsWith("#")) continue;
            String text = lines[i].replaceAll("^#+", "").trim();
            if (!text.isEmpty() && !text.matches("#+")) out.add(text);
        }
        return out;
    }

    // ── The bug ─────────────────────────────────────────────────────────────

    @Test
    void aCommentOnAPredicateSurvives() throws Exception {
        String document = PREFIX
            + "Animal ⊑ ⊤\n"
            + "\n"
            + "# Predicate definitions\n"
            + "greaterThan(x,y) ≝ x > y\n";

        String written = rewrite(document);

        assertTrue(written.contains("# Predicate definitions"),
            "the comment above a predicate definition must be written:\n" + written);
        assertTrue(comments(written).contains("Predicate definitions"), written);
    }

    @Test
    void aPredicatesCommentSurvivesAFullRoundTrip() throws Exception {
        String document = PREFIX
            + "Animal ⊑ ⊤\n"
            + "\n"
            + "# Predicate definitions\n"
            + "greaterThan(x,y) ≝ x > y\n";

        String once = rewrite(document);
        // Read what was written and write it again: the comment must still be there.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = manager.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StreamDocumentSource(new ByteArrayInputStream(once.getBytes(StandardCharsets.UTF_8))),
            reloaded, manager.getOntologyLoaderConfiguration());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(reloaded, format, new StreamDocumentTarget(out));
        String twice = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(twice.contains("Predicate definitions"),
            "the comment must survive a second generation:\n" + twice);
    }

    @Test
    void commentsOnPredicatesAndEntitiesAreBothKept() throws Exception {
        String document = PREFIX
            + "# About the animal\n"
            + "Animal ⊑ ⊤\n"
            + "\n"
            + "# About the predicate\n"
            + "greaterThan(x,y) ≝ x > y\n";

        List<String> written = comments(rewrite(document));

        assertTrue(written.contains("About the animal"), written.toString());
        assertTrue(written.contains("About the predicate"), written.toString());
    }

    // ── What already worked, and must keep working ──────────────────────────

    @Test
    void aCommentOnAnEntitySurvives() throws Exception {
        String written = rewrite(PREFIX + "# About the animal\nAnimal ⊑ Organism\n");
        assertTrue(comments(written).contains("About the animal"), written);
    }

    @Test
    void everyCommentLineOfASectionHeadingSurvives() throws Exception {
        String document = PREFIX
            + "####################\n"
            + "# Section heading\n"
            + "# with a second line\n"
            + "####################\n"
            + "Animal ⊑ Organism\n";

        List<String> written = comments(rewrite(document));

        assertTrue(written.contains("Section heading"), written.toString());
        assertTrue(written.contains("with a second line"), written.toString());
    }

    @Test
    void noCommentIsLostWritingTheCorpusDocument() throws Exception {
        // The regression document exercises predicates, section headings and
        // per-entity comments together.
        java.net.URL resource =
            getClass().getClassLoader().getResource("data/wildlife-reserve-test.dle");
        assertNotNull(resource);
        String source = new String(
            java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(resource.toURI())),
            StandardCharsets.UTF_8);

        List<String> before = comments(source);
        List<String> after = comments(rewrite(source));

        assertFalse(before.isEmpty(), "the document should contain comments");
        List<String> lost = new ArrayList<>(before);
        lost.removeAll(after);
        assertTrue(lost.isEmpty(), "comment lines lost when writing: " + lost);
    }

    /** The document body, with the generated explanatory header stripped. */
    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        boolean header = true;
        for (String line : document.split("\n", -1)) {
            if (header && !line.isEmpty() && line.charAt(0) != '#') header = false;
            if (!header) out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * A comment after the last statement survives.
     *
     * <p>Every other comment is found by looking left from the statement below it, and a
     * trailing block has nothing to look from — so it was read and discarded. It now
     * belongs to the document rather than to an entity, and is written back at the end.
     */
    @Test
    void aTrailingCommentSurvives() throws Exception {
        String written = rewrite(PREFIX + "A ⊑ B\n# a closing note\n");
        assertTrue(bodyOf(written).contains("# a closing note"),
            () -> "the trailing comment must be written back:\n" + written);
        assertEquals(bodyOf(written), bodyOf(rewrite(written)),
            () -> "and must be stable, not merely present once:\n" + written);
    }

    /**
     * A comment on an entity that gets no block of its own survives.
     *
     * <p>`b ⊑ A` is written in `A`'s block, because the base storer writes each axiom in
     * the first block that reaches it. So `b`'s own block holds nothing but this comment,
     * and if that block is written last the comment lands at the end of the file — where
     * the next read used to discard it. Two passes destroyed it.
     */
    @Test
    void aCommentOnAnEntityWithNoBlockOfItsOwnSurvives() throws Exception {
        String first = rewrite(PREFIX + "A ⊑ ⊤\n# about b\nb ⊑ A\n");
        assertTrue(bodyOf(first).contains("# about b"),
            () -> "written on the first pass:\n" + first);
        String second = rewrite(first);
        assertTrue(bodyOf(second).contains("# about b"),
            () -> "and must still be there after a second:\n" + second);
        // From the first pass, not merely from the second. The comment moves from an empty
        // entity block to the document's trailing block on the way through, and the two
        // paths used to differ by one blank line — so this is what pins them together.
        assertEquals(bodyOf(first), bodyOf(second),
            () -> "the first pass must already agree with the second:\n" + first);
    }

    /**
     * A comment whose subject is not in the signature at all is still written.
     *
     * <p>The standalone annotation pass filtered every {@code dle:comment} out, on the
     * grounds that they are internal and written with their entity. One whose subject has
     * no entity — an IRI that appears nowhere else, which RDF sources do produce — had no
     * entity to be written with, so it was discarded without trace.
     */
    @Test
    void aCommentOnAnIriOutsideTheSignatureIsStillWritten() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        org.semanticweb.owlapi.model.OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(org.semanticweb.owlapi.model.IRI.create("http://example.org/t#A")),
            df.getOWLClass(org.semanticweb.owlapi.model.IRI.create("http://example.org/t#B"))));
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(DLESyntaxAxiomVisitor.DLE_COMMENT_IRI),
            org.semanticweb.owlapi.model.IRI.create("http://example.org/absent#Ghost"),
            df.getOWLLiteral("a note with nowhere to live")));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix("http://example.org/t#");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(bodyOf(written).contains("# a note with nowhere to live"),
            () -> "it must not be discarded silently:\n" + written);
    }

    /**
     * A comment above an assertion belongs to the entity whose block it is in.
     *
     * <p>A comment attaches to the first name of the statement below it, and an assertion's
     * first name is not what the statement is about: {@code (bob,ann):knows} opens with an
     * individual. The writer puts a comment at the head of the block of the entity it
     * belongs to — {@code knows} for that line — so the comment changed subject on every
     * round trip, from the role to an individual.
     */
    @Test
    void aCommentAboveAnAssertionKeepsItsSubject() throws Exception {
        String first = rewrite(PREFIX + "# about the knows role\n(bob,ann):knows\n");
        assertTrue(bodyOf(first).contains("# about the knows role"),
            () -> "written back:\n" + first);
        assertEquals(bodyOf(first), bodyOf(rewrite(first)),
            () -> "and stable, which it is not if the subject moves:\n" + first);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(first), o,
            manager.getOntologyLoaderConfiguration());
        assertTrue(o.axioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)
                .anyMatch(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI
                        .equals(ax.getProperty().getIRI())
                    && ax.getSubject().toString().contains("knows")),
            () -> "the subject is the role, not an individual: "
                + o.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION));
    }
}
