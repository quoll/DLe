package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualDeclarationResolverTest {

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

    /**
     * Single unannotated sub-property: the boundary should push down from X to Y.
     * SubObjectPropertyOf(Y, X) → SubClassOf(Y, X).
     */
    @Test
    void singleUnannotatedChild_pushesBoundaryDown() {
        IRI xIRI = iri("X");
        IRI yIRI = iri("Y");
        OWLClass xClass = df.getOWLClass(xIRI);
        OWLObjectProperty xProp = df.getOWLObjectProperty(xIRI);
        OWLObjectProperty yProp = df.getOWLObjectProperty(yIRI);

        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xClass));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(yProp));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(yProp, xProp));

        DualDeclarationResolver.resolve(ontology);

        OWLClass yClass = df.getOWLClass(yIRI);

        assertFalse(ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY).anyMatch(
            a -> a.getSubProperty().equals(yProp) && a.getSuperProperty().equals(xProp)),
            "SubObjectPropertyOf(Y, X) should have been removed");
        assertTrue(ontology.axioms(AxiomType.SUBCLASS_OF).anyMatch(
            a -> a.getSubClass().equals(yClass) && a.getSuperClass().equals(xClass)),
            "SubClassOf(Y, X) should have been added");
    }

    /**
     * Single annotated sub-property: the boundary must not push — X stays dual-declared.
     */
    @Test
    void annotatedChild_noPush() {
        IRI xIRI = iri("X");
        IRI yIRI = iri("Y");
        OWLClass xClass = df.getOWLClass(xIRI);
        OWLObjectProperty xProp = df.getOWLObjectProperty(xIRI);
        OWLObjectProperty yProp = df.getOWLObjectProperty(yIRI);
        OWLAnnotationProperty label = df.getRDFSLabel();

        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xClass));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(yProp));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(yProp, xProp));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(label, yIRI,
            df.getOWLLiteral("Y label")));

        DualDeclarationResolver.resolve(ontology);

        OWLClass yClass = df.getOWLClass(yIRI);

        assertTrue(ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY).anyMatch(
            a -> a.getSubProperty().equals(yProp) && a.getSuperProperty().equals(xProp)),
            "SubObjectPropertyOf(Y, X) should remain when Y is annotated");
        assertFalse(ontology.axioms(AxiomType.SUBCLASS_OF).anyMatch(
            a -> a.getSubClass().equals(yClass) && a.getSuperClass().equals(xClass)),
            "SubClassOf(Y, X) must not be added when Y is annotated");
        assertTrue(ontology.objectPropertiesInSignature().anyMatch(p -> p.getIRI().equals(xIRI)),
            "X must remain in object property signature");
        assertTrue(ontology.classesInSignature().anyMatch(c -> c.getIRI().equals(xIRI)),
            "X must remain in class signature");
    }

    /**
     * Mixed children: the unannotated child's sub-property axiom is swapped; the annotated
     * child's remains. Because the annotated child still references X as an object property,
     * X stays dual-declared.
     */
    @Test
    void mixedChildren_partialPush_parentStaysDualDeclared() {
        IRI xIRI = iri("X");
        IRI y1IRI = iri("Y1");
        IRI y2IRI = iri("Y2");
        OWLClass xClass = df.getOWLClass(xIRI);
        OWLObjectProperty xProp = df.getOWLObjectProperty(xIRI);
        OWLObjectProperty y1Prop = df.getOWLObjectProperty(y1IRI);
        OWLObjectProperty y2Prop = df.getOWLObjectProperty(y2IRI);
        OWLAnnotationProperty label = df.getRDFSLabel();

        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xClass));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(y1Prop));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(y2Prop));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(y1Prop, xProp));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(y2Prop, xProp));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(label, y2IRI,
            df.getOWLLiteral("Y2 label")));

        DualDeclarationResolver.resolve(ontology);

        OWLClass y1Class = df.getOWLClass(y1IRI);
        OWLClass y2Class = df.getOWLClass(y2IRI);

        // Y1 (unannotated): sub-property axiom replaced by sub-class axiom
        assertFalse(ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY).anyMatch(
            a -> a.getSubProperty().equals(y1Prop) && a.getSuperProperty().equals(xProp)),
            "SubObjectPropertyOf(Y1, X) should have been removed");
        assertTrue(ontology.axioms(AxiomType.SUBCLASS_OF).anyMatch(
            a -> a.getSubClass().equals(y1Class) && a.getSuperClass().equals(xClass)),
            "SubClassOf(Y1, X) should have been added");

        // Y2 (annotated): sub-property axiom must remain, no sub-class axiom added
        assertTrue(ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY).anyMatch(
            a -> a.getSubProperty().equals(y2Prop) && a.getSuperProperty().equals(xProp)),
            "SubObjectPropertyOf(Y2, X) should remain when Y2 is annotated");
        assertFalse(ontology.axioms(AxiomType.SUBCLASS_OF).anyMatch(
            a -> a.getSubClass().equals(y2Class) && a.getSuperClass().equals(xClass)),
            "SubClassOf(Y2, X) must not be added when Y2 is annotated");

        // X stays dual-declared because Y2 still references it as an object property
        assertTrue(ontology.objectPropertiesInSignature().anyMatch(p -> p.getIRI().equals(xIRI)),
            "X must remain in object property signature");
        assertTrue(ontology.classesInSignature().anyMatch(c -> c.getIRI().equals(xIRI)),
            "X must remain in class signature");
    }

    /**
     * Cascade: X is dual-declared; Y is an unannotated sub-property of X; Z is an annotated
     * sub-property of Y. The boundary should push X→Y (making Y dual-declared) but stop at Z.
     */
    @Test
    void cascadeUnannotatedLevels_stopsAtAnnotatedNode() {
        IRI xIRI = iri("X");
        IRI yIRI = iri("Y");
        IRI zIRI = iri("Z");
        OWLClass xClass = df.getOWLClass(xIRI);
        OWLObjectProperty xProp = df.getOWLObjectProperty(xIRI);
        OWLObjectProperty yProp = df.getOWLObjectProperty(yIRI);
        OWLObjectProperty zProp = df.getOWLObjectProperty(zIRI);
        OWLAnnotationProperty label = df.getRDFSLabel();

        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xClass));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(xProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(yProp));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(zProp));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(yProp, xProp));
        manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(zProp, yProp));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(label, zIRI,
            df.getOWLLiteral("Z label")));

        DualDeclarationResolver.resolve(ontology);

        OWLClass yClass = df.getOWLClass(yIRI);
        OWLClass zClass = df.getOWLClass(zIRI);

        // Y: SubObjectPropertyOf(Y, X) replaced by SubClassOf(Y, X)
        assertFalse(ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY).anyMatch(
            a -> a.getSubProperty().equals(yProp) && a.getSuperProperty().equals(xProp)),
            "SubObjectPropertyOf(Y, X) should have been removed");
        assertTrue(ontology.axioms(AxiomType.SUBCLASS_OF).anyMatch(
            a -> a.getSubClass().equals(yClass) && a.getSuperClass().equals(xClass)),
            "SubClassOf(Y, X) should have been added");

        // Z: SubObjectPropertyOf(Z, Y) must remain — Z is annotated so no push
        assertTrue(ontology.axioms(AxiomType.SUB_OBJECT_PROPERTY).anyMatch(
            a -> a.getSubProperty().equals(zProp) && a.getSuperProperty().equals(yProp)),
            "SubObjectPropertyOf(Z, Y) should remain when Z is annotated");
        assertFalse(ontology.axioms(AxiomType.SUBCLASS_OF).anyMatch(
            a -> a.getSubClass().equals(zClass) && a.getSuperClass().equals(yClass)),
            "SubClassOf(Z, Y) must not be added when Z is annotated");

        // Y is dual-declared: SubClassOf(Y, X) puts Y in classesInSignature;
        // SubObjectPropertyOf(Z, Y) keeps Y in objectPropertiesInSignature.
        assertTrue(ontology.classesInSignature().anyMatch(c -> c.getIRI().equals(yIRI)),
            "Y must be in class signature after boundary push");
        assertTrue(ontology.objectPropertiesInSignature().anyMatch(p -> p.getIRI().equals(yIRI)),
            "Y must remain in object property signature (Z still references it as a property)");
    }
}
