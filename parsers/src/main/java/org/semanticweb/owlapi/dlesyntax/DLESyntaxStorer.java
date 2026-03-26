package org.semanticweb.owlapi.dlesyntax;

import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

/**
 * Storer for the extended DL syntax format.
 */
public class DLESyntaxStorer extends DLESyntaxStorerBase {

    @Override
    public boolean canStoreOntology(OWLDocumentFormat ontologyFormat) {
        return ontologyFormat instanceof DLESyntaxDocumentFormat;
    }
}
