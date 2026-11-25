package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A datatype an import declares is a datatype here.
 *
 * <p>Kinds are inferred from how a name is used, and the filler of a restriction is
 * what says whether its property is an object or a data property. A name the reader
 * does not know is a class, so {@code \u22a4 \u2291 \u2200live.gl:date-interval} made
 * {@code live} an object property \u2014 and the document then failed somewhere else
 * entirely, on a sub-property axiom hundreds of lines away.
 *
 * <p>The declaration was there; it was in the imported document, and imports were
 * loaded after the kinds had been settled. They are read first now.
 */
class ImportedDatatypeTest {

    private static final String VOCAB =
        "@ontology <http://example.org/v>\n"
        + "@prefix gl: <http://example.org/types/>\n"
        + "@prefix : <http://example.org/v#>\n"
        + "gl:date-interval \u2291 rdfs:Literal\n";

    private static final String DOC_HEAD =
        "@ontology <http://example.org/f>\n"
        + "@prefix gl: <http://example.org/types/>\n"
        + "@prefix mm: <http://example.org/mm/>\n"
        + "@prefix : <http://example.org/f#>\n";

    // Every @ directive precedes every statement, as the grammar requires, so the
    // import belongs in the head and the kind statement below it.
    private static final String FIELD = "mm:Field \u2291 owl:topDataProperty\n";

    /** The shape foundation-rdf.dle has: a domain, a range, and a super-property. */
    private static final String LIVE =
        "live \u2291 mm:Field\n"
        + "\u2203live.\u22a4 \u2291 Application\n"
        + "\u22a4 \u2291 \u2200live.gl:date-interval\n";

    private OWLOntology parse(Path file) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(file.toFile());
    }

    private Path write(Path dir, String name, String text) throws Exception {
        Path f = dir.resolve(name);
        Files.write(f, text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private boolean isDataProperty(OWLOntology o, String local) {
        return o.containsDataPropertyInSignature(IRI.create("http://example.org/f#" + local));
    }

    @Test
    void aDatatypeDeclaredInAnImportMakesItsPropertyADataProperty(@TempDir Path dir)
            throws Exception {
        write(dir, "vocab.dle", VOCAB);
        Path doc = write(dir, "doc.dle", DOC_HEAD + "@import <vocab.dle>\n" + FIELD + LIVE);

        OWLOntology o = assertDoesNotThrow(() -> parse(doc),
            "the document should load: the datatype is declared in what it imports");
        assertTrue(isDataProperty(o, "live"),
            "live ranges on an imported datatype, so it is a data property");
        assertTrue(o.containsDataPropertyInSignature(IRI.create("http://example.org/mm/Field")),
            "and so its super-property stays one");
    }

    /** The quoted form carries the same knowledge; only the spelling differs. */
    @Test
    void theQuotedImportFormWorksToo(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", VOCAB);
        Path doc = write(dir, "doc.dle", DOC_HEAD + "@import \"vocab.dle\"\n" + FIELD + LIVE);
        assertTrue(isDataProperty(assertDoesNotThrow(() -> parse(doc)), "live"));
    }

    /** A subdirectory resolves as a relative reference should. */
    @Test
    void anImportInASubdirectoryIsFound(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("ontology"));
        write(dir, "ontology/vocab.dle", VOCAB);
        Path doc = write(dir, "doc.dle", DOC_HEAD + "@import <ontology/vocab.dle>\n" + FIELD + LIVE);
        assertTrue(isDataProperty(assertDoesNotThrow(() -> parse(doc)), "live"));
    }

    /**
     * The boundary. Without the import the name means nothing, and the old reading
     * stands \u2014 which is what should happen, since nothing says it is a datatype.
     */
    @Test
    void withoutTheImportTheNameIsStillUnknown(@TempDir Path dir) throws Exception {
        Path doc = write(dir, "doc.dle", DOC_HEAD + FIELD + LIVE);
        assertThrows(Exception.class, () -> parse(doc),
            "an undeclared filler is a class, so live is an object property and the "
                + "sub-property axiom contradicts mm:Field");
    }

    /**
     * A failed import says that kinds may be wrong, not only that axioms are missing.
     *
     * <p>Since what an import declares decides kinds here, losing one can change how
     * the document reads \u2014 and the place it then goes wrong is rarely near the
     * import. The warning has to say so, or the reader looks in the wrong place.
     */
    @Test
    void aFailedImportWarnsThatKindsMayBeWrong(@TempDir Path dir) throws Exception {
        Path doc = write(dir, "doc.dle",
            DOC_HEAD + "@import <urn:example:nope>\n" + FIELD + "A \u2291 \u22a4\n");
        DLEOntologyParser parser = new DLEOntologyParser();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        parser.parse(new org.semanticweb.owlapi.io.FileDocumentSource(doc.toFile()),
            manager.createOntology(),
            manager.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                MissingImportHandlingStrategy.SILENT));
        assertFalse(parser.getWarnings().isEmpty(), "a failed import should warn");
        String warning = parser.getWarnings().get(0);
        assertTrue(warning.contains("kinds inferred"),
            () -> "the warning should mention inference: " + warning);
    }

    /**
     * An unreachable import must not take the document down, only leave it less
     * informed. The missing-import strategy decides that, and reading imports early
     * must not quietly bypass it.
     */
    @Test
    void anImportThatCannotBeFoundIsNotFatalHere(@TempDir Path dir) throws Exception {
        Path doc = write(dir, "doc.dle",
            DOC_HEAD + "@import <nowhere.dle>\n" + "A \u2291 \u22a4\n");
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        m.setOntologyLoaderConfiguration(m.getOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        assertDoesNotThrow(() -> m.loadOntologyFromOntologyDocument(doc.toFile()));
    }
}
