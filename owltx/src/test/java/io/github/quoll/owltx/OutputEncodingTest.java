package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What gets written to a file must be UTF-8, whatever the JVM's default charset is.
 *
 * <p>DLe is made of non-ASCII operators, and {@code .ofn} and {@code .ttl} both mandate
 * UTF-8, so this is not a preference. It is asserted on the bytes rather than on a string,
 * because reading the file back with the same wrong charset would hide the problem.
 *
 * <p>DLe is UTF-8 only, as every W3C serialisation is: the syntax is built from Unicode
 * operators, so no other encoding could express it. That makes this a hard rule rather than
 * a preference.
 *
 * <p>Two guards, because one is not enough. {@link #aWrittenFileIsUtf8} asserts the bytes
 * of {@link Main#writeToFile} — the real write path; a first version called
 * {@code saveOntology(o, format, IRI)} from the test itself, which is OWL API's own
 * always-UTF-8 code, so it passed no matter what owltx did. And
 * {@link #theWritePathNeverUsesADefaultCharsetWriter} checks structurally that no
 * default-charset writer is referenced at all, because a byte assertion can only catch that
 * on a machine whose default charset is wrong — which is never the machine you are on. An
 * earlier attempt ran the byte test again under {@code -Dfile.encoding=US-ASCII}; JEP 400
 * guarantees only {@code UTF-8} and {@code COMPAT}, so that fork could be silently
 * neutralised, and US-ASCII is not a configuration DLe could ever support anyway.
 */
class OutputEncodingTest {

    private static final String NS = "http://example.org/t#";

    private OWLOntology ontologyWithOperators() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "Truck")), df.getOWLClass(IRI.create(NS + "Vehicle"))));
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getRDFSLabel(), IRI.create(NS + "Truck"), df.getOWLLiteral("Café intérieur")));
        return o;
    }

    /** The bytes of a written file must be UTF-8 regardless of the platform default. */
    @Test
    void aWrittenFileIsUtf8(@TempDir Path dir) throws Exception {
        OWLOntology o = ontologyWithOperators();
        Path out = dir.resolve("out.dle");
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        Main.writeToFile(o.getOWLOntologyManager(), o, format, out.toString());

        byte[] written = Files.readAllBytes(out);
        String asUtf8 = new String(written, StandardCharsets.UTF_8);
        assertTrue(asUtf8.contains("Truck ⊑ Vehicle"),
            () -> "the subsumption operator must survive as UTF-8:\n" + asUtf8);
        assertTrue(asUtf8.contains("Café intérieur"), "and so must accented text");

        // The operator's UTF-8 encoding, present literally in the bytes. A default-charset
        // writer would have emitted '?' (0x3F) instead.
        byte[] sqsubseteq = "⊑".getBytes(StandardCharsets.UTF_8);
        assertTrue(indexOf(written, sqsubseteq) >= 0,
            "⊑ must be encoded as UTF-8 bytes, not replaced");
        assertFalse(asUtf8.contains("?"), () -> "a '?' means a charset replaced a character:\n" + asUtf8);
    }

    /** And the file must read back as the same ontology. */
    @Test
    void aWrittenFileReadsBackIdentically(@TempDir Path dir) throws Exception {
        OWLOntology o = ontologyWithOperators();
        Path out = dir.resolve("out.dle");
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        Main.writeToFile(o.getOWLOntologyManager(), o, format, out.toString());

        OWLOntologyManager back = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = back.loadOntologyFromOntologyDocument(out.toFile());
        assertEquals(o.getLogicalAxioms(), reloaded.getLogicalAxioms());
    }

    /**
     * A mistyped output path must fail, not create a directory tree.
     *
     * <p>Saving by IRI calls {@code mkdirs()} inside OWL API, so without this guard
     * {@code owltx in.dle /nonexistant/out.dle} silently succeeded.
     */
    @Test
    void aMissingParentDirectoryIsRefused(@TempDir Path dir) throws Exception {
        OWLOntology o = ontologyWithOperators();
        Path nested = dir.resolve("no-such-dir/out.dle");
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        assertThrows(Exception.class, () -> Main.writeToFile(
            o.getOWLOntologyManager(), o, format, nested.toString()));
        assertFalse(Files.exists(nested.getParent()),
            "and it must not have created the directory");
    }

    /**
     * No default-charset writer may appear in the write path.
     *
     * <p>This is the guard that holds on every machine. {@code FileDocumentTarget} reaches
     * the file through {@code new FileWriter(file)} and {@code PrintWriter(File)} does the
     * same; both encode with {@code Charset.defaultCharset()}, which is correct here and
     * wrong under {@code LANG=C} or on Windows. Checking the compiled reference rather than
     * the resulting bytes means the test cannot be fooled by the machine it runs on.
     */
    @Test
    void theWritePathNeverUsesADefaultCharsetWriter() throws Exception {
        byte[] bytecode = Files.readAllBytes(
            Path.of(Main.class.getResource("Main.class").toURI()));
        String constants = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String forbidden : new String[] {
                "org/semanticweb/owlapi/io/FileDocumentTarget",
                "java/io/FileWriter",
                "java/io/PrintWriter"}) {
            assertFalse(constants.contains(forbidden),
                () -> forbidden + " encodes with the platform default charset; DLe is UTF-8"
                    + " only, so the write path must not reference it");
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
