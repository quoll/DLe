package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How {@code @import} names its target.
 *
 * <p>Three forms are accepted: an IRI, a prefixed name, and a double-quoted string. The
 * string exists because a relative path is the natural way to name a file beside this one,
 * and it cannot be carried as an IRI — under RFC 3986 a scheme must be followed by a slash
 * for the path to be hierarchical, so {@code file:x.dle} is opaque, has no path, and cannot
 * be opened. A relative reference in either form is resolved against the document that
 * declared it, which is the only base that means anything.
 */
class ImportResolutionTest {

    private static final String HEAD =
        "@ontology <http://example.org/f>\n@prefix : <http://example.org/f#>\n";

    private OWLOntology parseFile(Path file) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(file.toFile());
    }

    private Path write(Path dir, String name, String body) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    // ── Resolution ──────────────────────────────────────────────────────────

    @Test
    void aQuotedRelativePathResolvesAgainstTheDocument(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", "@ontology <http://example.org/v>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        assertEquals(1, o.importsDeclarations().count());
        assertEquals(dir.resolve("vocab.dle").toUri().toString().replace("file:///", "file:/"),
            o.importsDeclarations().findFirst().orElseThrow().getIRI().toString(),
            "the reference must resolve beside the importing document");
    }

    @Test
    void aSubdirectoryAndParentPathResolve(@TempDir Path dir) throws Exception {
        write(dir, "sub/deep.dle", "@ontology <http://example.org/d>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"sub/deep.dle\"\nA ⊑ B\n");
        assertTrue(parseFile(main).importsDeclarations().findFirst().orElseThrow()
            .getIRI().toString().endsWith("/sub/deep.dle"));

        write(dir, "beside.dle", "@ontology <http://example.org/b>\nThing1 ⊑ ⊤\n");
        Path nested = write(dir, "sub/inner.dle", HEAD + "@import \"../beside.dle\"\nA ⊑ B\n");
        assertTrue(parseFile(nested).importsDeclarations().findFirst().orElseThrow()
            .getIRI().toString().endsWith("/beside.dle"),
            "a parent-relative reference must climb out of the subdirectory");
    }

    @Test
    void anAbsoluteReferenceIsUntouchedInEitherForm(@TempDir Path dir) throws Exception {
        for (String form : new String[] {"<http://example.org/remote>", "\"http://example.org/remote\""}) {
            Path main = write(dir, "m.dle", HEAD + "@import " + form + "\nA ⊑ B\n");
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology o = manager.createOntology();
            // Silent: the point here is that the IRI is not rebased, not whether a
            // non-existent remote document can be fetched.
            new DLEOntologyParser().parse(
                new org.semanticweb.owlapi.io.FileDocumentSource(main.toFile()), o,
                manager.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                    org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
            assertEquals("http://example.org/remote",
                o.importsDeclarations().findFirst().orElseThrow().getIRI().toString(),
                () -> "an absolute IRI must not be rebased: " + form);
        }
    }

    /** The unit behind all of the above, including the cases with no usable base. */
    @Test
    void resolveImportHandlesTheAwkwardCases() {
        IRI base = IRI.create("file:/tmp/dir/doc.dle");
        assertEquals("file:/tmp/dir/x.dle",
            DLEOntologyParser.resolveImport(base, "x.dle").toString());
        assertEquals("http://example.org/x",
            DLEOntologyParser.resolveImport(base, "http://example.org/x").toString());
        // No base: leave it relative rather than resolve against something arbitrary.
        assertEquals("x.dle", DLEOntologyParser.resolveImport(null, "x.dle").toString());
        // An opaque base cannot be resolved against; `string:` sources look like this.
        assertEquals("x.dle",
            DLEOntologyParser.resolveImport(IRI.create("string:ontology"), "x.dle").toString());
    }

    // ── Loading, not merely declaring ───────────────────────────────────────

    /**
     * The import must actually be followed. Recording the declaration fetches nothing, so
     * the closure of a document with an {@code @import} used to be just the document —
     * true of every form, including an absolute {@code file:} IRI.
     */
    @Test
    void anImportIsLoadedIntoTheClosure(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", "@ontology <http://example.org/v>\n"
            + "@prefix : <http://example.org/v#>\nThing1 ⊑ ⊤\nThing2 ⊑ Thing1\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        assertEquals(2, o.importsClosure().count(),
            "the imported ontology must be in the closure");
        assertTrue(o.importsClosure().mapToInt(OWLOntology::getAxiomCount).sum()
                > o.getAxiomCount(),
            "and must contribute axioms");
    }

    /**
     * Two documents that declare no {@code @ontology} must not collide.
     *
     * <p>Both used to be given the same made-up default IRI, which was harmless while
     * imports were never followed. Once they are, the manager refuses the second with
     * "Ontology already exists" — and most DLe documents declare no {@code @ontology}, so
     * this is the common case rather than an edge one. An undeclared document is now left
     * anonymous, which collides with nothing and, unlike naming it after its own file,
     * puts no machine-specific location into it.
     */
    @Test
    void twoDocumentsWithoutAnOntologyIriDoNotCollide(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", "@prefix : <http://example.org/v#>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle",
            "@prefix : <http://example.org/f#>\n@import \"vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        assertEquals(2, o.importsClosure().count(),
            "neither document names itself, so neither can clash with the other");
        assertTrue(o.getOntologyID().isAnonymous(),
            "a document that declares no @ontology has no IRI to report");
    }

    // ── Writing it back ─────────────────────────────────────────────────────

    /**
     * A relative import is written back relative. It has to be resolved to an absolute IRI
     * on the way in, and writing that absolute local path back would put a machine-specific
     * location into a document that is likely under version control.
     */
    @Test
    void aRelativeImportIsWrittenBackRelative(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", "@ontology <http://example.org/v>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);

        File out = dir.resolve("out.dle").toFile();
        o.getOWLOntologyManager().saveOntology(
            o, new DLESyntaxDocumentFormat(), new FileDocumentTarget(out));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(written.contains("@import \"vocab.dle\""),
            () -> "expected the relative form back:\n" + written);
    }

    @Test
    void anImportOutsideTheOutputDirectoryStaysAbsolute(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", "@ontology <http://example.org/v>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);

        File out = dir.resolve("elsewhere/out.dle").toFile();
        Files.createDirectories(out.getParentFile().toPath());
        o.getOWLOntologyManager().saveOntology(
            o, new DLESyntaxDocumentFormat(), new FileDocumentTarget(out));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(written.contains("@import <file:"),
            () -> "a relative path would be wrong from here:\n" + written);
    }
}
