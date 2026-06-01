package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WildlifeReserveIntegrationTest {

    @Test
    void wildlifeReserveDleFile_parsesSuccessfully() throws Exception {
        URL resource = getClass().getClassLoader().getResource("data/wildlife-reserve-test.dle");
        assertNotNull(resource, "Test resource data/wildlife-reserve-test.dle not found on classpath");
        File file = new File(resource.toURI());

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();

        OWLDocumentFormat format = parser.parse(
            new FileDocumentSource(file),
            ontology,
            manager.getOntologyLoaderConfiguration());

        assertNotNull(format, "Parser must return a document format");
        assertInstanceOf(DLESyntaxDocumentFormat.class, format,
            "Returned format must be DLESyntaxDocumentFormat");
        assertFalse(ontology.isEmpty(), "Parsed ontology must not be empty");
    }

    @Test
    void ofnToDleRoundTrip_preservesLogicalAxioms() throws Exception {
        URL resource = getClass().getClassLoader().getResource("data/wildlife-reserve-test.ofn");
        assertNotNull(resource, "Test resource data/wildlife-reserve-test.ofn not found on classpath");
        File file = new File(resource.toURI());

        // Load original OFN
        OWLOntologyManager manager1 = OWLManager.createOWLOntologyManager();
        manager1.getOntologyStorers().add(new DLESyntaxStorerFactory());
        OWLOntology original = manager1.loadOntologyFromOntologyDocument(file);
        Set<OWLLogicalAxiom> originalAxioms = original.getLogicalAxioms();
        assertFalse(originalAxioms.isEmpty(), "Original ontology must contain logical axioms");

        // Serialize to DLe in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        manager1.saveOntology(original, new DLESyntaxDocumentFormat(), new StreamDocumentTarget(baos));

        // Reload from the in-memory DLe buffer
        OWLOntologyManager manager2 = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = manager2.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();
        parser.parse(
            new StreamDocumentSource(new ByteArrayInputStream(baos.toByteArray())),
            reloaded,
            manager2.getOntologyLoaderConfiguration());

        Set<OWLLogicalAxiom> reloadedAxioms = reloaded.getLogicalAxioms();
        assertFalse(reloadedAxioms.isEmpty(), "Reloaded ontology must contain logical axioms");
        assertEquals(originalAxioms, reloadedAxioms,
            "Round-tripped ontology must have the same logical axioms as the original");
    }
}
