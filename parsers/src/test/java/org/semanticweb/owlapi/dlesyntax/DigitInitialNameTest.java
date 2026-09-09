package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Names in the default namespace whose local part begins with a digit.
 *
 * <p>Turtle permits a digit-initial local part, so such names arrive from real
 * documents. DLe had no spelling for one in the default namespace: {@code NAME}
 * requires a NameStart, which excludes digits, and {@code PREFIXED_NAME} requires
 * a NameStart before the colon, so the prefix could not be empty. The writer
 * emitted the bare local part regardless, which lexes as a number — it reported
 * success and produced a document that would not parse.
 *
 * <p>Such a name is now written and read as {@code DEFAULT_NAME}: {@code :1}.
 */
class DigitInitialNameTest {

    private static final String NS = "http://example.org/t#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    /**
     * An ontology together with the format its prefixes came from. The format has to
     * travel with it: a fresh {@code DLESyntaxDocumentFormat} carries no document
     * prefixes, so the writer could not shorten a name at all and would fall back to
     * a bare full IRI — a separate defect that would mask this one.
     */
    private static final class Parsed {
        final OWLOntology ontology;
        final OWLDocumentFormat format;
        Parsed(OWLOntology ontology, OWLDocumentFormat format) {
            this.ontology = ontology;
            this.format = format;
        }
    }

    private Parsed parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        return new Parsed(ontology, format);
    }

    private String write(Parsed parsed) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        parsed.ontology.getOWLOntologyManager().saveOntology(
            parsed.ontology, parsed.format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Reads a document, writes it, and reads the result back. */
    private OWLOntology roundTrip(String document) throws Exception {
        return parse(write(parse(document))).ontology;
    }

    // ── The defect ──────────────────────────────────────────────────────────

    @Test
    void aDigitInitialNameIsWrittenWithItsPrefix() throws Exception {
        String written = write(parse(PREFIX + "Thing2 ⊑ :1\n"));
        assertTrue(written.contains(":1"), () -> "expected :1 in\n" + written);
        assertFalse(written.matches("(?s).*⊑\\s+1\\s.*"),
                    () -> "a bare digit-initial name does not parse:\n" + written);
    }

    @Test
    void aDigitInitialNameSurvivesARoundTrip() throws Exception {
        OWLOntology back = roundTrip(PREFIX + "Thing2 ⊑ :1\n");
        assertTrue(back.containsClassInSignature(IRI.create(NS + "1")),
                   "the name must come back in the default namespace");
        assertFalse(back.containsClassInSignature(IRI.create(NS + ":1")),
                    "the colon must not become part of the local name");
    }

    @Test
    void writingIsIdempotent() throws Exception {
        String once = write(parse(PREFIX + "Thing2 ⊑ :1\n"));
        assertEquals(once, write(parse(once)));
    }

    @Test
    void aHyphenAfterTheLeadingDigitIsAccepted() throws Exception {
        OWLOntology back = roundTrip(PREFIX + "Thing2 ⊑ :2-b\n");
        assertTrue(back.containsClassInSignature(IRI.create(NS + "2-b")),
                   "NameChar includes '-', so it is legal after the first character");
    }

    @Test
    void aCommentOnADigitInitialNameIsKept() throws Exception {
        // findFirstNameIRI scans for name tokens to attach a comment to, and had to
        // learn the new one or the comment would attach to the following entity.
        String written = write(parse(PREFIX + "# a numeric identifier\n:1 ⊑ Thing2\n"));
        assertTrue(written.contains("# a numeric identifier"),
                   () -> "expected the comment in\n" + written);
    }

    /**
     * The writer half, with no DLe reader in the loop.
     *
     * <p>This is how the defect was reported: the name arrives from Turtle, which
     * permits a digit-initial local part, and the writer emitted the bare local part.
     * The other tests here cannot isolate it, because before this change the input
     * {@code :1} did not parse either.
     */
    @Test
    void anOntologyBuiltWithoutDleWritesTheNameParseably() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "Thing2")), df.getOWLClass(IRI.create(NS + "1"))));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(written.contains("Thing2 ⊑ :1"), () -> "expected Thing2 ⊑ :1 in\n" + written);
        // The point of the exercise: what was written must read back.
        assertTrue(parse(written).ontology.containsClassInSignature(IRI.create(NS + "1")),
                   () -> "the written document must parse:\n" + written);
    }

    /**
     * {@code :1} parses but {@code :A} does not, because only a digit-initial local part
     * has no bare spelling. ANTLR reports an unexpected ':' and lists the token names,
     * which does not explain the asymmetry, so the message says so.
     */
    @Test
    void aLeadingColonOnANonDigitNameExplainsItself() {
        Exception thrown = assertThrows(Exception.class,
            () -> parse(PREFIX + ":A ⊑ ⊤\n"));
        assertTrue(thrown.getMessage().contains("begins with a digit"),
            () -> "the error should explain the restriction: " + thrown.getMessage());
    }

    // ── What must not change ────────────────────────────────────────────────

    @Test
    void anOrdinaryDefaultNamespaceNameStaysBare() throws Exception {
        String written = write(parse(PREFIX + "Thing2 ⊑ Thing3\n"));
        assertTrue(written.contains("Thing2 ⊑ Thing3"),
                   () -> "the default prefix must still be stripped:\n" + written);
        assertFalse(written.contains(":Thing2"), () -> "unexpected prefix in\n" + written);
    }

    @Test
    void anUnderscoreInitialNameStaysBare() throws Exception {
        // NameStart already includes '_', so the bare form spells it.
        String written = write(parse(PREFIX + "_a ⊑ Thing2\n"));
        assertTrue(written.contains("_a ⊑ Thing2"), () -> "expected bare _a in\n" + written);
    }

    @Test
    void aDigitInitialNameUnderARealPrefixIsUnchanged() throws Exception {
        String doc = PREFIX + "@prefix sct: <http://snomed.info/id/>\nThing2 ⊑ sct:1\n";
        assertTrue(write(parse(doc)).contains("sct:1"), "PREFIXED_NAME already covered this");
        assertTrue(roundTrip(doc).containsClassInSignature(IRI.create("http://snomed.info/id/1")));
    }

    @Test
    void thePrefixDeclarationStillLexes() throws Exception {
        // PNAME_NS matches the lone ':' of the declaration. DEFAULT_NAME needs a digit
        // straight after the colon, so a declaration cannot be mistaken for a name.
        OWLOntology o = parse(PREFIX + "Thing2 ⊑ Thing3\n").ontology;
        assertTrue(o.containsClassInSignature(IRI.create(NS + "Thing2")),
                   "the @prefix : declaration must still take effect");
    }
}
