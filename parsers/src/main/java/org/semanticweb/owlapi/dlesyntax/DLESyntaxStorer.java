package org.semanticweb.owlapi.dlesyntax;

import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

/**
 * Storer for the extended DL syntax format.
 */
public class DLESyntaxStorer extends DLESyntaxStorerBase {

    /** Creates a new storer for the extended DL syntax format. */
    public DLESyntaxStorer() {}

    @Override
    public boolean canStoreOntology(OWLDocumentFormat ontologyFormat) {
        return ontologyFormat instanceof DLESyntaxDocumentFormat;
    }
}
