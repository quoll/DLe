package org.semanticweb.owlapi.dlesyntax;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxStorerBase;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.io.OWLOntologyDocumentTarget;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.DefaultPrefixManager;

/**
 * Extends {@link DLSyntaxStorerBase}, substituting {@link DLESyntaxObjectRenderer}
 * so that annotations and other extended constructs are rendered correctly.
 */
public abstract class DLESyntaxStorerBase extends DLSyntaxStorerBase {

    /** Creates a new base storer instance. */
    protected DLESyntaxStorerBase() {}

    /** Prefix/namespace pairs that are implicit in every DLE file and need not be declared. */
    static final Map<String, String> DLE_DEFAULT_PREFIXES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(":",     "http://quoll.github.io/DLe/ontology#");
        m.put("dle:",  "http://quoll.github.io/DLe/vocab#");
        m.put("owl:",  "http://www.w3.org/2002/07/owl#");
        m.put("rdf:",  "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        m.put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        m.put("xsd:",  "http://www.w3.org/2001/XMLSchema#");
        m.put("xml:",  "http://www.w3.org/XML/1998/namespace");
        DLE_DEFAULT_PREFIXES = Collections.unmodifiableMap(m);
    }

    /** Renderer used to produce DLE syntax strings for axioms. */
    private final DLESyntaxObjectRenderer renderer = new DLESyntaxObjectRenderer();

    /**
     * Where this document is being written, when that is known.
     *
     * <p>Only needed to write an import back the way it came. A relative reference is
     * resolved to an absolute IRI on the way in — it has to be, because a relative path
     * cannot be carried inside a {@code file:} IRI — and writing that absolute path back
     * would put a machine-specific location into a document that is likely under version
     * control. With the target known it can be made relative again.
     */
    @Nullable
    private IRI targetDocumentIRI;

    @Override
    public void storeOntology(OWLOntology o, IRI documentIRI, OWLDocumentFormat format)
            throws OWLOntologyStorageException {
        targetDocumentIRI = documentIRI;
        try {
            super.storeOntology(o, documentIRI, format);
        } finally {
            targetDocumentIRI = null;
        }
    }

    @Override
    public void storeOntology(OWLOntology o, OWLOntologyDocumentTarget target,
                              OWLDocumentFormat format)
            throws OWLOntologyStorageException {
        targetDocumentIRI = target.getDocumentIRI().orElse(null);
        try {
            super.storeOntology(o, target, format);
        } finally {
            targetDocumentIRI = null;
        }
    }

    /**
     * Renders an import target: a quoted relative path when it sits beside this document or
     * below it, and an absolute IRI otherwise.
     *
     * <p>Relativised only downward. {@link java.net.URI#relativize} declines to produce
     * {@code ../} chains, which is the behaviour wanted here — a path that climbs out of the
     * document's own directory is more fragile than an absolute one.
     */
    /** Escapes what the STRING token treats as special, so the value reads back unchanged. */
    private static String escapeForString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String renderImport(IRI importIRI) {
        if (targetDocumentIRI != null) {
            try {
                java.net.URI target = new java.net.URI(targetDocumentIRI.toString());
                java.net.URI imported = new java.net.URI(importIRI.toString());
                if ("file".equals(target.getScheme()) && "file".equals(imported.getScheme())
                        && !target.isOpaque() && !imported.isOpaque()) {
                    java.net.URI relative = target.resolve(".").relativize(imported);
                    if (!relative.isAbsolute()) {
                        // The decoded path, not the URI's text: relativize hands back
                        // percent-encoding, so a file named "my vocab.dle" would be written
                        // as "my%20vocab.dle" — legal, and reloadable, but not the spelling
                        // it came in as, which is the point of keeping the relative form.
                        String path = relative.getPath();
                        if (path == null || path.isEmpty()) path = relative.toString();
                        return "\"" + escapeForString(path) + "\"";
                    }
                }
            } catch (java.net.URISyntaxException e) {
                // fall through to the absolute form
            }
        }
        return "<" + importIRI + ">";
    }

