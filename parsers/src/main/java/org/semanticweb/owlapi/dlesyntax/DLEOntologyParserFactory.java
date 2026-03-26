package org.semanticweb.owlapi.dlesyntax;

import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormatFactory;
import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormatFactory;

/** Factory that creates {@link DLEOntologyParser} instances. */
public class DLEOntologyParserFactory implements OWLParserFactory {

    private static final long serialVersionUID = 1L;

    @Override
    public OWLParser createParser() {
        return new DLEOntologyParser();
    }

    @Override
    public OWLParser get() {
        return createParser();
    }

    @Override
    public OWLDocumentFormatFactory getSupportedFormat() {
        return new DLESyntaxDocumentFormatFactory();
    }

    @Override
    public String getDefaultMIMEType() {
        return "text/dle";
    }
}
