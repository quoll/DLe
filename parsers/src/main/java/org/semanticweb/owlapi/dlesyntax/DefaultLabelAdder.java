package org.semanticweb.owlapi.dlesyntax;

import java.util.List;
import java.util.stream.Collectors;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Post-processing step that adds a default {@code rdfs:label} for every named entity
 * in the ontology that has no label annotation.
 *
 * <p>The default label value is the IRI's local name (its fragment, the part after {@code #}
 * or the last {@code /}). On output the DLE renderer omits any {@code @label} whose value
 * equals the entity's local name, so the round-trip is lossless:
 * <ul>
 *   <li>No {@code @label} in source → default added here → omitted on write → clean.</li>
 *   <li>Explicit {@code @label Foo "Bar"} → no default added → written out normally.</li>
 *   <li>Explicit {@code @label Foo "Foo"} → no default added → omitted on write (redundant).</li>
 * </ul>
 *
 * <p>Built-in OWL/RDF/RDFS entities and internal DLE-namespace entities are excluded.
 */
class DefaultLabelAdder {

    private DefaultLabelAdder() {}

    static void addDefaultLabels(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLAnnotationProperty labelProp = df.getRDFSLabel();
        IRI labelIRI = labelProp.getIRI();

        List<OWLEntity> toLabel = ontology.getSignature().stream()
            .filter(entity -> !entity.isBuiltIn())
            .filter(entity -> !entity.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS))
            .filter(entity -> ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
                .noneMatch(ax -> labelIRI.equals(ax.getProperty().getIRI())))
            .collect(Collectors.toList());

        for (OWLEntity entity : toLabel) {
            String localName = entity.getIRI().getRemainder().orNull();
            if (localName != null && !localName.isEmpty()) {
                manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                    labelProp, entity.getIRI(), df.getOWLLiteral(localName)));
            }
        }
    }
}