    /** Current ontology being stored; set for the duration of {@code storeOntology}. */
    @Nullable private OWLOntology currentOntology;
    /** Annotation assertions already written inline; set for the duration of {@code storeOntology}. */
    @Nullable private Set<OWLAnnotationAssertionAxiom> writtenAnnotations;
    /** Prefixes in force while storing: the ontology's, overridden by the caller's. */
    @Nullable private Map<String, String> currentPrefixes;
    /** Set to true when {@code getRendering} returns {@code ""} so {@code endWritingAxiom} suppresses the blank line. */
    private boolean lastRenderingEmpty = false;
    /** Set to true whenever an axiom renders to non-empty content for the current entity;
     *  {@code endWritingAxioms} emits a blank-line separator only when this is true. */
    private boolean entityHadContent = false;

    @Override
    protected void storeOntology(OWLOntology o, PrintWriter printWriter, OWLDocumentFormat outputFormat) {
        renderer.setOntology(o);
        currentOntology = o;
        writtenAnnotations = new HashSet<>();
        currentPrefixes = prefixesFor(o, outputFormat);
        if (!currentPrefixes.isEmpty()) {
            DefaultPrefixManager pm = new DefaultPrefixManager();
            currentPrefixes.forEach(pm::setPrefix);
            renderer.setPrefixManager(pm);
        }
        try {
            super.storeOntology(o, printWriter, outputFormat);
        } finally {
            currentOntology = null;
            writtenAnnotations = null;
            currentPrefixes = null;
        }
    }

    /**
     * Writes a subject's {@code dle:comment} annotations as {@code #} lines.
     *
     * <p>Shared by the per-entity path and the predicate-definition path, because a
     * predicate IRI is not an OWL entity: it never appears in the signature, so
     * {@code beginWritingAxioms} is never called for it and its comments would
     * otherwise be dropped. Returns whether anything was written.
     *
     * @return true if at least one comment line was emitted
     */
    private boolean writeComments(IRI subject, OWLOntology ontology, PrintWriter writer) {
        if (writtenAnnotations == null) return false;
        boolean[] wrote = {false};
        // Each annotation may hold a multi-line block, joined with \n by the parser.
        ontology.annotationAssertionAxioms(subject)
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .sorted()
            .forEach(ax -> {
                if (writtenAnnotations.add(ax) && ax.getValue() instanceof OWLLiteral) {
                    for (String line : ((OWLLiteral) ax.getValue()).getLiteral().split("\n", -1)) {
                        writer.println("# " + line);
                    }
                    wrote[0] = true;
                }
            });
        return wrote[0];
    }

    /**
     * The prefixes to write with, merged from the two places they can come from:
     * the format passed to {@code saveOntology} and the format the ontology was
     * loaded from.
     *
     * <p>Both matter. The usual call is
     * {@code saveOntology(o, new DLESyntaxDocumentFormat(), t)}, where the
     * ontology's own format is the only place document prefixes exist. But a
     * caller who invokes the parser directly gets a populated format back and may
     * hand it straight to {@code saveOntology}, and preferring the ontology's
     * format outright discarded it — dropping every {@code @prefix} line and
     * re-resolving bare names to the DLe default namespace on reload.
     *
     * <p>Neither source can simply win, because neither is ever empty: a fresh
     * {@code DLESyntaxDocumentFormat} already carries {@code owl:}, {@code rdf:},
     * {@code rdfs:}, {@code xsd:} and {@code xml:} from
     * {@code PrefixDocumentFormatImpl}. Letting it win would silently reset a
     * document that redeclares one of those, and the renderer would then write the
     * affected names bare, to be re-resolved against a different namespace on
     * reload. Letting the ontology win loses the same thing in the other
     * direction.
     *
     * <p>So a declaration that merely repeats a DLe default never displaces one
     * that says something, and where both say something the ontology's wins,
     * because that is the namespace its entities actually live in.
     */
    private static Map<String, String> prefixesFor(OWLOntology o, OWLDocumentFormat outputFormat) {
        Map<String, String> merged = new LinkedHashMap<>();
        contribute(merged, outputFormat);
        contribute(merged, o.getFormat());
        return merged;
    }

