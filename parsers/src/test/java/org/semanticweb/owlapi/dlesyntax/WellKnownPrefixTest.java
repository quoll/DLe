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
 * For {@code skos:} and {@code dcterms:} that was not true, in either implementation:
 * both were rejected in every position. Rather than retract the claim, the prefix is
 * now bound the first time a name actually uses it, and the header is accurate.
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
    private static final String DCTERMS = "http://purl.org/dc/terms/";
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
     * The other prefix the header names, and the one with an unusual namespace.
     *
     * <p>Every other namespace here ends in {@code #}. This one ends in {@code /}, so
     * the IRI is asserted in full: a well-known namespace that quietly gained or lost
     * its final separator would resolve names to the wrong place without failing.
     */
    @Test
    void dctermsNeedsNoDeclarationEither() throws Exception {
        OWLOntology o = read(PREFIX + "@ann A dcterms:creator \"x\"\nA ⊑ ⊤\n");
        assertTrue(o.containsAnnotationPropertyInSignature(IRI.create(DCTERMS + "creator")),
            "dcterms:creator should be exactly " + DCTERMS + "creator");
    }

    /** Both at once, since they are registered independently. */
    @Test
    void bothCanBeUsedInOneDocument() throws Exception {
        String written = rewrite(PREFIX
            + "@ann A skos:prefLabel \"x\"\n@ann A dcterms:creator \"y\"\nA ⊑ ⊤\n");
        assertTrue(written.contains("@prefix skos: <" + SKOS + ">"), written);
        assertTrue(written.contains("@prefix dcterms: <" + DCTERMS + ">"), written);
    }

    /**
     * The boundary. The header names six prefixes and no more, so a seventh is still
     * the document\u0027s own business to declare \u2014 this is what stops "well known"
     * drifting into "any prefix anyone recognises".
     */
    @Test
    void anotherKnownVocabularyIsStillRefused() {
        assertTrue(refusal(PREFIX + "@ann A foaf:name \"x\"\nA ⊑ ⊤\n")
                .contains("unknown prefix"),
            "foaf: is not one the header promises, so it still needs declaring");
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

    // ── The header's own claim ──────────────────────────────────────────────

    /**
     * Every prefix the shipped header promises is actually usable without declaring it.
     *
     * <p>The header is read far more often than it is edited, and it is prepended to
     * every document the writer produces, so a promise it makes is one readers act on.
     * This one was false for two of the six it names for as long as the line existed.
     * Reading the claim out of the resource rather than restating it here means a
     * seventh prefix added to that line fails this test until it is implemented.
     *
     * <p>Only the prefix resolving is asserted. Whether {@code owl:} or {@code rdf:}
     * may be used as an annotation property is a different question with its own
     * answer, so the test demands that the parse not fail <em>for want of a
     * declaration</em>, not that it succeed.
     */
    @Test
    void everyPrefixTheHeaderPromisesResolves() throws Exception {
        String header;
        try (java.io.InputStream in = DLESyntaxStorerBase.class
                .getResourceAsStream("dle-syntax-header.txt")) {
            assertNotNull(in, "the header resource should ship with the parser");
            header = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String claim = null;
        for (String line : header.split("\n")) {
            if (line.contains("implicitly declared")) claim = line;
        }
        assertNotNull(claim, "the header should still carry its implicit-prefix claim");

        String[] named = claim.substring(claim.indexOf("declared:") + 9).split(",");
        assertTrue(named.length >= 2, "expected a list of prefixes in: " + claim);

        for (String raw : named) {
            String prefix = raw.trim();
            try {
                read(PREFIX + "@ann A " + prefix + ":example \"x\"\nA ⊑ ⊤\n");
            } catch (Throwable t) {
                assertFalse(String.valueOf(t.getMessage()).contains("unknown prefix"),
                    "the header promises '" + prefix + ":' is implicitly declared, but: "
                        + t.getMessage());
            }
        }
    }

    /** The same, for the lone-prefixed-name reading. */
    @Test
    void aLoneWellKnownNameIsNotAStatement() {
        assertTrue(refusal(PREFIX + "skos:Concept\n").contains("not a statement"),
            "skos: is a known prefix, so skos:Concept is a name, not the assertion Concept(skos)");
    }
}
