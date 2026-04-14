package org.semanticweb.owlapi.dlesyntax;

import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormatFactory;
import org.semanticweb.owlapi.model.OWLStorer;
import org.semanticweb.owlapi.util.OWLStorerFactoryImpl;

/**
 * Factory for {@link DLESyntaxStorer}.
 */
public class DLESyntaxStorerFactory extends OWLStorerFactoryImpl {

    /** Creates a new factory backed by a {@link DLESyntaxDocumentFormatFactory}. */
    public DLESyntaxStorerFactory() {
        super(new DLESyntaxDocumentFormatFactory());
    }

    @Override
    public OWLStorer createStorer() {
        return new DLESyntaxStorer();
    }
}