    /** Adds a format's prefixes, without letting a default value displace a real one. */
    private static void contribute(Map<String, String> merged,
                                   @Nullable OWLDocumentFormat format) {
        if (!(format instanceof PrefixDocumentFormat)) return;
        ((PrefixDocumentFormat) format).getPrefixName2PrefixMap().forEach((prefix, iri) -> {
            if (isDleDefault(prefix, iri)
                    && merged.containsKey(prefix)
                    && !isDleDefault(prefix, merged.get(prefix))) {
                return;
            }
            merged.put(prefix, iri);
        });
    }

    /** Whether a declaration says no more than DLe already assumes. */
    private static boolean isDleDefault(String prefix, String iri) {
        return iri.equals(DLE_DEFAULT_PREFIXES.get(prefix));
    }

    @Override
    protected void beginWritingAxioms(OWLEntity entity, PrintWriter writer) {
        entityHadContent = false;
        // Suppress internal dle: entities — their labels are embedded inline in expressions.
        if (entity.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            // Mark their annotations as written to prevent re-emission later.
            if (currentOntology != null && writtenAnnotations != null) {
                currentOntology.annotationAssertionAxioms(entity.getIRI())
                    .forEach(writtenAnnotations::add);
            }
            return;
        }
        if (currentOntology == null || writtenAnnotations == null) return;

        // Emit dle:comment annotations as # lines before the entity's logical axioms.
        if (writeComments(entity.getIRI(), currentOntology, writer)) {
            entityHadContent = true;
        }

        // Emit dle:inlineComment annotations as # lines (same treatment as dle:comment).
        currentOntology.annotationAssertionAxioms(entity.getIRI())
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .sorted()
            .forEach(ax -> {
                if (writtenAnnotations.add(ax) && ax.getValue() instanceof OWLLiteral) {
                    writer.println("# " + ((OWLLiteral) ax.getValue()).getLiteral());
                    entityHadContent = true;
                }
            });

        // Emit all other annotations for this entity inline, before its logical axioms.
        currentOntology.annotationAssertionAxioms(entity.getIRI()).sorted().forEach(ax -> {
            if (writtenAnnotations.add(ax)) {
                beginWritingAxiom(writer);
                writeAxiom(null, ax, writer);
                endWritingAxiom(writer);
            }
        });
    }

    @Override
    protected void endWritingAxioms(PrintWriter writer) {
        if (entityHadContent) {
            writer.println();
            entityHadContent = false;
        }
    }

    @Override
    protected void endWritingAxiom(PrintWriter writer) {
        if (!lastRenderingEmpty) {
            writer.println();
        }
        lastRenderingEmpty = false;
    }

    @Override
    protected void endWritingOntology(OWLOntology ontology, PrintWriter writer) {
        // Write any annotation assertions whose subject was not a named entity
        // in the ontology signature (e.g. annotations on external IRIs, blank nodes).
        // dle:comment annotations are internal and are never emitted standalone.
        Set<OWLAnnotationAssertionAxiom> already = writtenAnnotations != null
            ? writtenAnnotations : new HashSet<>();
        ontology.axioms(AxiomType.ANNOTATION_ASSERTION).sorted()
            .filter(ax -> !already.contains(ax))
            .filter(ax -> !DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI())
                       && !DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .forEach(ax -> {
                beginWritingAxiom(writer);
                writeAxiom(null, ax, writer);
                endWritingAxiom(writer);
            });
    }

