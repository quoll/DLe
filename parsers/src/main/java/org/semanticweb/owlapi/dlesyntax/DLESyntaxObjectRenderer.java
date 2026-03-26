package org.semanticweb.owlapi.dlesyntax;

import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxObjectRenderer;
import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntax;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDatatypeRestriction;
import org.semanticweb.owlapi.model.OWLFacetRestriction;
import org.semanticweb.owlapi.model.OWLAsymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLDisjointDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLIrreflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.model.OWLSymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLDataHasValue;
import org.semanticweb.owlapi.model.OWLObjectHasValue;
import org.semanticweb.owlapi.model.OWLObjectHasSelf;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLReflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.vocab.OWLFacet;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationSubject;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataAllValuesFrom;
import org.semanticweb.owlapi.model.OWLDataOneOf;
import org.semanticweb.owlapi.model.OWLDataCardinalityRestriction;
import org.semanticweb.owlapi.model.OWLDataExactCardinality;
import org.semanticweb.owlapi.model.OWLDataMaxCardinality;
import org.semanticweb.owlapi.model.OWLDataMinCardinality;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction;
import org.semanticweb.owlapi.model.OWLObjectExactCardinality;
import org.semanticweb.owlapi.model.OWLObjectMaxCardinality;
import org.semanticweb.owlapi.model.OWLObjectMinCardinality;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLQuantifiedDataRestriction;
import org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction;
import org.semanticweb.owlapi.model.PrefixManager;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Extends the standard DL syntax object renderer with support for annotations
 * and other constructs not handled by the base renderer.
 */
public class DLESyntaxObjectRenderer extends DLSyntaxObjectRenderer {

    @Nullable
    private PrefixManager prefixManager;
    @Nullable
    private OWLOntology ontology;

    public void setOntology(@Nullable OWLOntology ontology) {
        this.ontology = ontology;
    }

    /**
     * Render a class: if the IRI is in the DLE namespace, emit the rdfs:label
     * (the original predicate expression) rather than the CURIE.
     */
    @Override
    public void visit(OWLClass ce) {
        if (ontology != null && ce.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            String label = ontology.annotationAssertionAxioms(ce.getIRI())
                .filter(ax -> OWLRDFVocabulary.RDFS_LABEL.getIRI().equals(ax.getProperty().getIRI()))
                .filter(ax -> ax.getValue() instanceof OWLLiteral)
                .map(ax -> ((OWLLiteral) ax.getValue()).getLiteral())
                .findFirst().orElse(null);
            if (label != null) {
                write(label);
                return;
            }
        }
        super.visit(ce);
    }

    @Nullable
    private OWLDataRange dataPropertyRange(OWLDataPropertyExpression property) {
        if (ontology == null) return null;
        return ontology.axioms(AxiomType.DATA_PROPERTY_RANGE)
            .filter(ax -> ax.getProperty().equals(property))
            .map(ax -> ax.getRange())
            .findFirst().orElse(null);
    }

    @Nullable
    private OWLClassExpression objectPropertyRange(OWLObjectPropertyExpression property) {
        if (ontology == null) return null;
        return ontology.axioms(AxiomType.OBJECT_PROPERTY_RANGE)
            .filter(ax -> ax.getProperty().equals(property))
            .map(ax -> ax.getRange())
            .findFirst().orElse(null);
    }

    /**
     * Sets the prefix manager used to produce short-form IRIs.
     * Also updates the parent's ShortFormProvider so entity rendering is consistent.
     */
    public void setPrefixManager(@Nullable PrefixManager pm) {
        prefixManager = pm;
        if (pm != null) {
            setShortFormProvider(entity -> {
                String curie = pm.getPrefixIRI(entity.getIRI());
                return curie != null ? stripDefaultPrefix(curie)
                    : entity.getIRI().getRemainder().orElse(entity.getIRI().toString());
            });
        }
    }

