package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name goes out in a spelling the reader accepts, or it does not go out at all.
 *
 * <p>Two decisions were made without consulting the grammar. The storer asked whether any
 * declared prefix was a leading substring of the whole IRI, which said "covered" far too
 * often: with {@code ex:} bound to {@code http://example.org/ex/}, the IRI
 * {@code http://example.org/ex/deep#B} counted as covered, so nothing was minted for
 * {@code .../deep#} and the name went out as {@code ex:deep#B} — where {@code #B} begins a
 * comment. And the writer emitted whatever the prefix manager returned, which abbreviates
 * against XML's rules rather than DLe's: a local part containing a dot came out bare as
 * {@code A.B} and read back as a restriction over {@code A}, turning one class into an
 * existential, silently.
 *
 * <p>Both now work from the lexer's own rule. The namespace to mint is whichever split leaves
 * a tail that {@code PREFIXED_NAME} accepts, and a spelling that cannot be lexed is replaced
 * by the longest declared namespace that can.
 */
class NameSpellingTest {

    private static final String NS = "http://example.org/o#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    /** One class named by the given IRI, subsumed by a plain one. */
    private OWLOntology ontologyNaming(String iri) throws Exception {
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/o"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(iri)), df.getOWLClass(IRI.create(NS + "Base"))));
        return o;
    }

    /**
     * Every local part that needs a split still names the same entity afterwards.
     *
     * <p>A dot, a colon, a percent escape, a leading hyphen, a digit-initial identifier in a
     * foreign namespace, an authority with no path: each of these had no spelling the reader
     * would accept, and each was written anyway.
     */
    @ParameterizedTest(name = "{0} survives")
    @ValueSource(strings = {
        "http://example.org/o#A.B",
        "http://example.org/o/A.B",
        "http://example.org/o#1.5",
        "http://example.org/o#A:B",
        "http://example.org/o#-A",
        "http://example.org/o#A%20B",
        "http://example.org/o#A+B",
        "http://example.org/o#A~B",
        "http://snomed.info/id/762705008",
        "http://example.org",
        "mailto:bob@example.com",
        "urn:uuid:12345678",
        "http://example.org/ex/deep#B",
    })
    void aNameThatNeedsASplitStillNamesTheSameEntity(String iri) throws Exception {
        String written = write(ontologyNaming(iri));
        OWLOntology back = read(written);
        assertTrue(back.containsClassInSignature(IRI.create(iri)),
            () -> "written as:\n" + statementsOnly(written) + "\nand came back naming "
                + back.classesInSignature().map(c -> c.getIRI().toString()).sorted()
                    .collect(java.util.stream.Collectors.toList()));
    }

    /** The ordinary spellings are untouched, including the two that carry a colon. */
    @ParameterizedTest(name = "{0} is written plainly")
    @ValueSource(strings = {
        "http://example.org/o#Cat",
        "http://example.org/o#has-part",
        "http://example.org/o#_internal",
        "http://example.org/o#116676008",
    })
    void anOrdinaryNameIsUnaffected(String iri) throws Exception {
        String written = write(ontologyNaming(iri));
        assertFalse(statementsOnly(written).contains("ns1:"),
            () -> "nothing needed minting here:\n" + statementsOnly(written));
        assertTrue(read(written).containsClassInSignature(IRI.create(iri)),
            () -> "written as:\n" + statementsOnly(written));
    }

    /**
     * An IRI with no spellable tail at all fails loudly rather than quietly.
     *
     * <p>A namespace IRI used directly as an entity IRI, or one ending in a dot, leaves
     * nothing a local part could be. There is no spelling to choose, so the honest outcome is
     * a document the reader refuses — not one it accepts as something else.
     */
    @ParameterizedTest(name = "{0} cannot be spelled")
    @ValueSource(strings = {
        "http://bare.example.com/vocab#",
        "http://example.org/o/A.",
    })
    void anIriWithNoSpellableTailIsLoud(String iri) throws Exception {
        String written = write(ontologyNaming(iri));
        assertThrows(Exception.class, () -> read(written),
            () -> "there is no legal spelling for this, so it must not quietly parse:\n"
                + statementsOnly(written));
    }

    /** The lexer's rule, asserted directly on the predicate the writer consults. */
    @Test
    void theSpellablePredicateMatchesTheLexer() {
        for (String legal : new String[] {"B", "A-B", "_x", "x1", "762705008", "1", "été"}) {
            assertTrue(DLESyntaxObjectRenderer.isSpellableLocalName(legal),
                () -> legal + " is a legal local part after a prefix");
        }
        for (String illegal : new String[] {"", "A.B", "A:B", "A%20B", "A+B", "A~B", "A/B", "A B",
                                            "A.", "-A".substring(0, 1) + ".", "A⁻B"}) {
            assertFalse(DLESyntaxObjectRenderer.isSpellableLocalName(illegal),
                () -> "\"" + illegal + "\" is not a legal local part after a prefix");
        }
        // A leading hyphen is a NameChar but not a legal first character.
        assertFalse(DLESyntaxObjectRenderer.isSpellableLocalName("-A"),
            "a local part may not begin with a hyphen");
    }

    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * A prefix label the lexer cannot read is replaced, not written.
     *
     * <p>{@code PNAME_NS} is {@code NameChar* ':'}, and a dot is not a {@code NameChar}. A
     * document declaring {@code a.b:} had {@code @prefix a.b: <…>} written straight out and
     * refused by this same reader — taking every name that used it down with it.
     *
     * <p>Unlike a local part there is nothing to salvage by splitting: a label is the
     * author's choice of abbreviation and carries no meaning, so an unusable one is dropped
     * and the namespace left for minting.
     */
    @Test
    void anUnusablePrefixLabelIsReplaced() throws Exception {
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/o"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create("http://example.org/d#X")),
            df.getOWLClass(IRI.create(NS + "Base"))));
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        format.setPrefix("a.b:", "http://example.org/d#");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertFalse(statementsOnly(written).contains("a.b:"),
            () -> "an unreadable label must not be written:\n" + statementsOnly(written));
        assertTrue(read(written).containsClassInSignature(IRI.create("http://example.org/d#X")),
            () -> "and the name it covered must still be the same name:\n" + written);
    }

    /** Every label the lexer does accept is left exactly as the document had it. */
    @ParameterizedTest(name = "prefix {0}: is kept")
    @ValueSource(strings = {"ex", "a-b", "a1", "A", "_x", "ns9"})
    void anUsablePrefixLabelIsKept(String label) throws Exception {
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/o"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create("http://example.org/d#X")),
            df.getOWLClass(IRI.create(NS + "Base"))));
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        format.setPrefix(label + ":", "http://example.org/d#");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(written.contains("@prefix " + label + ":"),
            () -> "the document's own label must survive:\n" + statementsOnly(written));
    }
}
