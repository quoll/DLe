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
        // Against the statements, not the whole document. The generated header quotes the
        // syntax it documents, and one of its lines contains `:1` — so this assertion was
        // satisfied by an ontology with no digit-initial name in it at all.
        String written = write(parse(PREFIX + "Thing2 ⊑ :1\n"));
        String body = statementsOnly(written);
        assertTrue(body.contains(":1"), () -> "expected :1 in\n" + body);
        assertFalse(body.matches("(?s).*⊑\\s+1\\s.*"),
                    () -> "a bare digit-initial name does not parse:\n" + body);
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

    /**
     * Only ASCII digits get the colon. {@code Character.isDigit} is true for the whole
     * Unicode Nd category, while {@code DEFAULT_NAME : ':' [0-9] NameChar*} accepts only
     * ASCII, so a name like {@code ٠x} was written {@code :٠x} — which the lexer rejects,
     * although the bare form was legal and round-tripped before.
     */
    @Test
    void aNonAsciiDigitKeepsItsBareForm() throws Exception {
        String written = write(parse(PREFIX + "٤x ⊑ Dog\n０y ⊑ Dog\n"));
        for (String name : new String[] {"٤x", "０y"}) {
            assertTrue(written.contains(name + " ⊑ Dog"),
                () -> "expected a bare name for " + name + " in\n" + written);
            assertFalse(written.contains(":" + name), () -> "that does not lex:\n" + written);
        }
        assertTrue(parse(written).ontology.containsClassInSignature(IRI.create(NS + "٤x")),
            "and the written document must still parse");
    }

    /**
     * The whole local part is checked, not just its first character. A dot is not a name
     * character, so {@code 1.Dog} has no prefixed spelling either; writing {@code :1.Dog}
     * produced a document that parsed as a restriction over a property {@code 1} — a
     * different ontology, silently. Keeping the bare form fails loudly instead.
     */
    @Test
    void aLocalPartWithNoLegalSpellingIsNotGivenAColon() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "1.Dog")), df.getOWLClass(IRI.create(NS + "Cat"))));
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(written.contains(":1.Dog"),
            () -> "a colon here reads back as a restriction, not this class:\n" + written);
    }

    /**
     * Whatever the writer emits must parse. This is the contract the colon decision exists
     * to keep, and the reason it has to agree with the grammar exactly.
     *
     * <p>`isNameChar` in the renderer is a hand-copied second definition of the grammar's
     * NameStart ranges, so the two can drift apart silently. Rather than compare the
     * definitions — one is private, the other generated — this checks the property that
     * matters, at the boundary of every range where a copy is most likely to be wrong,
     * including the deliberate hole at U+207B (the inverse marker).
     */
    @Test
    void whateverTheWriterEmitsCanBeParsed() throws Exception {
        int[] boundaries = {
            0x0041, 0x005A, 0x005F, 0x0061, 0x007A,          // ASCII letters and underscore
            0x002D, 0x002E, 0x0030, 0x0039,                  // hyphen, dot, digits
            0x00BF, 0x00C0, 0x02FF, 0x0300,                  // first Unicode range edges
            0x036F, 0x0370, 0x037D, 0x037E, 0x037F, 0x1FFF,
            0x2000, 0x200C, 0x200D, 0x200E,
            0x206F, 0x2070, 0x207A, 0x207B, 0x207C, 0x218F,  // U+207B is excluded on purpose
            0x2190, 0x2BFF, 0x2C00, 0x2FEF, 0x2FF0,
            0x3000, 0x3001, 0xD7FF, 0xF8FF, 0xF900, 0xFDCF,
            0xFDD0, 0xFDF0, 0xFFFD,
        };
        for (int cp : boundaries) {
            String local = "1" + new String(Character.toChars(cp));
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
            OWLDataFactory df = manager.getOWLDataFactory();
            manager.addAxiom(o, df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + local)), df.getOWLClass(IRI.create(NS + "Cat"))));
            DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
            format.setDefaultPrefix(NS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manager.saveOntology(o, format, new StreamDocumentTarget(out));
            String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

            // The invariant is that the name never comes back as a *different* name. Which
            // spelling the writer picks is its business: the default prefix and an explicit
            // colon where the local part is a legal NCName after one, or a minted prefix that
            // splits the IRI so the tail is one — `<...#1.Dog>` goes out as
            // `@prefix ns1: <...#1.>` with `ns1:Dog`. Failing loudly is acceptable too, for a
            // local part with no legal spelling at all. Coming back as something else is not.
            IRI original = IRI.create(NS + local);
            Parsed result;
            try {
                result = parse(written);
            } catch (RuntimeException e) {
                continue; // loudly wrong is allowed; silently wrong is not
            }
            assertTrue(result.ontology.containsClassInSignature(original),
                () -> String.format("U+%04X: %s parsed, but not as <%s>:%n%s%nsignature: %s",
                    cp, local, original, written,
                    result.ontology.classesInSignature()
                        .map(c -> c.getIRI().toString()).sorted()
                        .collect(java.util.stream.Collectors.toList())));
        }
    }

    /**
     * A local part that needs a split still round-trips, through a minted prefix.
     *
     * <p>{@code 1.Dog} cannot be written bare — a leading digit needs a colon, and DLe's own
     * {@code :1.Dog} would read as something else. It used to go out bare and fail loudly,
     * which was at least honest. It now goes out as {@code @prefix ns1: <...#1.>} with
     * {@code ns1:Dog}, because the namespace that needs covering is the one the OWL API's own
     * split produces, and that reassembles to exactly the IRI it came from.
     *
     * <p>The loose leading-substring test this replaced counted such a namespace as covered
     * whenever any declared prefix was a leading substring of the full IRI, which for a name
     * in the document's own namespace was always.
     */
    @Test
    void aNameNeedingASplitRoundTripsThroughAMintedPrefix() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "1.Dog")), df.getOWLClass(IRI.create(NS + "Cat"))));
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(parse(written).ontology
                .containsClassInSignature(IRI.create(NS + "1.Dog")),
            () -> "the minted prefix must reassemble to the IRI it came from:\n" + written);
    }

    /** A digit-initial name has to work wherever a name works, not just in a subsumption. */
    @Test
    void aDigitInitialNameWorksInEveryPosition() throws Exception {
        String[] statements = {
            "A ⊑ ∃:1.B", "A ⊑ ∀:1.B", "A ⊑ ∃:1⁻.B", "A ⊑ ≥2 :1.B",
            "A ≡ {:1, :2}", "Trans(:1)", "Func(:1)", "Disj(:1, :2)",
            ":1 ∘ :2 ⊑ :3", "∃:1.⊤ ⊑ A", "⊤ ⊑ ∀:1.B",
            "@label :1 \"one\"", "@doc :1 \"one\"", "@ann :1 rdfs:seeAlso \"x\"",
        };
        for (String statement : statements) {
            OWLOntology o = parse(PREFIX + statement + "\n").ontology;
            assertFalse(o.getAxioms().isEmpty(), () -> "no axioms from: " + statement);
        }
    }

    /** The parse error for a pre-fix document explains itself. */
    @Test
    void aBareDigitInitialNameIsExplained() {
        Exception thrown = assertThrows(Exception.class,
            () -> parse(PREFIX + "A ⊑ 1\n"));
        assertTrue(thrown.getMessage().contains("cannot begin with a digit"),
            () -> "this is the error an older document produces: " + thrown.getMessage());
    }

    @Test
    void anUnrelatedSyntaxErrorGetsNoHint() {
        Exception thrown = assertThrows(Exception.class, () -> parse(PREFIX + "A ⊑ ∃r.\n"));
        assertFalse(thrown.getMessage().contains("cannot begin with a digit"),
            () -> thrown.getMessage());
        assertFalse(thrown.getMessage().contains("leading ':'"), () -> thrown.getMessage());
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

    /**
     * The document without its generated header.
     *
     * <p>The header quotes every construct it documents, including a digit-initial name, so a
     * {@code contains} check against the whole document can be satisfied by the explanation
     * rather than by anything the writer produced.
     */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }
}
