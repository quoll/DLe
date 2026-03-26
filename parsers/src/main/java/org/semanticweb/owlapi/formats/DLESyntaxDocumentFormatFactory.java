package org.semanticweb.owlapi.formats;

import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.util.OWLDocumentFormatFactoryImpl;

/**
 * Factory for {@link DLESyntaxDocumentFormat}.
 */
public class DLESyntaxDocumentFormatFactory extends OWLDocumentFormatFactoryImpl {

    @Override
    public String getKey() {
        return "DLE Syntax Format";
    }

    @Override
    public OWLDocumentFormat createFormat() {
        return new DLESyntaxDocumentFormat();
    }
}
