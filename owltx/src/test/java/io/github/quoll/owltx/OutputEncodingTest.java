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
 * <p>It guards {@link Main#writeToFile}, the real write path, and that is the point. A
 * first version called {@code saveOntology(o, format, IRI)} from the test itself — OWL API's
 * own code, which hardcodes UTF-8 — so it passed regardless of what owltx did, and a
 * deliberately broken owltx still went green. Asserting the right property against the
 * wrong code is no guard at all.
 *
 * <p>What it guards against: {@code FileDocumentTarget}'s writer is
 * {@code new FileWriter(file)}, which encodes with {@code Charset.defaultCharset()}, so
 * wherever that is not UTF-8 every DLe operator becomes a question mark. That was never
 * released — it existed only in an intermediate version of the import change — but the
 * target remains available and inviting, so the guard stays.
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