    /**
     * Strips the leading colon from a CURIE that belongs to the default (empty)
     * namespace prefix, e.g. {@code ":Dog"} becomes {@code "Dog"}.
     * Prefixed names with an explicit prefix (e.g. {@code "xsd:boolean"}) are unchanged.
     */
    private static String stripDefaultPrefix(String curie) {
        return curie.startsWith(":") ? curie.substring(1) : curie;
    }

    /**
     * Returns the short form of an IRI using the prefix manager if available,
     * falling back to the IRI's local fragment. Throws if neither is possible.
     */
    private String shortFormIRI(IRI iri) {
        if (prefixManager != null) {
            String curie = prefixManager.getPrefixIRI(iri);
            if (curie != null) {
                return stripDefaultPrefix(curie);
            }
        }
        return iri.getRemainder().orElseThrow(() ->
            new IllegalStateException("No prefix/namespace found for IRI: " + iri));
    }

    /** Package-private: used by {@link DLESyntaxStorerBase} to render IRIs consistently. */
    String shortForm(IRI iri) {
        return shortFormIRI(iri);
    }

    /** Renders an OWLLiteral as a DLE literal token: NUMBER/BOOL unquoted, strings quoted. */
    private String renderLiteral(OWLLiteral lit) {
        if (lit.isInteger() || lit.isDouble() || lit.isFloat()) return lit.getLiteral();
        if (lit.isBoolean()) return lit.getLiteral();
        String escaped = lit.getLiteral().replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private String renderSubject(OWLAnnotationSubject subject) {
        if (subject instanceof IRI) {
            return shortFormIRI((IRI) subject);
        }
        // OWLAnonymousIndividual
        return ((OWLAnonymousIndividual) subject).getID().getID();
    }

    private String renderValue(OWLAnnotationValue value) {
        if (value instanceof OWLLiteral) {
            String escaped = ((OWLLiteral) value).getLiteral()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
            return "\"" + escaped + "\"";
        }
        if (value instanceof IRI) {
            return shortFormIRI((IRI) value);
        }
        // OWLAnonymousIndividual
        return ((OWLAnonymousIndividual) value).getID().getID();
    }

    // -----------------------------------------------------------------------
    // Private helpers — shadow the parent's private versions to suppress
    // the space that the parent writes between the quantifier/cardinality
    // symbol and the property name / cardinality number.
    // -----------------------------------------------------------------------

    private void writeQuantifiedRestriction(OWLQuantifiedObjectRestriction r, DLSyntax keyword) {
        write(keyword);
        r.getProperty().accept(this);
        write(".");
        writeNested(r.getFiller());
    }

    private void writeQuantifiedRestriction(OWLQuantifiedDataRestriction r, DLSyntax keyword) {
        write(keyword);
        r.getProperty().accept(this);
        write(".");
        writeNested(r.getFiller());
    }

    private void writeCardinalityRestriction(OWLObjectCardinalityRestriction r, DLSyntax keyword) {
        write(keyword);
        write(r.getCardinality());
        write(" ");
        r.getProperty().accept(this);
        if (!r.getFiller().isOWLThing()) {
            write(".");
            writeNested(r.getFiller());
        }
    }

    private void writeCardinalityRestriction(OWLDataCardinalityRestriction r, DLSyntax keyword) {
        write(keyword);
        write(r.getCardinality());
        write(" ");
        r.getProperty().accept(this);
        if (!r.getFiller().isTopDatatype()) {
            write(".");
            writeNested(r.getFiller());
        }
    }

    private void writeDomainAxiom(OWLPropertyDomainAxiom<?> axiom) {
        write(DLSyntax.EXISTS);
        axiom.getProperty().accept(this);
        write(".");
        write(DLSyntax.TOP);
        write(" ");
        write(DLSyntax.SUBCLASS);
        write(" ");
        writeNested(axiom.getDomain());
    }

    private void writeRangeAxiom(OWLPropertyRangeAxiom<?, ?> axiom) {
        write(DLSyntax.TOP);
        write(" ");
        write(DLSyntax.SUBCLASS);
        write(" ");
        write(DLSyntax.FORALL);
        axiom.getProperty().accept(this);
        write(".");
        writeNested(axiom.getRange());
    }

    // -----------------------------------------------------------------------
    // Visit overrides — delegate to the helpers above
    // -----------------------------------------------------------------------

    @Override public void visit(OWLObjectPropertyDomainAxiom axiom) { writeDomainAxiom(axiom); }
    @Override public void visit(OWLDataPropertyDomainAxiom axiom)   { writeDomainAxiom(axiom); }
    @Override public void visit(OWLObjectPropertyRangeAxiom axiom)  { writeRangeAxiom(axiom); }
    @Override public void visit(OWLDataPropertyRangeAxiom axiom)    { writeRangeAxiom(axiom); }

    @Override public void visit(OWLObjectSomeValuesFrom ce) { writeQuantifiedRestriction(ce, DLSyntax.EXISTS); }
    @Override public void visit(OWLObjectAllValuesFrom ce)  { writeQuantifiedRestriction(ce, DLSyntax.FORALL); }
    @Override public void visit(OWLDataSomeValuesFrom ce)   { writeQuantifiedRestriction(ce, DLSyntax.EXISTS); }
    @Override public void visit(OWLDataAllValuesFrom ce)    { writeQuantifiedRestriction(ce, DLSyntax.FORALL); }

    @Override public void visit(OWLObjectMinCardinality ce)   { writeCardinalityRestriction(ce, DLSyntax.MIN); }
    @Override public void visit(OWLObjectExactCardinality ce) { writeCardinalityRestriction(ce, DLSyntax.EQUAL); }
    @Override public void visit(OWLObjectMaxCardinality ce)   { writeCardinalityRestriction(ce, DLSyntax.MAX); }
    @Override public void visit(OWLDataMinCardinality ce)     { writeCardinalityRestriction(ce, DLSyntax.MIN); }
    @Override public void visit(OWLDataExactCardinality ce)   { writeCardinalityRestriction(ce, DLSyntax.EQUAL); }
    @Override public void visit(OWLDataMaxCardinality ce)     { writeCardinalityRestriction(ce, DLSyntax.MAX); }

    @Override
    public void visit(OWLFunctionalDataPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Func", axiom.getProperty());
    }

    @Override
    public void visit(OWLFunctionalObjectPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Func", axiom.getProperty());
    }

    @Override
    public void visit(OWLDisjointObjectPropertiesAxiom axiom) {
        writeNaryRoleAxiom("Disj", axiom.properties());
    }

    @Override
    public void visit(OWLDisjointDataPropertiesAxiom axiom) {
        writeNaryRoleAxiom("Disj", axiom.properties());
    }

    @Override
    public void visit(OWLHasKeyAxiom axiom) {
        axiom.getClassExpression().accept(this);
        write(" ");
        write(DLSyntax.SUBCLASS);
        write(" key(");
        List<OWLPropertyExpression> keys = axiom.propertyExpressions()
            .collect(java.util.stream.Collectors.toList());
        for (Iterator<OWLPropertyExpression> it = keys.iterator(); it.hasNext();) {
            it.next().accept(this);
            if (it.hasNext()) {
                write(", ");
            }
        }
        write(")");
    }

    @Override
    protected void writeNested(org.semanticweb.owlapi.model.OWLObject object) {
        if (object instanceof OWLDataOneOf || object instanceof OWLDatatypeRestriction) {
            object.accept(this);
        } else {
            super.writeNested(object);
        }
    }

    @Override
    public void visit(OWLDataHasValue ce) {
        write(DLSyntax.EXISTS);
        ce.getProperty().accept(this);
        write(".{");
        write(renderLiteral(ce.getFiller()));
        write("}");
    }

    @Override
    public void visit(OWLObjectHasValue ce) {
        write(DLSyntax.EXISTS);
        ce.getProperty().accept(this);
        write(".{");
        ce.getFiller().accept(this);
        write("}");
    }

    @Override
    public void visit(OWLObjectHasSelf ce) {
        write(DLSyntax.EXISTS);
        ce.getProperty().accept(this);
        write(".");
        write("Self");
    }

    @Override
    public void visit(OWLIrreflexiveObjectPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Irref", axiom.getProperty());
    }

    @Override
    public void visit(OWLReflexiveObjectPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Ref", axiom.getProperty());
    }

    @Override
    public void visit(OWLTransitiveObjectPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Trans", axiom.getProperty());
    }

    @Override
    public void visit(OWLSymmetricObjectPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Sym", axiom.getProperty());
    }

    @Override
    public void visit(OWLAsymmetricObjectPropertyAxiom axiom) {
        writeUnaryRoleAxiom("Asym", axiom.getProperty());
    }

    private void writeUnaryRoleAxiom(String keyword, OWLPropertyExpression prop) {
        write(keyword);
        write("(");
        prop.accept(this);
        write(")");
    }

    private void writeNaryRoleAxiom(String keyword, java.util.stream.Stream<? extends OWLPropertyExpression> props) {
        write(keyword);
        write("(");
        List<OWLPropertyExpression> list = props.collect(java.util.stream.Collectors.toList());
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) write(", ");
            list.get(i).accept(this);
        }
        write(")");
    }

    @Override
    public void visit(OWLSubPropertyChainOfAxiom axiom) {
        List<OWLObjectPropertyExpression> chain = axiom.getPropertyChain()
            .stream().collect(java.util.stream.Collectors.toList());
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) write(" \u2218 ");
            chain.get(i).accept(this);
        }
        write(" ");
        write(DLSyntax.SUBCLASS);
        write(" ");
        axiom.getSuperProperty().accept(this);
    }

    @Override
    public void visit(OWLDatatypeRestriction restriction) {
        List<OWLFacetRestriction> facets = restriction.facetRestrictions()
            .sorted().collect(java.util.stream.Collectors.toList());
        boolean compact = !facets.isEmpty()
            && facets.stream().allMatch(fr -> isNumericFacet(fr.getFacet()));
        if (compact) {
            // xsd:integer[≥1 ⊓ ≤5]
            write(shortFormIRI(restriction.getDatatype().getIRI()));
            write("[");
            for (int i = 0; i < facets.size(); i++) {
                if (i > 0) write(" \u2293 ");
                OWLFacetRestriction fr = facets.get(i);
                write(numericFacetSymbol(fr.getFacet()));
                write(fr.getFacetValue().getLiteral());
            }
            write("]");
        } else {
            // [xsd:string ⊓ [matches "..."]]
            write("[");
            write(shortFormIRI(restriction.getDatatype().getIRI()));
            facets.forEach(fr -> {
                write(" \u2293 [");
                write(facetKeyword(fr.getFacet()));
                write(" ");
                write(renderLiteral(fr.getFacetValue()));
                write("]");
            });
            write("]");
        }
    }

    private static boolean isNumericFacet(OWLFacet facet) {
        switch (facet) {
            case MIN_INCLUSIVE:
            case MAX_INCLUSIVE:
            case MIN_EXCLUSIVE:
            case MAX_EXCLUSIVE:
            case TOTAL_DIGITS:
            case FRACTION_DIGITS: return true;
            default:              return false;
        }
    }

    private static String numericFacetSymbol(OWLFacet facet) {
        switch (facet) {
            case MIN_INCLUSIVE: return "\u2265"; // ≥
            case MAX_INCLUSIVE: return "\u2264"; // ≤
            case MIN_EXCLUSIVE: return ">";
            case MAX_EXCLUSIVE: return "<";
            default:            return facet.getShortForm();
        }
    }

    private static String facetKeyword(OWLFacet facet) {
        switch (facet) {
            case PATTERN:          return "matches";
            case LENGTH:           return "length";
            case MIN_LENGTH:       return "minLength";
            case MAX_LENGTH:       return "maxLength";
            case MIN_INCLUSIVE:    return "min";
            case MAX_INCLUSIVE:    return "max";
            case MIN_EXCLUSIVE:    return "minExclusive";
            case MAX_EXCLUSIVE:    return "maxExclusive";
            case TOTAL_DIGITS:     return "totalDigits";
            case FRACTION_DIGITS:  return "fractionDigits";
            case LANG_RANGE:       return "langRange";
            default:               return facet.getShortForm();
        }
    }

    @Override
    public void visit(OWLDataOneOf node) {
        write("{");
        List<OWLLiteral> values = node.values().collect(java.util.stream.Collectors.toList());
        for (Iterator<OWLLiteral> it = values.iterator(); it.hasNext();) {
            OWLLiteral lit = it.next();
            if (lit.isInteger() || lit.isDouble() || lit.isFloat() || lit.isBoolean()) {
                write(lit.getLiteral());
            } else {
                write("\"");
                write(lit.getLiteral());
                write("\"");
            }
            if (it.hasNext()) {
                write(",");
            }
        }
        write("}");
    }

    @Override
    public void visit(OWLAnnotationAssertionAxiom axiom) {
        IRI propIRI = axiom.getProperty().getIRI();
        String subject = renderSubject(axiom.getSubject());
        OWLAnnotationValue value = axiom.getValue();

        IRI rdfValueIRI = IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");
        if (rdfValueIRI.equals(propIRI) && value instanceof OWLLiteral) {
            String literal = ((OWLLiteral) value).getLiteral();
            int arrowIdx = literal.indexOf('\u2192');  // →
            if (arrowIdx >= 0) {
                String args = literal.substring(0, arrowIdx).trim();
                String body = literal.substring(arrowIdx + 1).trim();
                write(subject);
                write("(");
                write(args);
                write(") \u225D ");  // ≝
                write(body);
            } else {
                write(subject);
                write(" \u225D ");
                write(literal);
            }
        } else if (OWLRDFVocabulary.RDFS_LABEL.getIRI().equals(propIRI)) {
            write("@label ");
            write(subject);
            write(" ");
            write(renderValue(value));
        } else if (OWLRDFVocabulary.RDFS_COMMENT.getIRI().equals(propIRI)) {
            write("@doc ");
            write(subject);
            write(" ");
            write(renderValue(value));
        } else if (OWLRDFVocabulary.RDFS_SEE_ALSO.getIRI().equals(propIRI)) {
            write("@storage ");
            write(subject);
            write(" ");
            write(renderValue(value));
        } else if (OWLRDFVocabulary.RDFS_IS_DEFINED_BY.getIRI().equals(propIRI)
                && value instanceof OWLLiteral) {
            write("@db ");
            write(subject);
            write(" ");
            write(renderValue(value));
        } else {
            write("@ann ");
            write(subject);
            write(" ");
            write(renderEntity(axiom.getProperty()));
            write(" ");
            write(renderValue(value));
        }
    }

    @Override
    public void visit(OWLAnnotation node) {
        write("@ann ");
        write(renderEntity(node.getProperty()));
        write(" ");
        write(renderValue(node.getValue()));
    }

    @Override
    public void visit(OWLAnnotationProperty property) {
        writeEntity(property);
    }

    @Override
    public void visit(OWLAnnotationPropertyDomainAxiom axiom) {
        write(renderEntity(axiom.getProperty()));
        write(" domain ");
        write(shortFormIRI(axiom.getDomain()));
    }

    @Override
    public void visit(OWLAnnotationPropertyRangeAxiom axiom) {
        write(renderEntity(axiom.getProperty()));
        write(" range ");
        write(shortFormIRI(axiom.getRange()));
    }

    @Override
    public void visit(OWLSubAnnotationPropertyOfAxiom axiom) {
        write(renderEntity(axiom.getSubProperty()));
        write(" \u2291 ");
        write(renderEntity(axiom.getSuperProperty()));
    }

    @Override
    public void visit(OWLAnonymousIndividual individual) {
        write(individual.getID().getID());
    }
}
