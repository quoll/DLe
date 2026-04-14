package org.semanticweb.owlapi.formats;

/**
 * Document format for the extended DL syntax, which adds annotation support
 * and other constructs beyond the standard DL syntax.
 *
 * <p>Extends {@link PrefixDocumentFormatImpl} so that prefix declarations
 * emitted by the renderer and parsed by the parser are preserved on the
 * format object, enabling correct IRI expansion in round-trips.
 */
public class DLESyntaxDocumentFormat extends PrefixDocumentFormatImpl {

    /** Creates a new instance of this document format. */
    public DLESyntaxDocumentFormat() {}

    @Override
    public String getKey() {
        return "DLE Syntax Format";
    }
}
