package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.File;
import java.net.URL;

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
}
