package org.semanticweb.owlapi.dlesyntax;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
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
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.DefaultPrefixManager;

/**
 * Extends {@link DLSyntaxStorerBase}, substituting {@link DLESyntaxObjectRenderer}
 * so that annotations and other extended constructs are rendered correctly.
 */
public abstract class DLESyntaxStorerBase extends DLSyntaxStorerBase {

    private final DLESyntaxObjectRenderer renderer = new DLESyntaxObjectRenderer();

    // Per-storeOntology state: tracks which annotation assertions have already been
    // written inline (next to their subject entity) so endWritingOntology can skip them.
    @Nullable private OWLOntology currentOntology;
    @Nullable private Set<OWLAnnotationAssertionAxiom> writtenAnnotations;
    @Nullable private PrefixDocumentFormat currentPrefixFormat;
    // Set to true when getRendering returns "" so endWritingAxiom suppresses the blank line.
    private boolean lastRenderingEmpty = false;

    @Override
    protected void storeOntology(OWLOntology o, PrintWriter printWriter, OWLDocumentFormat outputFormat) {
        // The output format is our own DLESyntaxDocumentFormat which carries no
        // prefixes, so we look at the format the ontology was loaded from instead.
        renderer.setOntology(o);
        currentOntology = o;
        writtenAnnotations = new HashSet<>();
        OWLDocumentFormat sourceFormat = o.getFormat() != null ? o.getFormat() : outputFormat;
        if (sourceFormat instanceof PrefixDocumentFormat) {
            currentPrefixFormat = (PrefixDocumentFormat) sourceFormat;
            DefaultPrefixManager pm = new DefaultPrefixManager();
            currentPrefixFormat.getPrefixName2PrefixMap().forEach(pm::setPrefix);
            renderer.setPrefixManager(pm);
        }
        try {
            super.storeOntology(o, printWriter, outputFormat);
        } finally {
            currentOntology = null;
            writtenAnnotations = null;
            currentPrefixFormat = null;
        }
    }

    @Override
    protected void beginWritingAxioms(OWLEntity entity, PrintWriter writer) {
        // Suppress internal dle: entities — their labels are embedded inline in expressions.
        if (entity.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            // Mark their annotations as written to prevent re-emission later.
            if (currentOntology != null && writtenAnnotations != null) {
                currentOntology.annotationAssertionAxioms(entity.getIRI())
                    .forEach(writtenAnnotations::add);
            }
            return;
        }
        // Emit annotations for this entity inline, before its logical axioms.
        if (currentOntology == null || writtenAnnotations == null) return;
        currentOntology.annotationAssertionAxioms(entity.getIRI()).sorted().forEach(ax -> {
            if (writtenAnnotations.add(ax)) {
                beginWritingAxiom(writer);
                writeAxiom(null, ax, writer);
                endWritingAxiom(writer);
            }
        });
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
        Set<OWLAnnotationAssertionAxiom> already = writtenAnnotations != null
            ? writtenAnnotations : new HashSet<>();
        ontology.axioms(AxiomType.ANNOTATION_ASSERTION).sorted()
            .filter(ax -> !already.contains(ax))
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
        writer.println();

        // Emit ontology/version/import declarations if present.
        OWLOntologyID id = ontology.getOntologyID();
        if (id.getOntologyIRI().isPresent()) {
            writer.println("@ontology <" + id.getOntologyIRI().get() + ">");
            if (id.getVersionIRI().isPresent()) {
                writer.println("@version <" + id.getVersionIRI().get() + ">");
            }
            writer.println();
        }
        ontology.importsDeclarations().sorted().forEach(decl ->
            writer.println("@import <" + decl.getIRI() + ">"));
        if (ontology.importsDeclarations().findAny().isPresent()) {
            writer.println();
        }

        // Emit prefix declarations so the file is self-contained for parsing.
        if (currentPrefixFormat != null) {
            currentPrefixFormat.getPrefixName2PrefixMap().forEach((prefix, iri) ->
                writer.println("@prefix " + prefix + " <" + iri + ">"));
            writer.println();
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
        // Suppress declarations for dle: internal classes (they only appear via their labels).
        if (axiom instanceof OWLDeclarationAxiom) {
            OWLEntity e = ((OWLDeclarationAxiom) axiom).getEntity();
            if (e.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
                lastRenderingEmpty = true;
                return "";
            }
        }
        lastRenderingEmpty = false;
        return renderer.render(axiom);
    }
}
