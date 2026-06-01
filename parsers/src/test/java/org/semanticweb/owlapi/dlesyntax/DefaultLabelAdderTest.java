package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLabelAdderTest {

    private static final String NS = "http://example.org/test#";

    private OWLOntologyManager manager;
    private OWLDataFactory df;
    private OWLOntology ontology;

    @BeforeEach
    void setUp() throws OWLOntologyCreationException {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        ontology = manager.createOntology();
    }

    private IRI iri(String local) {
        return IRI.create(NS + local);
    }

    private Optional<String> labelFor(IRI entityIRI) {
        IRI labelIRI = OWLRDFVocabulary.RDFS_LABEL.getIRI();
        return ontology.getAnnotationAssertionAxioms(entityIRI).stream()
            .filter(ax -> labelIRI.equals(ax.getProperty().getIRI()))
            .filter(ax -> ax.getValue() instanceof OWLLiteral)
            .map(ax -> ((OWLLiteral) ax.getValue()).getLiteral())
            .findFirst();
    }

    /** Entity with no label gets the IRI local name as default. */
    @Test
    void unlabelledEntity_getsDefaultLabel() {
        IRI fooIRI = iri("Foo");
        OWLClass foo = df.getOWLClass(fooIRI);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(foo));

        DefaultLabelAdder.addDefaultLabels(ontology);

        Optional<String> label = labelFor(fooIRI);
        assertTrue(label.isPresent(), "Default label should have been added");
        assertEquals("Foo", label.get());
    }

    /** Entity with an explicit label keeps it; no default is added. */
    @Test
    void labelledEntity_labelUnchanged() {
        IRI barIRI = iri("Bar");
        OWLClass bar = df.getOWLClass(barIRI);
        OWLAnnotationProperty labelProp = df.getRDFSLabel();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(bar));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
            labelProp, barIRI, df.getOWLLiteral("Custom label")));

        DefaultLabelAdder.addDefaultLabels(ontology);

        long count = ontology.getAnnotationAssertionAxioms(barIRI).stream()
            .filter(ax -> OWLRDFVocabulary.RDFS_LABEL.getIRI().equals(ax.getProperty().getIRI()))
            .count();
        assertEquals(1, count, "Should still have exactly one label");
        assertEquals("Custom label", labelFor(barIRI).orElse(null));
    }

    /** Built-in entities (owl:Thing) must not receive a default label. */
    @Test
    void builtInEntity_noDefaultLabel() {
        // Just run the adder on an empty ontology that would implicitly contain owl:Thing
        // via a SubClassOf axiom.
        IRI thingIRI = IRI.create("http://www.w3.org/2002/07/owl#Thing");
        OWLClass foo = df.getOWLClass(iri("Foo"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(foo, df.getOWLThing()));

        DefaultLabelAdder.addDefaultLabels(ontology);

        long count = ontology.getAnnotationAssertionAxioms(thingIRI).stream()
            .filter(ax -> OWLRDFVocabulary.RDFS_LABEL.getIRI().equals(ax.getProperty().getIRI()))
            .count();
        assertEquals(0, count, "owl:Thing must not receive a default label");
    }

    /** DLE-namespace entities must not receive a default label. */
    @Test
    void dleNamespaceEntity_noDefaultLabel() {
        IRI dleIRI = IRI.create(DLESyntaxAxiomVisitor.DLE_NS + "SomePredicate");
        OWLClass dleCls = df.getOWLClass(dleIRI);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(dleCls));

        DefaultLabelAdder.addDefaultLabels(ontology);

        long count = ontology.getAnnotationAssertionAxioms(dleIRI).stream()
            .filter(ax -> OWLRDFVocabulary.RDFS_LABEL.getIRI().equals(ax.getProperty().getIRI()))
            .count();
        assertEquals(0, count, "DLE-namespace entities must not receive a default label");
    }

    /** Object properties also get default labels. */
    @Test
    void objectProperty_getsDefaultLabel() {
        IRI propIRI = iri("hasRole");
        OWLObjectProperty prop = df.getOWLObjectProperty(propIRI);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(prop));

        DefaultLabelAdder.addDefaultLabels(ontology);

        Optional<String> label = labelFor(propIRI);
        assertTrue(label.isPresent(), "Default label should have been added for object property");
        assertEquals("hasRole", label.get());
    }
}
