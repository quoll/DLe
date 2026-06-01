package org.semanticweb.owlapi.dlesyntax.protege;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.semanticweb.owlapi.dlesyntax.DLEOntologyParserFactory;
import org.semanticweb.owlapi.dlesyntax.DLESyntaxStorerFactory;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormatFactory;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormatFactory;
import org.semanticweb.owlapi.model.OWLStorerFactory;

/**
 * OSGi bundle activator that registers the DLe parser, storer, and document
 * format factories as OSGi services so that Protégé's OWLAPI bundle can
 * discover them at runtime.
 */
public class DLEBundleActivator implements BundleActivator {

    private ServiceRegistration<OWLParserFactory> parserReg;
    private ServiceRegistration<OWLStorerFactory> storerReg;
    private ServiceRegistration<OWLDocumentFormatFactory> formatReg;

    @Override
    public void start(BundleContext ctx) {
        parserReg = ctx.registerService(OWLParserFactory.class,
                new DLEOntologyParserFactory(), null);
        storerReg = ctx.registerService(OWLStorerFactory.class,
                new DLESyntaxStorerFactory(), null);
        formatReg = ctx.registerService(OWLDocumentFormatFactory.class,
                new DLESyntaxDocumentFormatFactory(), null);
    }

    @Override
    public void stop(BundleContext ctx) {
        if (parserReg != null) { parserReg.unregister(); parserReg = null; }
        if (storerReg != null) { storerReg.unregister(); storerReg = null; }
        if (formatReg != null) { formatReg.unregister(); formatReg = null; }
    }
}
