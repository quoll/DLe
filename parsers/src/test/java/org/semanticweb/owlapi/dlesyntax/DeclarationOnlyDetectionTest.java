package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What counts as using a name, beyond declaring it.
 *
 * <p>The writer states a kind for an entity that appears in nothing but its own declaration,
 * because there is then nothing for the reader to classify it from. The question used to be
 * asked of the ontology once per entity through {@code referencingAxioms}, which is not
 * indexed for an IRI and therefore scanned: the writer was quadratic, 8 000 classes taking
 * 29.5 s where the reader took under two. It is now one pass per document, collecting the
 * IRIs that some axiom mentions.
 *
 * <p>These tests pin the three places an IRI is mentioned without being in an axiom's entity
 * signature, which a pass built on {@code signature()} alone would miss. Each would show up
 * as a spurious kind statement on a name the document already uses — harmless-looking, and
 * wrong.
 *
 * <p>The counter-case matters as much: an annotation assertion is not a use, so a name with
 * nothing but a label still gets its kind stated.
 */
class DeclarationOnlyDetectionTest {

    private static final String NS = "http://example.org/d#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLClass cls(String n)  { return df.getOWLClass(IRI.create(NS + n)); }
    private OWLAnnotationProperty ann(String n) {
        return df.getOWLAnnotationProperty(IRI.create(NS + n));
    }

    private String write(OWLAxiom... axioms) throws Exception {
        OWLOntology o = manager.createOntology();
        for (OWLAxiom axiom : axioms) o.getOWLOntologyManager().addAxiom(o, axiom);
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        StringBuilder body = new StringBuilder();
        for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n", -1)) {
            if (!line.trim().startsWith("#")) body.append(line).append('\n');
        }
        return body.toString();
    }

    /** A class named only as an annotation property's domain is used, not merely declared. */
    @Test
    void anAnnotationPropertyDomainCountsAsUse() throws Exception {
        String written = write(
            df.getOWLDeclarationAxiom(cls("Subject")),
            df.getOWLAnnotationPropertyDomainAxiom(ann("note"), cls("Subject").getIRI()));
        assertFalse(written.contains("Subject ⊑ ⊤"),
            () -> "the domain mentions it, so no kind need be stated:\n" + written);
    }

    /** And so is one named only as its range. */
    @Test
    void anAnnotationPropertyRangeCountsAsUse() throws Exception {
        String written = write(
            df.getOWLDeclarationAxiom(cls("Target")),
            df.getOWLAnnotationPropertyRangeAxiom(ann("note"), cls("Target").getIRI()));
        assertFalse(written.contains("Target ⊑ ⊤"),
            () -> "the range mentions it, so no kind need be stated:\n" + written);
    }

    /** An IRI carried as the value of an annotation on an axiom is a use too. */
    @Test
    void anIriValuedAxiomAnnotationCountsAsUse() throws Exception {
        Set<OWLAnnotation> annotations = Set.of(
            df.getOWLAnnotation(ann("seeAlso"), cls("Cited").getIRI()));
        String written = write(
            df.getOWLDeclarationAxiom(cls("Cited")),
            df.getOWLSubClassOfAxiom(cls("A"), cls("B"), annotations));
        assertFalse(written.contains("Cited ⊑ ⊤"),
            () -> "the axiom annotation mentions it:\n" + written);
    }

    /** A name in nothing but its declaration still gets its kind stated. */
    @Test
    void aTrulyDeclarationOnlyNameIsStated() throws Exception {
        String written = write(
            df.getOWLDeclarationAxiom(cls("Solo")),
            df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        assertTrue(written.contains("Solo ⊑ ⊤"),
            () -> "nothing classifies Solo, so the kind has to be written:\n" + written);
    }

    /** An annotation assertion is not a use: a labelled name is still declaration-only. */
    @Test
    void anAnnotationAssertionIsNotUse() throws Exception {
        String written = write(
            df.getOWLDeclarationAxiom(cls("Labelled")),
            df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                cls("Labelled").getIRI(), df.getOWLLiteral("Labelled")),
            df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        assertTrue(written.contains("Labelled ⊑ ⊤"),
            () -> "a label says nothing about kind:\n" + written);
    }
}
