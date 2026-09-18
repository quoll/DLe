package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

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

    /** Parses a DLe document into a fresh ontology. */
    private OWLOntology readDocument(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    /** Writes an ontology built through the API, rather than one parsed from text. */
    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix("http://example.org/s#");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
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

    /**
     * Comment lines in a document, ignoring DLe's generated header and rule-offs.
     *
     * <p>The header is skipped by recognising its first line, not by position. Skipping any
     * leading run of {@code #} lines exempted the author's own leading comments from the
     * comparison — in the *source* as well as the output — so the test named for comment
     * preservation passed while every leading comment was being destroyed. Three of them
     * were, in the very document it reads.
     */
    private List<String> comments(String document) {
        List<String> out = new ArrayList<>();
        String[] lines = document.split("\n", -1);
        int i = 0;
        if (lines.length > 0 && lines[0].trim().startsWith("# DLe \u2014 Description Logic")) {
            while (i < lines.length && lines[i].startsWith("#")) i++;
        }
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
        // Counted, not just contained. `removeAll` on a List removes every occurrence of a
        // value, so a document that kept one copy of a repeated comment and dropped the
        // other passed — as did one that invented comments, since `extra` was never looked
        // at. Comparing multisets catches both directions.
        Map<String, Long> beforeCounts = before.stream()
            .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        Map<String, Long> afterCounts = after.stream()
            .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        List<String> lost = new ArrayList<>();
        beforeCounts.forEach((text, n) -> {
            long kept = afterCounts.getOrDefault(text, 0L);
            if (kept < n) lost.add(n + "\u00d7 \"" + text + "\" but " + kept + " written");
        });
        assertTrue(lost.isEmpty(), () -> "comment lines lost when writing: " + lost);
    }

    /**
     * A comment heading the document survives.
     *
     * <p>{@code @prefix} and the other three directives are not statements in the grammar, so
     * the statement visit never saw a comment above them and nothing else looked. A comment
     * at the top of the file — the ordinary way to head one — was captured by nothing and
     * vanished. It now belongs to the document, as a trailing comment does.
     *
     * <p>Position is not preserved, and is not asserted: document comments come back
     * together, and are unordered.
     */
    @Test
    void aCommentAboveThePrefixDeclarationSurvives() throws Exception {
        String source = "# First heading line\n"
            + "# Second heading line\n"
            + "@prefix : <http://example.org/c#>\n"
            + "\n"
            + "A \u2291 B\n";
        List<String> after = comments(rewrite(source));
        assertTrue(after.contains("First heading line") && after.contains("Second heading line"),
            () -> "a comment above @prefix must survive: " + after);
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

    /**
     * A comment on a statement's own line belongs to that statement, and only to it.
     *
     * <p>Both captures look at the same token. An inline comment is a hidden token to the
     * right of its own statement, and also a hidden token to the left of the next one, so
     * both branches claimed it: {@code A ⊑ B  # NOTE} followed by {@code C ⊑ D} produced a
     * {@code dle:inlineComment} on {@code :A} and a {@code dle:comment} on {@code :C} from
     * the same six characters, and the comment was then written out twice, once in each
     * entity's block. Only the line number distinguishes the two cases.
     */
    @Test
    void anInlineCommentIsNotAlsoTheNextStatementsBlockComment() throws Exception {
        String document = PREFIX + "A ⊑ B  # INLINE NOTE\nC ⊑ D\n";
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            manager.getOntologyLoaderConfiguration());

        java.util.List<org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom> notes =
            o.axioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)
                .filter(ax -> String.valueOf(ax.getValue()).contains("INLINE NOTE"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(1, notes.size(),
            () -> "one comment, one annotation: " + notes);
        assertTrue(notes.get(0).getSubject().toString().contains("#A"),
            () -> "and it belongs to the statement it sits on: " + notes);

        // And it is written once, not once per claimant.
        String written = rewrite(document);
        int occurrences = bodyOf(written).split("INLINE NOTE", -1).length - 1;
        assertEquals(1, occurrences,
            () -> "written once:\n" + bodyOf(written));
    }

    /** A comment on its own line above a statement is still that statement's. */
    @Test
    void aBlockCommentIsStillClaimed() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(
            PREFIX + "A ⊑ B\n\n# BLOCK NOTE\nC ⊑ D\n"), o,
            manager.getOntologyLoaderConfiguration());
        assertTrue(o.axioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)
                .anyMatch(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI
                        .equals(ax.getProperty().getIRI())
                    && String.valueOf(ax.getValue()).contains("BLOCK NOTE")
                    && ax.getSubject().toString().contains("#C")),
            () -> "the block comment belongs to the statement below it: "
                + o.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION));
    }

    /**
     * The compact spelling of a class assertion keeps its comment too.
     *
     * <p>Every other assertion form was given a subject and this one was overlooked, so a
     * comment above {@code rex:Cat} was dropped on the floor while the identical comment
     * above {@code rex : Cat} was kept — the silent discard this class exists to prevent.
     * The two spellings are the same axiom and must behave the same way.
     */
    @Test
    void aCommentAboveTheCompactAssertionKeepsItsSubject() throws Exception {
        for (String spelling : new String[] {"rex:Cat", "rex : Cat"}) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology o = manager.createOntology();
            new DLEOntologyParser().parse(new StringDocumentSource(
                PREFIX + "# about cats\n" + spelling + "\n"), o,
                manager.getOntologyLoaderConfiguration());

            java.util.Set<org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom> comments =
                o.axioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)
                    .filter(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI
                        .equals(ax.getProperty().getIRI()))
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(1, comments.size(),
                () -> "'" + spelling + "' must keep its comment: " + comments);
            assertTrue(comments.iterator().next().getSubject().toString().contains("Cat"),
                () -> "'" + spelling + "' must give it the same subject as the other"
                    + " spelling: " + comments);
        }
    }

    /**
     * A document comment stays one: nothing may be written after it.
     *
     * <p>Ownership is decided by position on the way back in — a comment belongs to the
     * statement below it — so a comment with no statement of its own has to be last in the
     * file. It was written from {@code endWritingOntology}, which is not the end:
     * {@code writeAxiomsWithNoBlock} runs after it. Any axiom with no entity block of its own
     * therefore landed beneath the comment, and the comment acquired an owner on the next
     * read and stopped being a document comment at all.
     *
     * <p>The fixture pairs one with a declaration-only datatype, whose kind statement is
     * written by exactly that pass.
     */
    @Test
    void aDocumentCommentKeepsItsIndependence() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/s"));
        OWLDatatype t = f.getOWLDatatype(IRI.create("http://example.org/s#T"));
        OWLDataProperty d = f.getOWLDataProperty(IRI.create("http://example.org/s#d"));
        m.applyChange(new AddOntologyAnnotation(o,
            f.getOWLAnnotation(
                f.getOWLAnnotationProperty(DLESyntaxAxiomVisitor.DLE_COMMENT_IRI),
                f.getOWLLiteral("A document comment"))));
        m.addAxiom(o, f.getOWLDeclarationAxiom(t));
        m.addAxiom(o, f.getOWLDataPropertyRangeAxiom(d, t));

        String written = write(o);
        OWLOntology back = readDocument(written);
        assertTrue(back.annotations()
                .anyMatch(a -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI
                        .equals(a.getProperty().getIRI())
                    && "A document comment".equals(
                        ((OWLLiteral) a.getValue()).getLiteral())),
            () -> "it must come back as a document comment, not attached to an entity:\n"
                + written + "\nannotations: "
                + back.annotations().map(Object::toString)
                    .collect(Collectors.toList())
                + "\nassertions: "
                + back.axioms(AxiomType.ANNOTATION_ASSERTION).map(Object::toString)
                    .collect(Collectors.toList()));
    }
}
