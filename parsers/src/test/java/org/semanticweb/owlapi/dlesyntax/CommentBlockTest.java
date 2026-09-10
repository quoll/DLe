package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code #} comment block is one annotation, and stays one across a round trip.
 *
 * <p>Comments used to be gathered per <em>statement</em>: everything before a statement
 * became a single multi-line literal. That is asymmetric with how they are written. An
 * entity named by three commented statements reads as three annotations, and the writer
 * emits all three at the head of its block, where the next read joins them back into one —
 * so a document lost comment annotations every time it was written and re-read.
 *
 * <p>The block is now the unit on both sides: a blank line ends a block on the way in, and
 * separates blocks on the way out.
 */
class CommentBlockTest {

    private static final String NS = "http://example.org/t#";
    private static final String HEAD = "@prefix : <" + NS + ">\n";

    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

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

    private long comments(OWLOntology o) {
        return o.axioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .count();
    }

    private long commentsOn(OWLOntology o, String local) {
        return o.annotationAssertionAxioms(IRI.create(NS + local))
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .count();
    }

    // ── Blocks are the unit ─────────────────────────────────────────────────

    @Test
    void aBlankLineEndsABlock() throws Exception {
        OWLOntology o = parse(HEAD + "\n# one\n\n# two\n\n# three\nA ⊑ B\n");
        assertEquals(3, commentsOn(o, "A"), "three blocks, three annotations");
    }

    @Test
    void adjacentLinesAreOneBlock() throws Exception {
        OWLOntology o = parse(HEAD + "\n# first line\n# second line\nA ⊑ B\n");
        assertEquals(1, commentsOn(o, "A"), "contiguous lines are one block");
        assertTrue(o.annotationAssertionAxioms(IRI.create(NS + "A"))
                .anyMatch(ax -> ax.getValue().toString().contains("first line")
                    && ax.getValue().toString().contains("second line")),
            "and both lines are in it");
    }

    /** The defect: the count must survive being written and read again. */
    @Test
    void severalCommentsOnOneEntitySurviveARoundTrip() throws Exception {
        String doc = HEAD
            + "\n# about the first fact\nA ⊑ B\n"
            + "\n# about the second fact\nA ⊑ C\n"
            + "\n# about the third fact\nA ⊑ D\n";
        OWLOntology first = parse(doc);
        assertEquals(3, commentsOn(first, "A"), "three commented statements, three annotations");

        OWLOntology second = parse(rewrite(doc));
        assertEquals(3, commentsOn(second, "A"),
            "writing and re-reading must not merge them");
        assertEquals(comments(first), comments(second));
    }

    @Test
    void writingIsStableAcrossGenerations() throws Exception {
        String doc = HEAD
            + "\n# one\nA ⊑ B\n"
            + "\n# two\nA ⊑ C\n";
        String once = rewrite(doc);
        assertEquals(comments(parse(doc)), comments(parse(once)));
        assertEquals(comments(parse(once)), comments(parse(rewrite(once))),
            "and must not decay on a further pass");
    }

    @Test
    void theWriterSeparatesBlocksWithABlankLine() throws Exception {
        String written = rewrite(HEAD + "\n# one\nA ⊑ B\n\n# two\nA ⊑ C\n");
        int start = written.indexOf("# one");
        assertTrue(start >= 0, () -> "expected the first comment in\n" + written);
        String between = written.substring(start, written.indexOf("# two"));
        assertTrue(between.contains("\n\n"),
            () -> "blocks must be separated or they read back as one:\n" + written);
    }

    // ── What must not change ────────────────────────────────────────────────

    @Test
    void theFileHeaderIsStillNotACommentOnAnything() throws Exception {
        // The leading contiguous block starting at line 1 describes the format, not the
        // ontology, so it is dropped rather than attached to the first entity.
        OWLOntology o = parse("# DLe — a header\n# second header line\n" + HEAD + "A ⊑ B\n");
        assertEquals(0, commentsOn(o, "A"), "the header is not a comment on A");
    }

    @Test
    void aMultiLineBlockIsStillASingleAnnotation() throws Exception {
        OWLOntology o = parse(HEAD + "\n# ###########\n# a heading\n# ###########\nA ⊑ B\n");
        assertEquals(1, commentsOn(o, "A"));
    }

    @Test
    void anInlineCommentIsUnaffected() throws Exception {
        OWLOntology o = parse(HEAD + "A ⊑ B  # trailing note\n");
        assertTrue(o.annotationAssertionAxioms(IRI.create(NS + "A"))
                .anyMatch(ax -> DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI
                    .equals(ax.getProperty().getIRI())),
            "a trailing comment is still recorded as an inline comment");
    }
}
