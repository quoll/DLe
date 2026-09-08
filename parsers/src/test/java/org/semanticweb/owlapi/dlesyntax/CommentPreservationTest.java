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
}