    @Override
    protected void beginWritingOntology(OWLOntology ontology, PrintWriter writer) {
        String resource = "/org/semanticweb/owlapi/dlesyntax/dle-syntax-header.txt";
        try (InputStream in = DLESyntaxStorerBase.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("DLE syntax header resource not found: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read DLE syntax header resource: " + e.getMessage(), e);
        }
        // The header resource already ends with a blank line; no extra println() needed.

        // Emit ontology/version/import declarations if present.
        //
        // The version is emitted independently of the ontology IRI. It used to be nested
        // inside it, so a document declaring @version and no @ontology had its version
        // written nowhere: the reader gives such a document the default IRI — a version IRI
        // cannot be held without one — and that default is exactly what the test below
        // suppresses.
        OWLOntologyID id = ontology.getOntologyID();
        boolean named = id.getOntologyIRI().isPresent()
            && !DLESyntaxAxiomVisitor.DLE_DEFAULT_ONTOLOGY_IRI.equals(id.getOntologyIRI().get());
        if (named) {
            writer.println("@ontology <" + id.getOntologyIRI().get() + ">");
        }
        if (id.getVersionIRI().isPresent()) {
            writer.println("@version <" + id.getVersionIRI().get() + ">");
        }
        if (named || id.getVersionIRI().isPresent()) {
            writer.println();
        }
        ontology.importsDeclarations().sorted().forEach(decl ->
            writer.println("@import " + renderImport(decl.getIRI())));
        if (ontology.importsDeclarations().findAny().isPresent()) {
            writer.println();
        }

        // Emit prefix declarations that differ from the DLE defaults.
        // The six standard prefixes are implicit in every DLE file and need not be repeated.
        if (currentPrefixes != null) {
            long[] count = {0};
            currentPrefixes.forEach((prefix, iri) -> {
                String defaultIri = DLE_DEFAULT_PREFIXES.get(prefix);
                if (defaultIri == null || !defaultIri.equals(iri)) {
                    writer.println("@prefix " + prefix + " <" + iri + ">");
                    count[0]++;
                }
            });
            if (count[0] > 0) writer.println();
        }

        // Emit predicate definitions (rdf:value annotations with '→') near the top,
        // before logical axioms. Mark them so they are not re-emitted later.
        IRI rdfValueIRI = IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");
        if (writtenAnnotations != null) {
            long[] count = {0};
            ontology.axioms(AxiomType.ANNOTATION_ASSERTION).sorted()
                .filter(ax -> rdfValueIRI.equals(ax.getProperty().getIRI()))
                .filter(ax -> ax.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) ax.getValue()).getLiteral().contains("\u2192"))
                .forEach(ax -> {
                    if (writtenAnnotations.add(ax)) {
                        // A predicate's own comments, before its definition — the
                        // position they occupied in the source. Nothing else will
                        // emit them: a predicate IRI is not an OWL entity, so it is
                        // never passed to beginWritingAxioms.
                        if (ax.getSubject() instanceof IRI) {
                            writeComments((IRI) ax.getSubject(), ontology, writer);
                        }
                        beginWritingAxiom(writer);
                        writeAxiom(null, ax, writer);
                        endWritingAxiom(writer);
                        count[0]++;
                    }
                });
            if (count[0] > 0) writer.println();
        }
    }

    @Override
    protected String getRendering(@Nullable OWLEntity subject, OWLAxiom axiom) {
        // Declaration axioms carry no DL content and the base renderer produces ""
        // for them, which would cause a spurious blank line from endWritingAxiom.
        if (axiom instanceof OWLDeclarationAxiom) {
            lastRenderingEmpty = true;
            return "";
        }
        // Suppress all axioms in the entity block of dle: internal classes —
        // their content (EquivalentClasses, rdfs:label) is either rendered inline
        // elsewhere or is an internal implementation detail.
        if (subject != null && subject.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            lastRenderingEmpty = true;
            return "";
        }
        // dle:comment and dle:inlineComment annotations are emitted as # lines
        // in beginWritingAxioms — suppress here.
        if (axiom instanceof OWLAnnotationAssertionAxiom) {
            IRI propIRI = ((OWLAnnotationAssertionAxiom) axiom).getProperty().getIRI();
            if (DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(propIRI)
                    || DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(propIRI)) {
                lastRenderingEmpty = true;
                return "";
            }
        }
        lastRenderingEmpty = false;
        String rendered = renderer.render(axiom);
        if (rendered.isEmpty()) {
            lastRenderingEmpty = true;
        } else {
            entityHadContent = true;
        }
        return rendered;
    }
}
