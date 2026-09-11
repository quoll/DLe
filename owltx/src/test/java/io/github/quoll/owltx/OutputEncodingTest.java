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
 * <p>Writing through {@code FileDocumentTarget} broke this: its writer is
 * {@code new FileWriter(file)}, which encodes with {@code Charset.defaultCharset()}. Under
 * {@code LANG=C} or on Windows every {@code ⊑} became a question mark and the output
 * stopped being a DLe document. It passed review unnoticed because the change was verified
 * on a UTF-8 machine, where the default happens to be right.
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
        o.getOWLOntologyManager().saveOntology(o, format, IRI.create(out.toFile()));

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
        o.getOWLOntologyManager().saveOntology(o, format, IRI.create(out.toFile()));

        OWLOntologyManager back = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = back.loadOntologyFromOntologyDocument(out.toFile());
        assertEquals(o.getLogicalAxioms(), reloaded.getLogicalAxioms());
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
