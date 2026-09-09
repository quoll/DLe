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
 *
 * <p>A name whose kind the document <em>stated</em> — with {@code X ⊑ owl:topObjectProperty}
 * or {@code X ⊑ owl:topDataProperty}, at either end of the axiom — is left alone. Those statements exist precisely to
 * say "this name is punned, here", and pushing the pun down to the children would undo
 * them. Without this exemption the disambiguation only survives for children that happen
 * to carry an annotation, which made the whole mechanism work on labelled documents such
 * as SNOMED CT extracts and fail silently on unlabelled ones.
 */
class DualDeclarationResolver {

    private DualDeclarationResolver() {}

    static void resolve(OWLOntology ontology) {
        resolve(ontology, java.util.Collections.emptySet());
    }

    /**
     * @param statedKinds IRIs whose kind the document stated outright, which are not pushed
     */
    static void resolve(OWLOntology ontology, Set<IRI> statedKinds) {
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
                if (statedKinds.contains(xIRI)) continue;   // the document said so

                OWLObjectProperty xProp = df.getOWLObjectProperty(xIRI);
                OWLClass xClass = df.getOWLClass(xIRI);

                List<OWLSubObjectPropertyOfAxiom> toSwap = ontology
                    .axioms(AxiomType.SUB_OBJECT_PROPERTY)
                    .filter(a -> a.getSuperProperty().equals(xProp))
                    .filter(a -> a.getSubProperty().isNamed())
                    .filter(a -> !ontology.annotationAssertionAxioms(
                        a.getSubProperty().getNamedProperty().getIRI()).findAny().isPresent())
                    // A child whose own kind the document stated is not guessed at either.
                    // Exempting only the parent left the annotation heuristic deciding
                    // whether an explicit statement was honoured.
                    .filter(a -> !statedKinds.contains(
                        a.getSubProperty().getNamedProperty().getIRI()))
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
