package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A well-known prefix is registered on first use, not required in advance.
 *
 * <p>The file header has always said the standard prefixes are implicitly declared.
 * For {@code skos:} that was not true, in either implementation: it was rejected in
 * every position. Rather than retract the claim, the prefix is now bound the first
 * time a name actually uses it.
 *
 * <p>The written document follows from that and needs no rule of its own. The writer
 * emits the prefixes the reader ended up holding, so a document that never names
 * {@code skos:} never registers it and never gains the declaration, while one that
 * does gets an explicit {@code @prefix} line and reads back unchanged.
 *
 * <p>What needs care is that registering on use makes the map grow as the document is
 * read, and three decisions turn on whether a prefix <em>exists</em> rather than on
 * what it expands to. Those must not depend on how far the reader has got, or the
 * same line means different things at the top and bottom of a file. They ask
 * {@code isKnownPrefix} instead, and the last two tests here are what hold that.
 */
class WellKnownPrefixTest {

    private static final String NS = "http://example.org/wk#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology read(String document) throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager().createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            o.getOWLOntologyManager().getOntologyLoaderConfiguration());
        return o;
    }

    /**
     * Reads a document and writes it out again, reusing the format the parser returned.
     *
     * <p>Reusing it is the point: the prefix map lives on that format, so saving with a
     * fresh one discards everything the reader learned — including the prefix
     * registered on first use, which is what these tests are about.
     */
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
     * A document with its generated header removed.
     *
     * <p>Needed because the header itself names skos — it is the line claiming the
     * standard prefixes are implicitly declared — so a search of the whole document
     * finds the word whether or not any prefix was declared.
     */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private String refusal(String document) {
        Throwable t = assertThrows(Throwable.class, () -> read(document),
            () -> "expected a refusal for:\n" + document);
        return String.valueOf(t.getMessage());
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The claim the header makes, for the one prefix this covers so far. */
    @Test
    void skosNeedsNoDeclaration() throws Exception {
        OWLOntology o = read(PREFIX + "@ann A skos:prefLabel \"x\"\nA ⊑ ⊤\n");
        assertTrue(o.containsAnnotationPropertyInSignature(IRI.create(SKOS + "prefLabel")),
            "skos:prefLabel should bind to the SKOS namespace without an @prefix line");
    }

    /** Not only in annotations: resolution is shared, so every position gains it. */
    @Test
    void skosWorksWhereverANameDoes() throws Exception {
        OWLOntology o = read(PREFIX + "A ⊑ ∃skos:broader.B\n");
        assertTrue(o.containsObjectPropertyInSignature(IRI.create(SKOS + "broader")),
            "a filler position resolves through the same prefix map");
    }

    /** A document that binds the prefix itself keeps its own binding. */
    @Test
    void aDocumentsOwnBindingWins() throws Exception {
        OWLOntology o = read(PREFIX
            + "@prefix skos: <http://example.com/mine#>\n"
            + "@ann A skos:note \"x\"\nA ⊑ ⊤\n");
        assertTrue(o.containsAnnotationPropertyInSignature(IRI.create("http://example.com/mine#note")),
            "the declared namespace, not the well-known one");
        assertFalse(o.containsAnnotationPropertyInSignature(IRI.create(SKOS + "note")),
            "the well-known binding must not overwrite a declared one");
    }

    /**
     * The boundary of this change. dcterms: is the other prefix the header names and
     * is deliberately still undeclared, so that this stayed one prefix wide.
     */
    @Test
    void dctermsStillRequiresADeclaration() {
        assertTrue(refusal(PREFIX + "@ann A dcterms:creator \"x\"\nA ⊑ ⊤\n")
                .contains("unknown prefix"),
            "dcterms: is not in scope yet and should still be refused");
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** Registered on use means absent when unused: no pruning rule is needed. */
    @Test
    void aDocumentThatNeverNamesSkosDoesNotDeclareIt() throws Exception {
        String written = statementsOnly(rewrite(PREFIX + "Person ⊑ Agent\n"));
        assertFalse(written.contains("skos"),
            "an untouched well-known prefix must not be declared in the output:\n" + written);
    }

    /** And present when used, as an explicit declaration that reads back. */
    @Test
    void aUsedPrefixIsWrittenOutAndReadsBack() throws Exception {
        String written = rewrite(PREFIX + "@ann A skos:prefLabel \"x\"\nA ⊑ ⊤\n");
        assertTrue(written.contains("@prefix skos: <" + SKOS + ">"),
            "the prefix the reader registered should be declared on the way out");
        assertTrue(read(written).containsAnnotationPropertyInSignature(IRI.create(SKOS + "prefLabel")),
            "and the written document should mean the same thing");
    }

    // ── Order independence ──────────────────────────────────────────────────

    /**
     * {@code skos:a:C} is genuinely ambiguous when {@code a:} is also a prefix, and
     * saying so must not depend on whether skos was named earlier in the file.
     *
     * <p>Consulting the live map instead read the first document as the individual
     * {@code skos} of class {@code a:C}, silently, while the second — identical but
     * for an earlier mention of skos — raised the ambiguity. Same line, two meanings.
     */
    @Test
    void ambiguityDoesNotDependOnPosition() {
        String withPrefixes = PREFIX + "@prefix a: <http://ex/a#>\n";
        String first = refusal(withPrefixes + "skos:a:C\n");
        String afterAnEarlierMention = refusal(withPrefixes
            + "@ann X skos:note \"earlier\"\nX ⊑ ⊤\nskos:a:C\n");
        assertTrue(first.contains("ambiguous"), "first mention: " + first);
        assertTrue(afterAnEarlierMention.contains("ambiguous"), "later: " + afterAnEarlierMention);
    }

    /** The same, for the lone-prefixed-name reading. */
    @Test
    void aLoneWellKnownNameIsNotAStatement() {
        assertTrue(refusal(PREFIX + "skos:Concept\n").contains("not a statement"),
            "skos: is a known prefix, so skos:Concept is a name, not the assertion Concept(skos)");
    }
}
