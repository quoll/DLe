package org.semanticweb.owlapi.dlesyntax;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;

/**
 * Post-processing step that pushes dual-declared (owl:Class + owl:ObjectProperty) nodes
 * downward through the property hierarchy to unannotated sub-properties.
 *
 * <p>When a node X is dual-declared and a sub-property Y ⊑ X has no annotation assertions,
 * the sub-property axiom SubObjectPropertyOf(Y, X) is replaced with SubClassOf(Y, X),
 * making Y dual-declared (or class-only) instead of X. This repeats until stable.
 *
 * <p>If all sub-properties of X are annotated, X remains dual-declared. If some are
 * annotated and some are not, only the unannotated ones are pushed; X stays dual-declared
 * because the annotated children still reference it as an object property.
 */
class DualDeclarationResolver {

    private DualDeclarationResolver() {}

    static void resolve(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();

        boolean changed = true;
        while (changed) {
            changed = false;

            Set<IRI> propIRIs = ontology.objectPropertiesInSignature()
                .map(OWLObjectProperty::getIRI)
                .collect(Collectors.toSet());
            Set<IRI> classIRIs = ontology.classesInSignature()
                .map(OWLClass::getIRI)
                .collect(Collectors.toSet());

            for (IRI xIRI : propIRIs) {
                if (!classIRIs.contains(xIRI)) continue;

                OWLObjectProperty xProp = df.getOWLObjectProperty(xIRI);
                OWLClass xClass = df.getOWLClass(xIRI);

                List<OWLSubObjectPropertyOfAxiom> toSwap = ontology
                    .axioms(AxiomType.SUB_OBJECT_PROPERTY)
                    .filter(a -> a.getSuperProperty().equals(xProp))
                    .filter(a -> a.getSubProperty().isNamed())
                    .filter(a -> !ontology.annotationAssertionAxioms(
                        a.getSubProperty().getNamedProperty().getIRI()).findAny().isPresent())
                    .collect(Collectors.toList());

                for (OWLSubObjectPropertyOfAxiom axiom : toSwap) {
                    IRI subIRI = axiom.getSubProperty().getNamedProperty().getIRI();
                    OWLClass subClass = df.getOWLClass(subIRI);
                    manager.removeAxiom(ontology, axiom);
                    manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(subClass, xClass));
                    changed = true;
                }
            }
        }
    }
}
