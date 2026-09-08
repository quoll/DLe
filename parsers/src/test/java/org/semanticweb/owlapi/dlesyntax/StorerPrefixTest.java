package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which prefixes the storer writes with.
 *
 * <p>Two sources can supply them: the format the ontology was loaded from, and
 * the format passed to {@code saveOntology}. The storer used to prefer the
 * former outright whenever it was present, so a caller who invoked the parser
 * directly and handed back the populated format it returned had it silently
 * discarded — no {@code @prefix} lines were written, and every bare name
 * re-resolved to the DLe default namespace on reload. Both sources are now
 * merged, with the caller's winning.
 */
class StorerPrefixTest {

    private static final String NS = "http://example.org/t#";
    private static final String DOC =
        "@prefix : <" + NS + ">\nAnimal ⊑ Organism\n@doc Animal \"An animal.\"\n";

    /** Parses without going through the manager, as a direct caller would. */
    private OWLDocumentFormat parseInto(OWLOntologyManager manager, OWLOntology ontology,
                                        String document) throws Exception {
        return new DLEOntologyParser().parse(
            new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
    }

    private String write(OWLOntologyManager manager, OWLOntology ontology,
                         OWLDocumentFormat format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── The bug ─────────────────────────────────────────────────────────────

    @Test
    void aFormatPassedToSaveOntologySuppliesThePrefixes() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology, DOC);

        String written = write(manager, ontology, parsed);

        assertTrue(written.contains("@prefix : <" + NS + ">"),
            "the default prefix the parser recorded must be written:\n" + written);
        assertTrue(written.contains("Animal ⊑ Organism"),
            "names should still be written in their short form:\n" + written);
    }

    @Test
    void namesDoNotSilentlyMoveNamespaceOnReload() throws Exception {
        // The symptom that matters. Without the @prefix line, `Animal` re-resolves
        // to the DLe default namespace and the round trip is not the same ontology.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology, DOC);
        Set<OWLLogicalAxiom> before = ontology.getLogicalAxioms();

        String written = write(manager, ontology, parsed);

        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = reader.createOntology();
        new DLEOntologyParser().parse(
            new StreamDocumentSource(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8))),
            reloaded, reader.getOntologyLoaderConfiguration());

        assertEquals(before, reloaded.getLogicalAxioms(),
            "the same axioms, on the same IRIs, must survive the round trip:\n" + written);
        assertTrue(reloaded.getLogicalAxioms().toString().contains(NS),
            "reloaded names must still be in " + NS + ":\n" + reloaded.getLogicalAxioms());
    }

    // ── The path that already worked, which must keep working ───────────────

    @Test
    void theOntologysOwnFormatIsUsedWhenTheCallerSuppliesNothing() throws Exception {
        // The usual call: saveOntology(o, new DLESyntaxDocumentFormat(), target).
        // The output format carries no document prefixes, so the ontology's
        // recorded format is the only place they exist.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology, DOC);
        manager.setOntologyFormat(ontology, parsed);

        String written = write(manager, ontology, new DLESyntaxDocumentFormat());

        assertTrue(written.contains("@prefix : <" + NS + ">"),
            "prefixes recorded on the ontology must be written:\n" + written);
    }

    @Test
    void aFreshFormatsStandardPrefixesDoNotResetTheDocuments() throws Exception {
        // A fresh DLESyntaxDocumentFormat is not empty: PrefixDocumentFormatImpl
        // seeds owl:, rdf:, rdfs:, xsd: and xml:. If those displaced the
        // document's own declarations, a document that redeclares one of them
        // would be silently rewritten — and the renderer, finding no prefix that
        // matches, would write the affected names bare, to be re-resolved
        // against a different namespace on reload.
        String doc = "@prefix : <" + NS + ">\n"
            + "@prefix xsd: <http://example.org/mine#>\n"
            + "⊤ ⊑ ∀code.xsd:Code\n";

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology, doc);
        manager.setOntologyFormat(ontology, parsed);
        Set<OWLLogicalAxiom> before = ontology.getLogicalAxioms();

        String written = write(manager, ontology, new DLESyntaxDocumentFormat());

        assertTrue(written.contains("@prefix xsd: <http://example.org/mine#>"),
            "a redeclared xsd: must survive:\n" + written);

        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = reader.createOntology();
        new DLEOntologyParser().parse(
            new StreamDocumentSource(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8))),
            reloaded, reader.getOntologyLoaderConfiguration());
        assertEquals(before, reloaded.getLogicalAxioms(),
            "the redeclared namespace must survive the round trip:\n" + written);
    }

    @Test
    void theDocumentsPrefixWinsWhenBothSourcesSaySomething() throws Exception {
        // A genuine conflict: the document says `:` is one namespace, the caller
        // says another. The document wins, because that is where its entities
        // actually live. Letting the caller win produced a document whose every
        // name reloaded against the overriding namespace — `t#Animal` came back
        // as `override#Animal`, silently a different ontology.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology, DOC);
        manager.setOntologyFormat(ontology, parsed);
        Set<OWLLogicalAxiom> before = ontology.getLogicalAxioms();

        DLESyntaxDocumentFormat override = new DLESyntaxDocumentFormat();
        override.setPrefix(":", "http://example.org/override#");

        String written = write(manager, ontology, override);

        assertTrue(written.contains("@prefix : <" + NS + ">"),
            "the document's own namespace must be written:\n" + written);
        assertFalse(written.contains("http://example.org/override#"),
            "an override that would move the entities must not be written:\n" + written);

        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = reader.createOntology();
        new DLEOntologyParser().parse(
            new StreamDocumentSource(new ByteArrayInputStream(written.getBytes(StandardCharsets.UTF_8))),
            reloaded, reader.getOntologyLoaderConfiguration());
        assertEquals(before, reloaded.getLogicalAxioms(),
            "names must not move namespace:\n" + written);
    }

    @Test
    void prefixesFromBothSourcesAreCombined() throws Exception {
        // Non-conflicting prefixes from each source should both appear.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology, DOC);
        manager.setOntologyFormat(ontology, parsed);

        DLESyntaxDocumentFormat extra = new DLESyntaxDocumentFormat();
        extra.setPrefix("ex:", "http://example.org/extra#");

        String written = write(manager, ontology, extra);

        assertTrue(written.contains("@prefix : <" + NS + ">"), written);
        assertTrue(written.contains("@prefix ex: <http://example.org/extra#>"), written);
    }

    @Test
    void theImplicitDlePrefixesAreNotRewritten() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat parsed = parseInto(manager, ontology,
            "⊤ ⊑ ∀name.xsd:string\n");

        String written = write(manager, ontology, parsed);

        assertFalse(written.contains("@prefix xsd:"),
            "xsd: is implicit in every DLe document:\n" + written);
        assertFalse(written.contains("@prefix owl:"), written);
    }
}
