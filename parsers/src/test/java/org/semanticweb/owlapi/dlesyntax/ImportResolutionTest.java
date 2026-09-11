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
import java.util.List;

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

    private static final String NS_F = "http://example.org/f#";
    private static final String HEAD =
        "@ontology <http://example.org/f>\n@prefix : <http://example.org/f#>\n";

    private OWLOntology parseFile(Path file) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(file.toFile());
    }

    /**
     * The document without its explanatory header, which documents `@ontology` and
     * `@import` as prose — so a naive search of the output finds the documentation.
     */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
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

    /**
     * What a quoted reference means, as a table.
     *
     * <p>An IRI only when it parses as one AND carries a scheme that can actually be
     * retrieved. Everything else is a file path, taken literally — no percent-decoding — so
     * a file whose name contains {@code #}, {@code ?} or {@code %} names itself. Reading it
     * literally means encoding it to build the IRI, which is what lets the writer hand back
     * the same spelling.
     */
    @Test
    void whatAQuotedReferenceMeans() {
        IRI base = IRI.create("file:/dir/doc.dle");
        String[][] cases = {
            // retrievable schemes pass through untouched
            {"http://example.org/x",   "http://example.org/x"},
            {"https://example.org/x",  "https://example.org/x"},
            {"file:/abs/v.dle",        "file:/abs/v.dle"},
            // unretrievable schemes are file names whose first segment has a colon
            {"urn:x:y",                "file:/dir/urn:x:y"},
            {"mailto:bob@example.org", "file:/dir/mailto:bob@example.org"},
            {"classpath:v.dle",        "file:/dir/classpath:v.dle"},
            {"a:b.dle",                "file:/dir/a:b.dle"},
            // ordinary paths
            {"v.dle",                  "file:/dir/v.dle"},
            {"sub/deep.dle",           "file:/dir/sub/deep.dle"},
            {"../beside.dle",          "file:/beside.dle"},
            // literal names: nothing is decoded, everything is encoded to build the IRI
            {"my vocab.dle",           "file:/dir/my%20vocab.dle"},
            {"a%20b.dle",              "file:/dir/a%2520b.dle"},
            {"a#b.dle",                "file:/dir/a%23b.dle"},
            {"a?b.dle",                "file:/dir/a%3Fb.dle"},
            {"100%.dle",               "file:/dir/100%25.dle"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], DLEOntologyParser.resolveQuotedImport(base, c[0]).toString(),
                () -> "reference: " + c[0]);
        }
    }

    /** A Windows path is its own case, because a drive letter looks like a scheme. */
    @Test
    void aWindowsPathIsRecognised() {
        IRI base = IRI.create("file:/dir/doc.dle");
        assertEquals("file:///C:/vocab.dle",
            DLEOntologyParser.resolveQuotedImport(base, "C:\\vocab.dle").toString());
        assertEquals("file:///C:/vocab.dle",
            DLEOntologyParser.resolveQuotedImport(base, "C:/vocab.dle").toString());
        assertEquals("file:///D:/a%20b/v.dle",
            DLEOntologyParser.resolveQuotedImport(base, "D:\\a b\\v.dle").toString());
    }

    /** With no location to resolve against, a path is left as it stands. */
    @Test
    void withNoBaseAPathIsLeftRelative() {
        assertEquals("v.dle", DLEOntologyParser.resolveQuotedImport(null, "v.dle").toString());
        assertEquals("my%20v.dle",
            DLEOntologyParser.resolveQuotedImport(null, "my v.dle").toString(),
            "still escaped, so what is written can be read back");
        // A string or stream source has an opaque document IRI; there is nothing to resolve to.
        assertEquals("v.dle",
            DLEOntologyParser.resolveQuotedImport(IRI.create("string:ontology"), "v.dle").toString());
        // But an absolute reference never needed a base.
        assertEquals("http://example.org/x",
            DLEOntologyParser.resolveQuotedImport(null, "http://example.org/x").toString());
    }

    /** `@import <iri>` is handed over exactly as written — no resolution, any scheme. */
    @Test
    void theAngleFormIsUsedAsWritten(@TempDir Path dir) throws Exception {
        Path main = write(dir, "m.dle", HEAD + "@import <urn:example:vocab>\nA ⊑ B\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(
            new org.semanticweb.owlapi.io.FileDocumentSource(main.toFile()), o,
            manager.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
        assertEquals("urn:example:vocab",
            o.importsDeclarations().findFirst().orElseThrow().getIRI().toString(),
            "the angle form is the escape hatch for any scheme, so it must not be touched");
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

    /**
     * A file name may contain a space, which is illegal in a URI. Parsing the reference
     * therefore failed and it was passed through unresolved — and an unresolved relative
     * reference comes back from the manager as OWLOntologyFactoryNotFoundException, a
     * RuntimeException, which its own missing-import handling never sees. One space in a
     * file name took the whole document down.
     */
    @Test
    void aReferenceWithASpaceResolvesAndLoads(@TempDir Path dir) throws Exception {
        write(dir, "my vocab.dle", "@ontology <http://example.org/v>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"my vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        assertEquals(2, o.importsClosure().count(),
            () -> "the file exists and must be loaded: " + o.importsDeclarations().count());
    }

    /**
     * An unloadable reference must honour the caller's strategy, not bypass it. These come
     * back as RuntimeExceptions, so the manager's own handling is skipped.
     */
    @Test
    void anUnloadableReferenceHonoursTheStrategy(@TempDir Path dir) throws Exception {
        Path main = write(dir, "m.dle", HEAD + "@import <urn:example:nope>\nA ⊑ B\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        OWLOntology silent = manager.createOntology();
        new DLEOntologyParser().parse(
            new org.semanticweb.owlapi.io.FileDocumentSource(main.toFile()), silent,
            manager.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
        assertFalse(silent.getLogicalAxioms().isEmpty(),
            "silent means the readable part is still delivered");

        OWLOntologyManager strict = OWLManager.createOWLOntologyManager();
        OWLOntology thrown = strict.createOntology();
        assertThrows(org.semanticweb.owlapi.model.OWLRuntimeException.class,
            () -> new DLEOntologyParser().parse(
            new org.semanticweb.owlapi.io.FileDocumentSource(main.toFile()), thrown,
            strict.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                org.semanticweb.owlapi.model.MissingImportHandlingStrategy.THROW_EXCEPTION)),
            "and the default strategy must still refuse it");
    }

    /**

    /**
     * An import that cannot be loaded is reported even when the strategy is silent.
     *
     * <p>The manager's own handling never sees a reference it can find no factory for: that
     * failure arrives as a RuntimeException, outside the strategy entirely. A missing *file*
     * was reported; this was not.
     *
     * <p>Reached through the angle form, which is now the only way to write a reference with
     * an unretrievable scheme — a quoted one would be read as a file name.
     */
    @Test
    void anUnloadableReferenceIsReportedNotSwallowed(@TempDir Path dir) throws Exception {
        Path main = write(dir, "m.dle", HEAD + "@import <urn:example:nope>\nA ⊑ B\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        DLEOntologyParser parser = new DLEOntologyParser();
        parser.parse(new org.semanticweb.owlapi.io.FileDocumentSource(main.toFile()),
            manager.createOntology(),
            manager.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
        assertEquals(1, parser.getWarnings().size(),
            () -> "expected one warning, got " + parser.getWarnings());
        assertTrue(parser.getWarnings().get(0).contains("urn:example:nope"),
            () -> parser.getWarnings().get(0));
    }

    /** Warnings must not survive into the next parse, including after a failure. */
    @Test
    void warningsDoNotLeakBetweenParses(@TempDir Path dir) throws Exception {
        Path bad = write(dir, "bad.dle", HEAD + "@import <urn:example:nope>\nA ⊑ B\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        DLEOntologyParser parser = new DLEOntologyParser();
        org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration silent =
            manager.getOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT);
        parser.parse(new org.semanticweb.owlapi.io.FileDocumentSource(bad.toFile()),
            manager.createOntology(), silent);
        assertFalse(parser.getWarnings().isEmpty());

        Path broken = write(dir, "broken.dle", "this is not DLe (((\n");
        assertThrows(Exception.class, () -> parser.parse(
            new org.semanticweb.owlapi.io.FileDocumentSource(broken.toFile()),
            manager.createOntology(), silent));
        assertEquals(List.of(), parser.getWarnings(),
            "a failed parse must not leave the previous document's warnings behind");
    }


    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * A document that declares no @ontology is anonymous.
     *
     * <p>It used to be handed a fixed sentinel IRI. That is invisible until imports are
     * followed: most DLe documents omit @ontology, so any two of them then collide on
     * the same identity and the manager refuses the second.
     */
    @Test
    void aDocumentWithNoOntologyDeclarationIsAnonymous(@TempDir Path dir) throws Exception {
        OWLOntology o = parseFile(write(dir, "a.dle", "A ⊑ B\n"));
        assertTrue(o.isAnonymous(), () -> "expected anonymous, got " + o.getOntologyID());
    }

    /**
     * Two undeclared documents can be loaded together, one importing the other.
     *
     * <p>This is the common case, not an edge case, and it is what the sentinel broke.
     */
    @Test
    void twoUndeclaredDocumentsCoexist(@TempDir Path dir) throws Exception {
        write(dir, "dep.dle", "C ⊑ D\n");
        Path main = write(dir, "top.dle",
            HEAD + "@import \"dep.dle\"\nA ⊑ B\n");
        assertEquals(2, parseFile(main).importsClosure().count(),
            "both documents must be in the closure");
    }

    /**
     * A declared ontology IRI is written back, even when it happens to be the IRI the
     * reader once invented.
     *
     * <p>The writer suppressed that one IRI, so a document that genuinely declared it —
     * this project's own published vocabulary — silently lost its identity on the way out.
     */
    @Test
    void anOntologyIriThatMatchesTheOldSentinelSurvives(@TempDir Path dir) throws Exception {
        String iri = "http://quoll.github.io/DLe/ontology";
        Path main = write(dir, "s.dle", "@ontology <" + iri + ">\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        assertEquals(iri, o.getOntologyID().getOntologyIRI().get().toString());

        File out = dir.resolve("out.dle").toFile();
        o.getOWLOntologyManager().saveOntology(o, new DLESyntaxDocumentFormat(), IRI.create(out));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(statementsOnly(written).contains("@ontology <" + iri + ">"),
            () -> "the declared IRI must be written:\n" + written);
    }

    /** An ontology IRI and its version both survive the DLe text, not merely the parse. */
    @Test
    void anOntologyAndItsVersionSurviveTheText(@TempDir Path dir) throws Exception {
        Path main = write(dir, "v.dle", "@ontology <http://example.org/thing>\n"
            + "@version <http://example.org/thing/1.0>\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        assertEquals("http://example.org/thing/1.0",
            o.getOntologyID().getVersionIRI().get().toString());

        File out = dir.resolve("out.dle").toFile();
        o.getOWLOntologyManager().saveOntology(o, new DLESyntaxDocumentFormat(), IRI.create(out));
        String written = statementsOnly(
            new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
        assertTrue(written.contains("@ontology <http://example.org/thing>"),
            () -> "the ontology IRI must be written:\n" + written);
        assertTrue(written.contains("@version <http://example.org/thing/1.0>"),
            () -> "the version must be written:\n" + written);
    }

    /**
     * @version without @ontology is refused, and says why.
     *
     * <p>OWL has no such ontology: a version IRI identifies a version *of* a named
     * ontology, and OWL API rejects the pair outright. The alternatives were to drop the
     * declaration silently or to invent an ontology IRI the author never wrote.
     */
    @Test
    void aVersionWithoutAnOntologyIriIsRefused(@TempDir Path dir) throws Exception {
        Path main = write(dir, "v.dle", "@version <http://example.org/thing/1.0>\nA ⊑ B\n");
        String message = dleDiagnostic(main);
        assertTrue(message.contains("@version requires @ontology"),
            () -> "expected a diagnostic naming the rule, got: " + message);
    }

    /** Declaring an identity twice is refused rather than resolved by taking the last. */
    @Test
    void aDuplicateIdentityDeclarationIsRefused(@TempDir Path dir) throws Exception {
        Path two = write(dir, "d.dle",
            "@ontology <http://example.org/a>\n@ontology <http://example.org/b>\nA ⊑ B\n");
        String first = dleDiagnostic(two);
        assertTrue(first.contains("duplicate @ontology"), () -> "got: " + first);

        Path ver = write(dir, "dv.dle", "@ontology <http://example.org/a>\n"
            + "@version <http://example.org/a/1>\n@version <http://example.org/a/2>\nA ⊑ B\n");
        String second = dleDiagnostic(ver);
        assertTrue(second.contains("duplicate @version"), () -> "got: " + second);
    }

    /**
     * A relative IRI cannot be an identity.
     *
     * <p>OWL 2 requires both to be absolute. OWL API does not check, so a relative one
     * survives as far as the first document that imports it — a much worse place to find
     * out that the identity means something different depending on where it is read.
     */
    @Test
    void aRelativeIdentityIsRefused(@TempDir Path dir) throws Exception {
        Path main = write(dir, "r.dle", "@ontology <foo>\nA ⊑ B\n");
        String message = dleDiagnostic(main);
        assertTrue(message.contains("must be an absolute IRI"), () -> "got: " + message);
    }

    /**
     * Parses a document expected to fail, and returns the DLe parser's own diagnostic.
     *
     * <p>Going through the manager would bury it among every other parser's complaint, so
     * the DLe parser is invoked directly. That is also what characterises the message a
     * caller can act on.
     */
    private String dleDiagnostic(Path file) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        Throwable t = assertThrows(Throwable.class, () -> new DLEOntologyParser().parse(
            new org.semanticweb.owlapi.io.FileDocumentSource(file.toFile()),
            manager.createOntology(), manager.getOntologyLoaderConfiguration()));
        return String.valueOf(t.getMessage());
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
        // Saved by IRI, which is the overload owltx uses — and the one that carries the
        // document location, so it is the path that must be characterised here.
        o.getOWLOntologyManager().saveOntology(
            o, new DLESyntaxDocumentFormat(), IRI.create(out));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(written.contains("@import \"vocab.dle\""),
            () -> "expected the relative form back:\n" + written);
    }

    /**

    /** A name with a space keeps its spelling, rather than coming back percent-encoded. */
    @Test
    void aSpacedReferenceKeepsItsSpelling(@TempDir Path dir) throws Exception {
        write(dir, "my vocab.dle", "@ontology <http://example.org/v>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"my vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);
        File out = dir.resolve("out.dle").toFile();
        // Saved by IRI, which is the overload owltx uses — and the one that carries the
        // document location, so it is the path that must be characterised here.
        o.getOWLOntologyManager().saveOntology(
            o, new DLESyntaxDocumentFormat(), IRI.create(out));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(statementsOnly(written).contains("@import \"my vocab.dle\""),
            () -> "the spelling it came in as:\n" + written);
    }

    @Test
    void anImportOutsideTheOutputDirectoryStaysAbsolute(@TempDir Path dir) throws Exception {
        write(dir, "vocab.dle", "@ontology <http://example.org/v>\nThing1 ⊑ ⊤\n");
        Path main = write(dir, "main.dle", HEAD + "@import \"vocab.dle\"\nA ⊑ B\n");
        OWLOntology o = parseFile(main);

        File out = dir.resolve("elsewhere/out.dle").toFile();
        Files.createDirectories(out.getParentFile().toPath());
        // Saved by IRI, which is the overload owltx uses — and the one that carries the
        // document location, so it is the path that must be characterised here.
        o.getOWLOntologyManager().saveOntology(
            o, new DLESyntaxDocumentFormat(), IRI.create(out));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(written.contains("@import <file:"),
            () -> "a relative path would be wrong from here:\n" + written);
    }
}
