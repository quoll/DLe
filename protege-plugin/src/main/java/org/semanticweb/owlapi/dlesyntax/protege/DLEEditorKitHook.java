package org.semanticweb.owlapi.dlesyntax.protege;

import org.protege.editor.core.editorkit.plugin.EditorKitHook;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.ui.UIHelper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.dlesyntax.DLEOntologyParserFactory;
import org.semanticweb.owlapi.dlesyntax.DLESyntaxStorerFactory;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.utilities.Injector;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DLEEditorKitHook extends EditorKitHook {

    private static final Logger LOGGER = Logger.getLogger(DLEEditorKitHook.class.getName());
    private static final String DLE_EXTENSION = ".dle";

    private DLEOntologyParserFactory parserFactory;
    private DLESyntaxStorerFactory storerFactory;
    private AWTEventListener formatPanelInjector;
    private Set<String> originalExtensions;

    @Override
    public void initialise() throws Exception {
        OWLOntologyManager manager = owlManager();
        parserFactory = new DLEOntologyParserFactory();
        storerFactory = new DLESyntaxStorerFactory();
        manager.getOntologyParsers().add(parserFactory);
        manager.addOntologyStorer(storerFactory);
        registerWithOwlApiInjectors();
        injectFileExtension();
        installFormatPanelInjector();
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
        if (formatPanelInjector != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(formatPanelInjector);
            formatPanelInjector = null;
        }
        restoreFileExtension();
    }

    /**
     * Registers our parser factory with OWLManager's static Injectors.
     *
     * <p>Protégé's OntologyLoader.createInterceptingManager() creates a brand-new
     * OWLOntologyManager (via OWLManager.createConcurrentOWLOntologyManager()) for
     * each file load, copying parsers from that fresh manager rather than from the
     * main Protégé manager.  The fresh manager's parser list is built by the Injector,
     * so registering here ensures our parser is included in every load.
     */
    private void registerWithOwlApiInjectors() {
        for (String fieldName : new String[] { "concurrentInjector", "normalInjector" }) {
            try {
                Field f = OWLManager.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Injector injector = (Injector) f.get(null);
                injector.bindOneMore(parserFactory, OWLParserFactory.class);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Could not register DLe parser in OWLManager." + fieldName, e);
            }
        }
    }

    /**
     * Replaces UIHelper.OWL_EXTENSIONS with a new set that also contains ".dle",
     * so that the macOS file-open dialog accepts .dle files.
     */
    @SuppressWarnings("unchecked")
    private void injectFileExtension() {
        try {
            Field field = UIHelper.class.getDeclaredField("OWL_EXTENSIONS");
            field.setAccessible(true);
            Set<String> current = (Set<String>) field.get(null);
            if (current.contains(DLE_EXTENSION)) {
                return;
            }
            originalExtensions = current;
            Set<String> extended = new HashSet<>(current);
            extended.add(DLE_EXTENSION);
            setStaticField(field, Collections.unmodifiableSet(extended));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not inject .dle into UIHelper.OWL_EXTENSIONS", e);
        }
    }

    private void restoreFileExtension() {
        if (originalExtensions == null) return;
        try {
            Field field = UIHelper.class.getDeclaredField("OWL_EXTENSIONS");
            field.setAccessible(true);
            setStaticField(field, originalExtensions);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not restore UIHelper.OWL_EXTENSIONS", e);
        }
        originalExtensions = null;
    }

    /**
     * Sets a static field value. Tries plain reflection first; if the field is
     * final and Java blocks the assignment, falls back to sun.misc.Unsafe.
     */
    private static void setStaticField(Field field, Object value) throws Exception {
        try {
            field.set(null, value);
        } catch (IllegalAccessException e) {
            // Java 12+ does not allow Field.set() on static final fields even with
            // setAccessible(true). Use Unsafe to bypass the restriction.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field uf = unsafeClass.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class)
                    .invoke(unsafe, field);
            long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class)
                    .invoke(unsafe, field);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, value);
        }
    }

    /**
     * Installs an AWT-level event listener that fires whenever a new window opens.
     * For each new window, it scans the component tree for a JComboBox whose items
     * are OWLDocumentFormat instances (i.e. OntologyFormatPanel's format selector)
     * and injects a DLESyntaxDocumentFormat entry if one is not already present.
     */
    private void installFormatPanelInjector() {
        formatPanelInjector = event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED) {
                Window window = ((WindowEvent) event).getWindow();
                // invokeLater ensures the window's component tree is fully laid out
                // before we walk it, even when called from within the WINDOW_OPENED event.
                SwingUtilities.invokeLater(() -> injectIntoCombos(window));
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(
                formatPanelInjector, AWTEvent.WINDOW_EVENT_MASK);
    }

    /**
     * Recursively walks the component tree rooted at {@code container} and adds
     * a {@link DLESyntaxDocumentFormat} to any {@link JComboBox} whose items are
     * {@link OWLDocumentFormat} instances and that does not already contain one.
     */
    @SuppressWarnings("unchecked")
    private void injectIntoCombos(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JComboBox) {
                JComboBox<?> box = (JComboBox<?>) comp;
                if (box.getItemCount() > 0 && box.getItemAt(0) instanceof OWLDocumentFormat) {
                    boolean hasDle = false;
                    for (int i = 0; i < box.getItemCount(); i++) {
                        if (box.getItemAt(i) instanceof DLESyntaxDocumentFormat) {
                            hasDle = true;
                            break;
                        }
                    }
                    if (!hasDle) {
                        DefaultComboBoxModel<OWLDocumentFormat> model = new DefaultComboBoxModel<>();
                        for (int i = 0; i < box.getItemCount(); i++) {
                            model.addElement((OWLDocumentFormat) box.getItemAt(i));
                        }
                        model.addElement(new DLESyntaxDocumentFormat());
                        ((JComboBox<OWLDocumentFormat>) box).setModel(model);
                    }
                }
            }
            if (comp instanceof Container) {
                injectIntoCombos((Container) comp);
            }
        }
    }

    private OWLOntologyManager owlManager() {
        return ((OWLEditorKit) getEditorKit()).getOWLModelManager().getOWLOntologyManager();
    }
}
