package org.semanticweb.owlapi.dlesyntax.protege;

import org.protege.editor.core.editorkit.plugin.EditorKitHook;
import org.protege.editor.owl.OWLEditorKit;
import org.semanticweb.owlapi.dlesyntax.DLEOntologyParserFactory;
import org.semanticweb.owlapi.dlesyntax.DLESyntaxStorerFactory;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Protégé EditorKitHook that registers the DLe parser and storer factories
 * directly with the active {@link OWLOntologyManager}.
 *
 * <p>This complements the OSGi service registration in {@link DLEBundleActivator}
 * and ensures the factories are available even if OWLAPI's OSGi service tracker
 * has already completed its discovery phase before our bundle starts.
 */
public class DLEEditorKitHook extends EditorKitHook {

    private DLEOntologyParserFactory parserFactory;
    private DLESyntaxStorerFactory storerFactory;

    @Override
    public void initialise() throws Exception {
        OWLOntologyManager manager = owlManager();
        parserFactory = new DLEOntologyParserFactory();
        storerFactory = new DLESyntaxStorerFactory();
        manager.getOntologyParsers().add(parserFactory);
        manager.addOntologyStorer(storerFactory);
    }

    @Override
    public void dispose() {
        OWLOntologyManager manager = owlManager();
        if (parserFactory != null) {
            manager.getOntologyParsers().remove(parserFactory);
            parserFactory = null;
        }
        if (storerFactory != null) {
            manager.removeOntologyStorer(storerFactory);
            storerFactory = null;
        }
    }

    private OWLOntologyManager owlManager() {
        return ((OWLEditorKit) getEditorKit()).getOWLModelManager().getOWLOntologyManager();
    }
}
