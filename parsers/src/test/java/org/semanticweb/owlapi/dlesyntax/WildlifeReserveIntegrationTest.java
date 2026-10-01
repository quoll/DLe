package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import java.util.ArrayList;
import java.util.List;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;


import static org.junit.jupiter.api.Assertions.*;

class WildlifeReserveIntegrationTest {

    @Test
    void wildlifeReserveDleFile_parsesSuccessfully() throws Exception {
        URL resource = getClass().getClassLoader().getResource("data/wildlife-reserve-test.dle");
        assertNotNull(resource, "Test resource data/wildlife-reserve-test.dle not found on classpath");
        File file = new File(resource.toURI());

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();

        OWLDocumentFormat format = parser.parse(
            new FileDocumentSource(file),
            ontology,
            manager.getOntologyLoaderConfiguration());

        assertNotNull(format, "Parser must return a document format");
        assertInstanceOf(DLESyntaxDocumentFormat.class, format,
            "Returned format must be DLESyntaxDocumentFormat");
        // A count, not "not empty": one surviving axiom out of a 240-axiom document
        // satisfied the old assertion, so almost any loss passed. The number is the
        // document's, and changing the document is meant to require changing this.
        assertEquals(242, ontology.getLogicalAxiomCount(),
            () -> "the whole document must load, not merely part of it: "
                + ontology.getLogicalAxiomCount() + " logical axioms");
    }

    @Test
    void ofnToDleRoundTrip_preservesLogicalAxioms() throws Exception {
        URL resource = getClass().getClassLoader().getResource("data/wildlife-reserve-test.ofn");
        assertNotNull(resource, "Test resource data/wildlife-reserve-test.ofn not found on classpath");
        File file = new File(resource.toURI());

        // Load original OFN
        OWLOntologyManager manager1 = OWLManager.createOWLOntologyManager();
        manager1.getOntologyStorers().add(new DLESyntaxStorerFactory());
        OWLOntology original = manager1.loadOntologyFromOntologyDocument(file);
        Set<OWLLogicalAxiom> originalAxioms = original.getLogicalAxioms();
        assertFalse(originalAxioms.isEmpty(), "Original ontology must contain logical axioms");

        // Serialize to DLe in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        manager1.saveOntology(original, new DLESyntaxDocumentFormat(), new StreamDocumentTarget(baos));

        // Reload from the in-memory DLe buffer
        OWLOntologyManager manager2 = OWLManager.createOWLOntologyManager();
        OWLOntology reloaded = manager2.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();
        parser.parse(
            new StreamDocumentSource(new ByteArrayInputStream(baos.toByteArray())),
            reloaded,
            manager2.getOntologyLoaderConfiguration());

        Set<OWLLogicalAxiom> reloadedAxioms = reloaded.getLogicalAxioms();
        assertFalse(reloadedAxioms.isEmpty(), "Reloaded ontology must contain logical axioms");

        // Strict equality, against an expectation that has one documented normalisation
        // applied to it. This was relaxed for a while to tolerate `SubClassOf(X, owl:Thing)`
        // axioms the writer's own class markers turned into real ones on the way back in;
        // the markers are now emitted only where a reader would actually misread the name,
        // so that relaxation is gone.
        //
        // The normalisation is `EquivalentObjectProperties(:x ObjectInverseOf(:y))` reading
        // back as `InverseObjectProperties(:x :y)`. DL has one notation for both — `x ≡ y⁻` —
        // so only one of them can survive a round trip, and the dedicated axiom is the one
        // chosen. Eight axioms in this document are in the general form and become the
        // specific one. Normalising the *expectation* rather than comparing loosely keeps
        // every other axiom under a strict assertion.
        // Compared without annotations, because a comment now rides on the axiom it sits
        // above rather than on an entity chosen from the statement — so every commented
        // axiom comes back carrying `Annotation(dle:comment …)` and is, to OWL equality, a
        // different axiom with identical content. The comments themselves are asserted
        // below, so nothing is being waved through here.
        assertEquals(withoutAnnotations(asInverseAxioms(originalAxioms)),
            withoutAnnotations(reloadedAxioms),
            "Round-tripped ontology must have the same logical axioms as the original,"
                + " up to the inverse-property normalisation");

        // And the comments arrived, as annotations on those axioms. The document has them;
        // if they stopped being carried this comparison would pass on content alone.
        assertTrue(reloadedAxioms.stream().anyMatch(ax -> ax.getAnnotations().stream()
                .anyMatch(a -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI
                    .equals(a.getProperty().getIRI()))),
            "the corpus document's comments must come back on their axioms");

        // The identity has to survive the trip too. Comparing the IDs directly would not
        // work — an anonymous ID is unique per instance — so the IRIs are compared, which
        // catches both losing a declared identity and inventing one that was not declared.
        assertEquals(original.getOntologyID().getOntologyIRI(),
            reloaded.getOntologyID().getOntologyIRI(),
            "Round-tripping must preserve the ontology IRI");
        assertEquals(original.getOntologyID().getVersionIRI(),
            reloaded.getOntologyID().getVersionIRI(),
            "Round-tripping must preserve the version IRI");
    }

    /** The same axioms with every annotation stripped, for comparing content alone. */
    private static Set<OWLLogicalAxiom> withoutAnnotations(Set<OWLLogicalAxiom> axioms) {
        return axioms.stream()
            .map(ax -> (OWLLogicalAxiom) ax.getAxiomWithoutAnnotations())
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The same axioms, with each inverse-shaped property equivalence written as the
     * dedicated axiom.
     *
     * <p>{@code EquivalentObjectProperties(:x ObjectInverseOf(:y))} and
     * {@code InverseObjectProperties(:x :y)} are the same statement, and DL spells both
     * {@code x \u2261 y\u207b}, so the reader has to pick one. Anything not of that exact
     * shape is passed through untouched, so this cannot paper over an unrelated change.
     */
    private static Set<OWLLogicalAxiom> asInverseAxioms(Set<OWLLogicalAxiom> axioms) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        Set<OWLLogicalAxiom> out = new HashSet<>();
        for (OWLLogicalAxiom axiom : axioms) {
            OWLLogicalAxiom replacement = null;
            if (axiom instanceof OWLEquivalentObjectPropertiesAxiom) {
                List<OWLObjectPropertyExpression> props = new ArrayList<>(
                    ((OWLEquivalentObjectPropertiesAxiom) axiom).getProperties());
                if (props.size() == 2) {
                    for (int i = 0; i < 2; i++) {
                        OWLObjectPropertyExpression named = props.get(i);
                        OWLObjectPropertyExpression other = props.get(1 - i);
                        if (!named.isAnonymous() && other.isAnonymous()
                                && !other.getInverseProperty().getSimplified().isAnonymous()) {
                            replacement = df.getOWLInverseObjectPropertiesAxiom(
                                named, other.getInverseProperty().getSimplified());
                        }
                    }
                }
            }
            out.add(replacement == null ? axiom : replacement);
        }
        return out;
    }
}
