package org.semanticweb.owlapi.dlesyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWLFacet;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Second-pass visitor that converts a DLE parse tree into OWLAPI axioms.
 *
 * <p>Entity type information (which names are object/data properties) is
 * supplied by {@link EntityTypeScanner} after its first-pass scan.
 */
class DLESyntaxAxiomVisitor extends DLESyntaxBaseVisitor<OWLObject> {

    /** IRI namespace for internally-generated predicate restriction classes. */
    static final String DLE_NS = "http://quoll.github.io/DLe/vocab#";
    /** Annotation property IRI used to preserve DLE {@code #} comments through the OWL model. */
    static final IRI DLE_COMMENT_IRI = IRI.create(DLE_NS + "comment");
    private static final IRI RDF_VALUE_IRI =
        IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");

    private final OWLDataFactory df;
    private final Set<String> objectPropertyNames;
    private final Set<String> dataPropertyNames;
    private final Set<String> predicateNames;
    /** Token stream used to retrieve hidden comment tokens; null means comments are not captured. */
    private final CommonTokenStream tokenStream;

    /** Prefix map: pre-populated with standard prefixes, extended by {@code @prefix} declarations. */
    private final Map<String, String> prefixes = new LinkedHashMap<String, String>() {{
        put(":",     "http://quoll.github.io/DLe/ontology#");
        put("dle:",  DLE_NS);
        put("owl:",  "http://www.w3.org/2002/07/owl#");
        put("rdf:",  "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        put("xsd:",  "http://www.w3.org/2001/XMLSchema#");
        put("xml:",  "http://www.w3.org/XML/1998/namespace");
    }};

    private final List<OWLAxiom> axioms = new ArrayList<>();

    /** Ontology IRI from {@code @ontology}, null if not declared. */
    private IRI ontologyIRI = null;
    /** Version IRI from {@code @version}, null if not declared. */
    private IRI versionIRI  = null;
    /** IRIs declared via {@code @import}. */
    private final List<IRI> imports = new ArrayList<>();

    DLESyntaxAxiomVisitor(OWLDataFactory df,
                          Set<String> objectPropertyNames,
                          Set<String> dataPropertyNames,
                          Set<String> predicateNames,
                          CommonTokenStream tokenStream) {
        this.df = df;
        this.objectPropertyNames = objectPropertyNames;
        this.dataPropertyNames   = dataPropertyNames;
        this.predicateNames      = predicateNames;
        this.tokenStream         = tokenStream;
    }

    List<OWLAxiom> getAxioms()        { return axioms; }
    Map<String, String> getPrefixes() { return prefixes; }
    IRI getOntologyIRI()              { return ontologyIRI; }
    IRI getVersionIRI()               { return versionIRI; }
    List<IRI> getImports()            { return imports; }

    // ── Prefix declarations ──────────────────────────────────────────────────

    @Override
    public OWLObject visitPrefixDecl(DLESyntaxParser.PrefixDeclContext ctx) {
        // PNAME_NS token text includes the trailing colon, e.g. "xsd:" or ":"
        String prefixLabel = ctx.PNAME_NS().getText();        // strip the trailing colon to get the namespace identifier
        String prefixName  = prefixLabel.endsWith(":") ? prefixLabel : prefixLabel + ":";
        // IRI token includes the angle brackets — strip them
        String iri = ctx.IRI().getText();
        iri = iri.substring(1, iri.length() - 1);
        prefixes.put(prefixName, iri);
        return null;
    }

    @Override
    public OWLObject visitOntologyDecl(DLESyntaxParser.OntologyDeclContext ctx) {
        ontologyIRI = expandIriRef(ctx.iriRef());
        return null;
    }

    @Override
    public OWLObject visitVersionDecl(DLESyntaxParser.VersionDeclContext ctx) {
        versionIRI = expandIriRef(ctx.iriRef());
        return null;
    }

    @Override
    public OWLObject visitImportDecl(DLESyntaxParser.ImportDeclContext ctx) {
        imports.add(expandIriRef(ctx.iriRef()));
        return null;
    }

    // ── Predicate definitions ────────────────────────────────────────────────

    @Override
    public OWLObject visitPredicateDefinition(DLESyntaxParser.PredicateDefinitionContext ctx) {
        // name(0) = predicate name; name(1..n) = argument variable names (raw text, not expanded)
        IRI predicateIRI = expandName(ctx.name(0));
        List<String> args = ctx.name().subList(1, ctx.name().size()).stream()
            .map(DLESyntaxParser.NameContext::getText)
            .collect(Collectors.toList());
        // Body: strip leading ≝ (U+225D) from DEFINED_AS_LINE token
        String body = ctx.DEFINED_AS_LINE().getText().substring(1).trim();
        String rdfValue = String.join(",", args) + " \u2192 " + body;  // → U+2192

        OWLAnnotationProperty predProp = df.getOWLAnnotationProperty(predicateIRI);
        axioms.add(df.getOWLDeclarationAxiom(predProp));
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(RDF_VALUE_IRI),
            predicateIRI, df.getOWLLiteral(rdfValue)));
        return null;
    }

    // ── Annotations ──────────────────────────────────────────────────────────

    @Override
    public OWLObject visitLabelAnnotation(DLESyntaxParser.LabelAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = stringLiteral(ctx.STRING().getText());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitDocAnnotation(DLESyntaxParser.DocAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = stringLiteral(ctx.STRING().getText());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_COMMENT.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitStorageAnnotation(DLESyntaxParser.StorageAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = stringLiteral(ctx.STRING().getText());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_SEE_ALSO.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitDbAnnotation(DLESyntaxParser.DbAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = stringLiteral(ctx.STRING().getText());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_IS_DEFINED_BY.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitFolAnnotation(DLESyntaxParser.FolAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        // DEFINED_AS_LINE token text is "≝<body>"; strip the ≝ (1 char) and trim.
        String raw = ctx.DEFINED_AS_LINE().getText();
        String body = raw.substring(1).trim();
        OWLLiteral value = df.getOWLLiteral(body);
        IRI rdfValue = IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(rdfValue), subject, value));
        return null;
    }

    @Override
    public OWLObject visitAnnAnnotation(DLESyntaxParser.AnnAnnotationContext ctx) {
        IRI subject  = expandName(ctx.name(0));
        IRI propIRI  = expandName(ctx.name(1));
        OWLAnnotationValue value = (OWLAnnotationValue) visit(ctx.annotationValue());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(propIRI), subject, value));
        return null;
    }

    @Override
    public OWLObject visitStringAnnotationValue(DLESyntaxParser.StringAnnotationValueContext ctx) {
        return stringLiteral(ctx.STRING().getText());
    }

    @Override
    public OWLObject visitIriAnnotationValue(DLESyntaxParser.IriAnnotationValueContext ctx) {
        return expandName(ctx.name());
    }

    // ── Axioms ───────────────────────────────────────────────────────────────

    @Override
    public OWLObject visitSubClassAxiom(DLESyntaxParser.SubClassAxiomContext ctx) {
        // DisjointObjectProperties / DisjointDataProperties: p ⊓ q ⊑ ⊥
        // Must be detected from parse tree before visiting, to avoid asClass() failure.
        List<DLESyntaxParser.NameContext> disjointNames = allIntersectedNameCtxs(ctx.classExpr(0));
        if (disjointNames != null && isBottomClassExpr(ctx.classExpr(1))) {
            List<String> texts = disjointNames.stream()
                .map(n -> n.getText()).collect(Collectors.toList());
            if (texts.stream().allMatch(objectPropertyNames::contains)) {
                List<OWLObjectPropertyExpression> props = disjointNames.stream()
                    .map(n -> (OWLObjectPropertyExpression) df.getOWLObjectProperty(expandName(n)))
                    .collect(Collectors.toList());
                axioms.add(df.getOWLDisjointObjectPropertiesAxiom(props));
                return null;
            }
            if (texts.stream().allMatch(dataPropertyNames::contains)) {
                List<OWLDataPropertyExpression> props = disjointNames.stream()
                    .map(n -> (OWLDataPropertyExpression) df.getOWLDataProperty(expandName(n)))
                    .collect(Collectors.toList());
                axioms.add(df.getOWLDisjointDataPropertiesAxiom(props));
                return null;
            }
        }

        OWLObject lhs = visit(ctx.classExpr(0));
        OWLObject rhs = visit(ctx.classExpr(1));

        // Irreflexive: ∃r.Self ⊑ ⊥
        if (lhs instanceof OWLObjectHasSelf
                && rhs instanceof OWLClassExpression
                && ((OWLClassExpression) rhs).isOWLNothing()) {
            axioms.add(df.getOWLIrreflexiveObjectPropertyAxiom(
                ((OWLObjectHasSelf) lhs).getProperty()));
            return null;
        }

        // Reflexive: ⊤ ⊑ ∃r.Self
        if (isOWLThing(lhs) && rhs instanceof OWLObjectHasSelf) {
            axioms.add(df.getOWLReflexiveObjectPropertyAxiom(
                ((OWLObjectHasSelf) rhs).getProperty()));
            return null;
        }

        // Domain: ∃r.⊤ ⊑ C
        if (lhs instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom svf = (OWLObjectSomeValuesFrom) lhs;
            if (svf.getFiller().isOWLThing()) {
                axioms.add(df.getOWLObjectPropertyDomainAxiom(svf.getProperty(), asClass(rhs)));
                return null;
            }
        }
        if (lhs instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom svf = (OWLDataSomeValuesFrom) lhs;
            if (svf.getFiller().isTopDatatype()) {
                axioms.add(df.getOWLDataPropertyDomainAxiom(svf.getProperty(), asClass(rhs)));
                return null;
            }
        }

        // Range: ⊤ ⊑ ∀r.C
        if (isOWLThing(lhs)) {
            if (rhs instanceof OWLObjectAllValuesFrom) {
                OWLObjectAllValuesFrom avf = (OWLObjectAllValuesFrom) rhs;
                axioms.add(df.getOWLObjectPropertyRangeAxiom(avf.getProperty(), avf.getFiller()));
                return null;
            }
            if (rhs instanceof OWLDataAllValuesFrom) {
                OWLDataAllValuesFrom avf = (OWLDataAllValuesFrom) rhs;
                axioms.add(df.getOWLDataPropertyRangeAxiom(avf.getProperty(), avf.getFiller()));
                return null;
            }
        }

        // Sub-property: p ⊑ q (both sides are property expressions)
        if (lhs instanceof OWLObjectPropertyExpression && rhs instanceof OWLObjectPropertyExpression) {
            axioms.add(df.getOWLSubObjectPropertyOfAxiom(
                (OWLObjectPropertyExpression) lhs, (OWLObjectPropertyExpression) rhs));
            return null;
        }
        if (lhs instanceof OWLDataPropertyExpression && rhs instanceof OWLDataPropertyExpression) {
            axioms.add(df.getOWLSubDataPropertyOfAxiom(
                (OWLDataPropertyExpression) lhs, (OWLDataPropertyExpression) rhs));
            return null;
        }

        axioms.add(df.getOWLSubClassOfAxiom(asClass(lhs), asClass(rhs)));
        return null;
    }

    @Override
    public OWLObject visitEquivAxiom(DLESyntaxParser.EquivAxiomContext ctx) {
        OWLObject lhs = visit(ctx.classExpr(0));
        OWLObject rhs = visit(ctx.classExpr(1));
        // If one side is a property expression and the other is a bare OWLClass,
        // the class must be a property that the scanner didn't classify (e.g. uppercase).
        if (lhs instanceof OWLClass && rhs instanceof OWLObjectPropertyExpression) {
            lhs = df.getOWLObjectProperty(((OWLClass) lhs).getIRI());
        }
        if (rhs instanceof OWLClass && lhs instanceof OWLObjectPropertyExpression) {
            rhs = df.getOWLObjectProperty(((OWLClass) rhs).getIRI());
        }
        if (lhs instanceof OWLObjectPropertyExpression && rhs instanceof OWLObjectPropertyExpression) {
            axioms.add(df.getOWLEquivalentObjectPropertiesAxiom(
                (OWLObjectPropertyExpression) lhs, (OWLObjectPropertyExpression) rhs));
        } else if (lhs instanceof OWLDataPropertyExpression && rhs instanceof OWLDataPropertyExpression) {
            axioms.add(df.getOWLEquivalentDataPropertiesAxiom(
                (OWLDataPropertyExpression) lhs, (OWLDataPropertyExpression) rhs));
        } else {
            axioms.add(df.getOWLEquivalentClassesAxiom(asClass(lhs), asClass(rhs)));
        }
        return null;
    }

    @Override
    public OWLObject visitFunctionalPropertyAxiom(DLESyntaxParser.FunctionalPropertyAxiomContext ctx) {
        String propName = propName(ctx.propertyExpr());
        if (dataPropertyNames.contains(propName)) {
            axioms.add(df.getOWLFunctionalDataPropertyAxiom(
                df.getOWLDataProperty(expandName(propCtxName(ctx.propertyExpr())))));
        } else {
            OWLObjectPropertyExpression prop = buildObjectProp(ctx.propertyExpr());
            axioms.add(df.getOWLFunctionalObjectPropertyAxiom(prop));
        }
        return null;
    }

    @Override
    public OWLObject visitHasKeyAxiom(DLESyntaxParser.HasKeyAxiomContext ctx) {
        OWLClassExpression ce = asClass(visit(ctx.classExpr()));
        List<OWLPropertyExpression> keys = ctx.keyExpr().name().stream()
            .map(n -> {
                OWLObject obj = nameToPropertyOrClass(n);
                if (obj instanceof OWLPropertyExpression) return (OWLPropertyExpression) obj;
                // Unclassified name in key context: default to object property
                return (OWLPropertyExpression) df.getOWLObjectProperty(expandName(n));
            })
            .collect(Collectors.toList());
        axioms.add(df.getOWLHasKeyAxiom(ce, keys));
        return null;
    }

    @Override
    public OWLObject visitAnnPropDomainAxiom(DLESyntaxParser.AnnPropDomainAxiomContext ctx) {
        IRI prop   = expandName(ctx.name(0));
        IRI domain = expandName(ctx.name(1));
        axioms.add(df.getOWLAnnotationPropertyDomainAxiom(
            df.getOWLAnnotationProperty(prop), domain));
        return null;
    }

    @Override
    public OWLObject visitAnnPropRangeAxiom(DLESyntaxParser.AnnPropRangeAxiomContext ctx) {
        IRI prop  = expandName(ctx.name(0));
        IRI range = expandName(ctx.name(1));
        axioms.add(df.getOWLAnnotationPropertyRangeAxiom(
            df.getOWLAnnotationProperty(prop), range));
        return null;
    }

    @Override
    public OWLObject visitChainedEquivSubAxiom(DLESyntaxParser.ChainedEquivSubAxiomContext ctx) {
        OWLObjectPropertyExpression a = buildObjectProp(ctx.propertyExpr(0));
        OWLObjectPropertyExpression b = buildObjectProp(ctx.propertyExpr(1));
        OWLObjectPropertyExpression c = buildObjectProp(ctx.propertyExpr(2));
        axioms.add(df.getOWLEquivalentObjectPropertiesAxiom(a, b));
        axioms.add(df.getOWLSubObjectPropertyOfAxiom(a, c));
        axioms.add(df.getOWLSubObjectPropertyOfAxiom(b, c));
        return null;
    }

    // ── Textbook role axiom syntax ────────────────────────────────────────────

    @Override
    public OWLObject visitTransitiveRoleAxiom(DLESyntaxParser.TransitiveRoleAxiomContext ctx) {
        axioms.add(df.getOWLTransitiveObjectPropertyAxiom(objectProp(ctx.name())));
        return null;
    }

    @Override
    public OWLObject visitFunctionalRoleAxiom(DLESyntaxParser.FunctionalRoleAxiomContext ctx) {
        OWLObjectProperty prop = objectProp(ctx.name());
        axioms.add(df.getOWLFunctionalObjectPropertyAxiom(prop));
        return null;
    }

    @Override
    public OWLObject visitReflexiveRoleAxiom(DLESyntaxParser.ReflexiveRoleAxiomContext ctx) {
        axioms.add(df.getOWLReflexiveObjectPropertyAxiom(objectProp(ctx.name())));
        return null;
    }

    @Override
    public OWLObject visitIrreflexiveRoleAxiom(DLESyntaxParser.IrreflexiveRoleAxiomContext ctx) {
        axioms.add(df.getOWLIrreflexiveObjectPropertyAxiom(objectProp(ctx.name())));
        return null;
    }

    @Override
    public OWLObject visitSymmetricRoleAxiom(DLESyntaxParser.SymmetricRoleAxiomContext ctx) {
        axioms.add(df.getOWLSymmetricObjectPropertyAxiom(objectProp(ctx.name())));
        return null;
    }

    @Override
    public OWLObject visitAsymmetricRoleAxiom(DLESyntaxParser.AsymmetricRoleAxiomContext ctx) {
        axioms.add(df.getOWLAsymmetricObjectPropertyAxiom(objectProp(ctx.name())));
        return null;
    }

    @Override
    public OWLObject visitDisjointRoleAxiom(DLESyntaxParser.DisjointRoleAxiomContext ctx) {
        List<OWLObjectPropertyExpression> props = ctx.name().stream()
            .map(n -> (OWLObjectPropertyExpression) df.getOWLObjectProperty(expandName(n)))
            .collect(Collectors.toList());
        axioms.add(df.getOWLDisjointObjectPropertiesAxiom(props));
        return null;
    }

    private OWLObjectProperty objectProp(DLESyntaxParser.NameContext ctx) {
        return df.getOWLObjectProperty(expandName(ctx));
    }

    @Override
    public OWLObject visitSubPropertyChainAxiom(DLESyntaxParser.SubPropertyChainAxiomContext ctx) {
        OWLObjectProperty superProp = df.getOWLObjectProperty(expandName(ctx.name()));
        List<OWLObjectPropertyExpression> chain = ctx.chainExpr().propertyExpr().stream()
            .map(p -> buildObjectProp(p))
            .collect(Collectors.toList());
        // r ∘ r ⊑ r is TransitiveObjectProperty
        if (chain.size() == 2 && chain.get(0).equals(superProp) && chain.get(1).equals(superProp)) {
            axioms.add(df.getOWLTransitiveObjectPropertyAxiom(superProp));
        } else {
            axioms.add(df.getOWLSubPropertyChainOfAxiom(chain, superProp));
        }
        return null;
    }

    @Override
    public OWLObject visitPropertyChainEquivAxiom(DLESyntaxParser.PropertyChainEquivAxiomContext ctx) {
        OWLObjectProperty prop = df.getOWLObjectProperty(expandName(ctx.name()));
        List<OWLObjectPropertyExpression> chain = ctx.chainExpr().propertyExpr().stream()
            .map(p -> buildObjectProp(p))
            .collect(Collectors.toList());
        axioms.add(df.getOWLSubPropertyChainOfAxiom(chain, prop));
        return null;
    }

    // ── Class expressions ────────────────────────────────────────────────────

    @Override
    public OWLObject visitUnionOf(DLESyntaxParser.UnionOfContext ctx) {
        OWLObject lhs = visit(ctx.classExpr());
        OWLObject rhs = visit(ctx.intersectionExpr());
        if (lhs instanceof OWLDataRange && rhs instanceof OWLDataRange) {
            // Flatten nested DataUnionOf
            if (lhs instanceof OWLDataUnionOf) {
                List<OWLDataRange> ops = new ArrayList<>(((OWLDataUnionOf) lhs).operands().collect(Collectors.toList()));
                ops.add((OWLDataRange) rhs);
                return df.getOWLDataUnionOf(ops);
            }
            return df.getOWLDataUnionOf((OWLDataRange) lhs, (OWLDataRange) rhs);
        }
        OWLClassExpression lhsCE = asClass(lhs);
        OWLClassExpression rhsCE = asClass(rhs);
        // Flatten nested ObjectUnionOf
        if (lhsCE instanceof OWLObjectUnionOf) {
            List<OWLClassExpression> ops = new ArrayList<>(((OWLObjectUnionOf) lhsCE).operands().collect(Collectors.toList()));
            ops.add(rhsCE);
            return df.getOWLObjectUnionOf(ops);
        }
        return df.getOWLObjectUnionOf(lhsCE, rhsCE);
    }

    @Override
    public OWLObject visitIntersectionOf(DLESyntaxParser.IntersectionOfContext ctx) {
        OWLObject lhs = visit(ctx.intersectionExpr());
        OWLObject rhs = visit(ctx.primary());
        if (lhs instanceof OWLDataRange && rhs instanceof OWLDataRange) {
            // Flatten nested DataIntersectionOf
            if (lhs instanceof OWLDataIntersectionOf) {
                List<OWLDataRange> ops = new ArrayList<>(((OWLDataIntersectionOf) lhs).operands().collect(Collectors.toList()));
                ops.add((OWLDataRange) rhs);
                return df.getOWLDataIntersectionOf(ops);
            }
            return df.getOWLDataIntersectionOf((OWLDataRange) lhs, (OWLDataRange) rhs);
        }
        OWLClassExpression lhsCE = asClass(lhs);
        OWLClassExpression rhsCE = asClass(rhs);
        if (lhsCE instanceof OWLObjectIntersectionOf) {
            List<OWLClassExpression> ops = new ArrayList<>(((OWLObjectIntersectionOf) lhsCE).operands().collect(Collectors.toList()));
            ops.add(rhsCE);
            return df.getOWLObjectIntersectionOf(ops);
        }
        return df.getOWLObjectIntersectionOf(lhsCE, rhsCE);
    }

    @Override
    public OWLObject visitIntersectionWrap(DLESyntaxParser.IntersectionWrapContext ctx) {
        return visit(ctx.intersectionExpr());
    }

    @Override
    public OWLObject visitPrimaryWrap(DLESyntaxParser.PrimaryWrapContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public OWLObject visitAtomWrap(DLESyntaxParser.AtomWrapContext ctx) {
        return visit(ctx.atom());
    }

    @Override
    public OWLObject visitComplement(DLESyntaxParser.ComplementContext ctx) {
        return df.getOWLObjectComplementOf(asClass(visit(ctx.primary())));
    }

    @Override
    public OWLObject visitImplicitSomeValuesFrom(DLESyntaxParser.ImplicitSomeValuesFromContext ctx) {
        // r.C shorthand for ∃r.C
        String pName = propName(ctx.propertyExpr());
        OWLObject filler = visit(ctx.primary());
        if (dataPropertyNames.contains(pName)) {
            OWLDataPropertyExpression prop = df.getOWLDataProperty(
                expandName(propCtxName(ctx.propertyExpr())));
            OWLDataRange range = asDataRange(filler);
            if (range instanceof OWLDataOneOf) {
                List<OWLLiteral> lits = ((OWLDataOneOf) range).values().collect(Collectors.toList());
                if (lits.size() == 1) return df.getOWLDataHasValue(prop, lits.get(0));
            }
            return df.getOWLDataSomeValuesFrom(prop, range);
        } else {
            OWLObjectPropertyExpression prop = buildObjectProp(ctx.propertyExpr());
            OWLClassExpression fce = asClass(filler);
            if (fce instanceof OWLObjectOneOf) {
                List<OWLIndividual> inds = ((OWLObjectOneOf) fce).individuals().collect(Collectors.toList());
                if (inds.size() == 1) return df.getOWLObjectHasValue(prop, inds.get(0));
            }
            return df.getOWLObjectSomeValuesFrom(prop, fce);
        }
    }

    @Override
    public OWLObject visitMultiRoleSomeValuesFrom(DLESyntaxParser.MultiRoleSomeValuesFromContext ctx) {
        return buildPredicateClass("\u2203", ctx.propertyExpr(), ctx.name().getText());
    }

    @Override
    public OWLObject visitMultiRoleAllValuesFrom(DLESyntaxParser.MultiRoleAllValuesFromContext ctx) {
        return buildPredicateClass("\u2200", ctx.propertyExpr(), ctx.name().getText());
    }

    @Override
    public OWLObject visitSomeValuesFrom(DLESyntaxParser.SomeValuesFromContext ctx) {
        // ∃r.Self → ObjectHasSelf(r)
        if (ctx.primary() instanceof DLESyntaxParser.AtomWrapContext) {
            DLESyntaxParser.AtomContext atom =
                ((DLESyntaxParser.AtomWrapContext) ctx.primary()).atom();
            if (atom instanceof DLESyntaxParser.SelfAtomContext) {
                return df.getOWLObjectHasSelf(buildObjectProp(ctx.propertyExpr()));
            }
            // ∃r.p where p is a unary predicate
            if (atom instanceof DLESyntaxParser.NameAtomContext) {
                String fillerName = ((DLESyntaxParser.NameAtomContext) atom).name().getText();
                if (isPredicateName(propName(ctx.propertyExpr()), fillerName)) {
                    return buildPredicateClass("\u2203",
                        Collections.singletonList(ctx.propertyExpr()), fillerName);
                }
            }
        }
        String pName = propName(ctx.propertyExpr());
        OWLObject filler = visit(ctx.primary());

        if (dataPropertyNames.contains(pName)) {
            OWLDataPropertyExpression prop = df.getOWLDataProperty(
                expandName(propCtxName(ctx.propertyExpr())));
            OWLDataRange range = asDataRange(filler);
            // ∃r.{x} with single literal → DataHasValue
            if (range instanceof OWLDataOneOf) {
                List<OWLLiteral> lits = ((OWLDataOneOf) range).values().collect(Collectors.toList());
                if (lits.size() == 1) return df.getOWLDataHasValue(prop, lits.get(0));
            }
            return df.getOWLDataSomeValuesFrom(prop, range);
        } else {
            OWLObjectPropertyExpression prop = buildObjectProp(ctx.propertyExpr());
            OWLClassExpression fce = asClass(filler);
            // ∃r.{x} with single individual → ObjectHasValue
            if (fce instanceof OWLObjectOneOf) {
                List<OWLIndividual> inds = ((OWLObjectOneOf) fce).individuals().collect(Collectors.toList());
                if (inds.size() == 1) return df.getOWLObjectHasValue(prop, inds.get(0));
            }
            return df.getOWLObjectSomeValuesFrom(prop, fce);
        }
    }

    @Override
    public OWLObject visitAllValuesFrom(DLESyntaxParser.AllValuesFromContext ctx) {
        // ∀r.p where p is a unary predicate
        if (ctx.primary() instanceof DLESyntaxParser.AtomWrapContext) {
            DLESyntaxParser.AtomContext atom =
                ((DLESyntaxParser.AtomWrapContext) ctx.primary()).atom();
            if (atom instanceof DLESyntaxParser.NameAtomContext) {
                String fillerName = ((DLESyntaxParser.NameAtomContext) atom).name().getText();
                if (isPredicateName(propName(ctx.propertyExpr()), fillerName)) {
                    return buildPredicateClass("\u2200",
                        Collections.singletonList(ctx.propertyExpr()), fillerName);
                }
            }
        }
        String pName = propName(ctx.propertyExpr());
        OWLObject filler = visit(ctx.primary());

        if (dataPropertyNames.contains(pName)) {
            OWLDataPropertyExpression prop = df.getOWLDataProperty(
                expandName(propCtxName(ctx.propertyExpr())));
            return df.getOWLDataAllValuesFrom(prop, asDataRange(filler));
        } else {
            return df.getOWLObjectAllValuesFrom(buildObjectProp(ctx.propertyExpr()), asClass(filler));
        }
    }

    @Override
    public OWLObject visitCardinalityRestriction(DLESyntaxParser.CardinalityRestrictionContext ctx) {
        int n = (int) Double.parseDouble(ctx.NUMBER().getText());
        String pName = propName(ctx.propertyExpr());
        OWLObject filler = visit(ctx.primary());
        boolean isMin  = ctx.cardSymbol().MIN()   != null;
        boolean isMax  = ctx.cardSymbol().MAX()   != null;

        if (dataPropertyNames.contains(pName)) {
            OWLDataPropertyExpression prop = df.getOWLDataProperty(
                expandName(propCtxName(ctx.propertyExpr())));
            OWLDataRange range = asDataRange(filler);
            if (isMin)       return df.getOWLDataMinCardinality(n, prop, range);
            else if (isMax)  return df.getOWLDataMaxCardinality(n, prop, range);
            else             return df.getOWLDataExactCardinality(n, prop, range);
        } else {
            OWLObjectPropertyExpression prop = buildObjectProp(ctx.propertyExpr());
            OWLClassExpression fce = asClass(filler);
            if (isMin)       return df.getOWLObjectMinCardinality(n, prop, fce);
            else if (isMax)  return df.getOWLObjectMaxCardinality(n, prop, fce);
            else             return df.getOWLObjectExactCardinality(n, prop, fce);
        }
    }

    @Override
    public OWLObject visitUnqualifiedCardinalityRestriction(
            DLESyntaxParser.UnqualifiedCardinalityRestrictionContext ctx) {
        int n = (int) Double.parseDouble(ctx.NUMBER().getText());
        String pName = propName(ctx.propertyExpr());
        boolean isMin  = ctx.cardSymbol().MIN()   != null;
        boolean isMax  = ctx.cardSymbol().MAX()   != null;

        if (dataPropertyNames.contains(pName)) {
            OWLDataPropertyExpression prop = df.getOWLDataProperty(
                expandName(propCtxName(ctx.propertyExpr())));
            if (isMin)       return df.getOWLDataMinCardinality(n, prop);
            else if (isMax)  return df.getOWLDataMaxCardinality(n, prop);
            else             return df.getOWLDataExactCardinality(n, prop);
        } else {
            OWLObjectPropertyExpression prop = buildObjectProp(ctx.propertyExpr());
            if (isMin)       return df.getOWLObjectMinCardinality(n, prop);
            else if (isMax)  return df.getOWLObjectMaxCardinality(n, prop);
            else             return df.getOWLObjectExactCardinality(n, prop);
        }
    }

    // ── Atoms ────────────────────────────────────────────────────────────────

    @Override
    public OWLObject visitInversePropertyAtom(DLESyntaxParser.InversePropertyAtomContext ctx) {
        return df.getOWLObjectInverseOf(df.getOWLObjectProperty(expandName(ctx.name())));
    }

    @Override
    public OWLObject visitTopAtom(DLESyntaxParser.TopAtomContext ctx) {
        return df.getOWLThing();
    }

    @Override
    public OWLObject visitBottomAtom(DLESyntaxParser.BottomAtomContext ctx) {
        return df.getOWLNothing();
    }

    @Override
    public OWLObject visitNameAtom(DLESyntaxParser.NameAtomContext ctx) {
        return nameToPropertyOrClass(ctx.name());
    }

    @Override
    public OWLObject visitOneOfAtom(DLESyntaxParser.OneOfAtomContext ctx) {
        List<DLESyntaxParser.OneOfElemContext> elems = ctx.oneOfList().oneOfElem();
        boolean hasLiterals = elems.stream()
            .anyMatch(e -> e instanceof DLESyntaxParser.LiteralElemContext);
        if (hasLiterals) {
            List<OWLLiteral> lits = elems.stream()
                .map(this::buildLiteral)
                .collect(Collectors.toList());
            return df.getOWLDataOneOf(lits);
        } else {
            List<OWLIndividual> inds = elems.stream()
                .map(e -> (OWLIndividual) df.getOWLNamedIndividual(
                    expandName(((DLESyntaxParser.IndividualElemContext) e).name())))
                .collect(Collectors.toList());
            return df.getOWLObjectOneOf(inds);
        }
    }

    @Override
    public OWLObject visitParenAtom(DLESyntaxParser.ParenAtomContext ctx) {
        return visit(ctx.classExpr());
    }

    @Override
    public OWLObject visitEmptyAtom(DLESyntaxParser.EmptyAtomContext ctx) {
        // () is an empty data range (no valid values)
        return df.getOWLDataOneOf();
    }

    // ── Comment capture ──────────────────────────────────────────────────────

    /**
     * For each statement, collect any hidden {@code #} comment tokens that immediately
     * precede it and attach them as {@code dle:comment} annotation assertions on the
     * first named entity referenced in the statement.
     */
    @Override
    public OWLObject visitStatement(DLESyntaxParser.StatementContext ctx) {
        OWLObject result = visitChildren(ctx);
        if (tokenStream == null) return result;

        List<Token> hidden = tokenStream.getHiddenTokensToLeft(
            ctx.start.getTokenIndex(), Token.HIDDEN_CHANNEL);
        if (hidden == null || hidden.isEmpty()) return result;

        // Strip the file header: the initial contiguous block of comment lines that
        // begins at line 1. The header is terminated by the first blank line (gap in
        // line numbers) or by the first non-comment content. Because WS is skipped,
        // blank lines appear as gaps between consecutive LINE_COMMENT line numbers.
        int realStart = 0;
        if (hidden.get(0).getLine() == 1) {
            int prevLine = 0;
            while (realStart < hidden.size()) {
                int line = hidden.get(realStart).getLine();
                if (realStart == 0 || line == prevLine + 1) {
                    prevLine = line;
                    realStart++;
                } else {
                    break; // blank line found — header ends before this token
                }
            }
        }
        hidden = hidden.subList(realStart, hidden.size());
        if (hidden.isEmpty()) return result;

        IRI subjectIRI = findFirstNameIRI(ctx);
        if (subjectIRI == null) return result;

        // Store the entire comment block as a single multi-line literal so that
        // within-block order is preserved even after OWL axiom sorting.
        StringBuilder sb = new StringBuilder();
        for (Token tok : hidden) {
            String text = tok.getText();
            if (text.startsWith("#")) text = text.substring(1);
            if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        if (sb.length() > 0) {
            OWLAnnotationProperty commentProp = df.getOWLAnnotationProperty(DLE_COMMENT_IRI);
            axioms.add(df.getOWLAnnotationAssertionAxiom(
                commentProp, subjectIRI, df.getOWLLiteral(sb.toString())));
        }
        return result;
    }

    /** Returns the IRI of the first NAME or PREFIXED_NAME token in the statement, or null. */
    private IRI findFirstNameIRI(DLESyntaxParser.StatementContext ctx) {
        int start = ctx.start.getTokenIndex();
        int stop  = ctx.stop != null ? ctx.stop.getTokenIndex() : start;
        for (int i = start; i <= stop; i++) {
            Token tok = tokenStream.get(i);
            int type = tok.getType();
            if (type == DLESyntaxLexer.NAME || type == DLESyntaxLexer.PREFIXED_NAME) {
                return expandNameText(tok.getText());
            }
        }
        return null;
    }

    /** Resolves a raw name token text (bare or prefixed) to a full IRI using the prefix map. */
    private IRI expandNameText(String text) {
        int colon = text.indexOf(':');
        if (colon > 0) {
            String prefix = text.substring(0, colon + 1);
            String local  = text.substring(colon + 1);
            String base   = prefixes.get(prefix);
            if (base == null) return null;
            return IRI.create(base + local);
        }
        String base = prefixes.getOrDefault(":", "");
        return IRI.create(base + text);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Expands an iriRef (either angle-bracket IRI or prefixed/bare name) to a full IRI. */
    private IRI expandIriRef(DLESyntaxParser.IriRefContext ctx) {
        if (ctx.IRI() != null) {
            String raw = ctx.IRI().getText();
            return IRI.create(raw.substring(1, raw.length() - 1)); // strip < >
        }
        return expandName(ctx.name());
    }

    /** Expands a name context to a full IRI using the prefix map. */
    private IRI expandName(DLESyntaxParser.NameContext ctx) {
        String text = ctx.getText();
        if (ctx.PREFIXED_NAME() != null) {
            int colon = text.indexOf(':');
            String prefixLabel = text.substring(0, colon + 1); // "xsd:"
            String local       = text.substring(colon + 1);
            String base = prefixes.get(prefixLabel);
            if (base == null) throw new IllegalStateException("Unknown prefix: " + prefixLabel);
            return IRI.create(base + local);
        }
        // Bare name — use default prefix ":"
        String base = prefixes.getOrDefault(":", "");
        return IRI.create(base + text);
    }

    /**
     * Returns an OWLObjectProperty, OWLDataProperty, or OWLClass depending on
     * how the name was classified by the scanner.
     */
    /** If classExpr is a flat intersection of bare names only, returns those NameContexts; else null. */
    private List<DLESyntaxParser.NameContext> allIntersectedNameCtxs(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return null;
        var inter = ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        List<DLESyntaxParser.NameContext> names = new ArrayList<>();
        return collectIntersectionNameCtxs(inter, names) ? names : null;
    }

    private boolean collectIntersectionNameCtxs(DLESyntaxParser.IntersectionExprContext inter,
                                                List<DLESyntaxParser.NameContext> names) {
        if (inter instanceof DLESyntaxParser.PrimaryWrapContext) {
            DLESyntaxParser.NameContext n = primaryNameCtx(((DLESyntaxParser.PrimaryWrapContext) inter).primary());
            if (n == null) return false;
            names.add(n);
            return true;
        }
        if (inter instanceof DLESyntaxParser.IntersectionOfContext) {
            var iof = (DLESyntaxParser.IntersectionOfContext) inter;
            DLESyntaxParser.NameContext n = primaryNameCtx(iof.primary());
            if (n == null) return false;
            names.add(n);
            return collectIntersectionNameCtxs(iof.intersectionExpr(), names);
        }
        return false;
    }

    private DLESyntaxParser.NameContext primaryNameCtx(DLESyntaxParser.PrimaryContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.AtomWrapContext)) return null;
        var atom = ((DLESyntaxParser.AtomWrapContext) ctx).atom();
        if (!(atom instanceof DLESyntaxParser.NameAtomContext)) return null;
        return ((DLESyntaxParser.NameAtomContext) atom).name();
    }

    private boolean isBottomClassExpr(DLESyntaxParser.ClassExprContext ctx) {
        if (!(ctx instanceof DLESyntaxParser.IntersectionWrapContext)) return false;
        var inter = ((DLESyntaxParser.IntersectionWrapContext) ctx).intersectionExpr();
        if (!(inter instanceof DLESyntaxParser.PrimaryWrapContext)) return false;
        var prim = ((DLESyntaxParser.PrimaryWrapContext) inter).primary();
        if (!(prim instanceof DLESyntaxParser.AtomWrapContext)) return false;
        return ((DLESyntaxParser.AtomWrapContext) prim).atom() instanceof DLESyntaxParser.BottomAtomContext;
    }

    /**
     * Returns true if {@code fillerName} should be treated as a predicate rather
     * than a class in a restriction with role {@code propName}.
     */
    private boolean isPredicateName(String propName, String fillerName) {
        // Explicitly declared predicates take priority over property type
        if (predicateNames.contains(fillerName)) return true;
        // Data property fillers that are not predicates are data ranges
        if (dataPropertyNames.contains(propName)) return false;
        // Already known as a property (shouldn't be a filler, but guard anyway)
        if (objectPropertyNames.contains(fillerName) || dataPropertyNames.contains(fillerName)) return false;
        // Prefixed names (e.g. owl:Thing, xsd:string) are never predicates
        if (fillerName.contains(":")) return false;
        // Lowercase-first convention: treat as predicate
        return !fillerName.isEmpty() && Character.isLowerCase(fillerName.charAt(0));
    }

    /**
     * Creates (or re-uses) a DLE predicate-restriction class:
     * declares it as OWLClass, annotates it with its expression as rdfs:label,
     * lazily adds the dle: prefix, and returns the class.
     */
    private OWLClassExpression buildPredicateClass(String quantifier,
            List<DLESyntaxParser.PropertyExprContext> roles, String predName) {
        // Build canonical expression string: ∃r1,r2.pred  or  ∀r1,r2.pred
        StringBuilder sb = new StringBuilder(quantifier);
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(roles.get(i).getText());
        }
        sb.append(".").append(predName);
        String expr = sb.toString();

        String prefix = "\u2203".equals(quantifier) ? "E_" : "A_";
        IRI classIRI = IRI.create(DLE_NS + prefix + predName + "_" + String.format("%08x", expr.hashCode()));
        OWLClass dleClass = df.getOWLClass(classIRI);

        // Declare class and annotate with its expression (addAxioms deduplicates)
        axioms.add(df.getOWLDeclarationAxiom(dleClass));
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
            classIRI, df.getOWLLiteral(expr)));

        return dleClass;
    }

    private OWLObject nameToPropertyOrClass(DLESyntaxParser.NameContext ctx) {
        String text = ctx.getText();
        IRI iri = expandName(ctx);
        if (objectPropertyNames.contains(text)) return df.getOWLObjectProperty(iri);
        if (dataPropertyNames.contains(text))   return df.getOWLDataProperty(iri);
        // Names in well-known datatype namespaces are data ranges, not classes
        if (text.startsWith("xsd:") || text.startsWith("rdf:") || text.startsWith("rdfs:")) {
            return df.getOWLDatatype(iri);
        }
        return df.getOWLClass(iri);
    }

    private OWLObjectPropertyExpression buildObjectProp(DLESyntaxParser.PropertyExprContext ctx) {
        if (ctx instanceof DLESyntaxParser.InversePropertyExprContext) {
            IRI iri = expandName(((DLESyntaxParser.InversePropertyExprContext) ctx).name());
            return df.getOWLObjectInverseOf(df.getOWLObjectProperty(iri));
        }
        IRI iri = expandName(((DLESyntaxParser.SimplePropertyExprContext) ctx).name());
        return df.getOWLObjectProperty(iri);
    }

    /** Returns the local text of the name in a propertyExpr (for type lookup). */
    private String propName(DLESyntaxParser.PropertyExprContext ctx) {
        return propCtxName(ctx).getText();
    }

    private DLESyntaxParser.NameContext propCtxName(DLESyntaxParser.PropertyExprContext ctx) {
        if (ctx instanceof DLESyntaxParser.InversePropertyExprContext) {
            return ((DLESyntaxParser.InversePropertyExprContext) ctx).name();
        }
        return ((DLESyntaxParser.SimplePropertyExprContext) ctx).name();
    }

    private OWLClassExpression asClass(OWLObject obj) {
        if (obj instanceof OWLClassExpression) return (OWLClassExpression) obj;
        throw new IllegalStateException("Expected class expression, got: " + obj);
    }

    private OWLDataRange asDataRange(OWLObject obj) {
        if (obj instanceof OWLDataRange) return (OWLDataRange) obj;
        // ⊤ in data context → top data type
        if (obj instanceof OWLClass && ((OWLClass) obj).isOWLThing()) return df.getTopDatatype();
        // A named class whose IRI is an XSD/RDF datatype → treat as OWLDatatype
        if (obj instanceof OWLClass) {
            IRI iri = ((OWLClass) obj).getIRI();
            String ns = iri.getNamespace();
            if (ns.startsWith("http://www.w3.org/2001/XMLSchema#")
                    || ns.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
                return df.getOWLDatatype(iri);
            }
        }
        throw new IllegalStateException("Expected data range, got: " + obj);
    }

    private boolean isOWLThing(OWLObject obj) {
        return obj instanceof OWLClass && ((OWLClass) obj).isOWLThing();
    }

    private OWLLiteral buildLiteral(DLESyntaxParser.OneOfElemContext ctx) {
        DLESyntaxParser.LiteralContext lit = ((DLESyntaxParser.LiteralElemContext) ctx).literal();
        if (lit instanceof DLESyntaxParser.StringLiteralContext) {
            return stringLiteral(lit.getText());
        }
        if (lit instanceof DLESyntaxParser.NumberLiteralContext) {
            String s = lit.getText();
            if (s.contains(".")) return df.getOWLLiteral(Double.parseDouble(s));
            return df.getOWLLiteral(Integer.parseInt(s));
        }
        // BoolLiteral
        return df.getOWLLiteral(Boolean.parseBoolean(lit.getText()));
    }

    // ── Datatype restrictions ─────────────────────────────────────────────────

    @Override
    public OWLObject visitNumericDataRangeAtom(DLESyntaxParser.NumericDataRangeAtomContext ctx) {
        OWLDatatype base = df.getOWLDatatype(expandName(ctx.name()));
        List<OWLFacetRestriction> facets = ctx.numericFacet().stream()
            .map(f -> {
                OWLFacet facet = f.MIN() != null ? OWLFacet.MIN_INCLUSIVE
                              : f.MAX() != null ? OWLFacet.MAX_INCLUSIVE
                              : f.GT()  != null ? OWLFacet.MIN_EXCLUSIVE
                              :                   OWLFacet.MAX_EXCLUSIVE;
                String numText = f.NUMBER().getText();
                OWLLiteral value = numText.contains(".")
                    ? df.getOWLLiteral(Double.parseDouble(numText))
                    : df.getOWLLiteral(Integer.parseInt(numText));
                return df.getOWLFacetRestriction(facet, value);
            })
            .collect(Collectors.toList());
        return df.getOWLDatatypeRestriction(base, facets);
    }

    @Override
    public OWLObject visitDataRangeAtom(DLESyntaxParser.DataRangeAtomContext ctx) {
        return visit(ctx.datatypeRestriction());
    }

    @Override
    public OWLObject visitDatatypeRestriction(DLESyntaxParser.DatatypeRestrictionContext ctx) {
        OWLDatatype base = df.getOWLDatatype(expandName(ctx.name()));
        List<OWLFacetRestriction> facets = ctx.facet().stream()
            .map(f -> df.getOWLFacetRestriction(
                    facetFromName(f.name()),
                    buildFacetLiteral(f.literal())))
            .collect(Collectors.toList());
        return df.getOWLDatatypeRestriction(base, facets);
    }

    /** Resolves a facet name context — bare keyword or prefixed IRI — to an OWLFacet. */
    private OWLFacet facetFromName(DLESyntaxParser.NameContext nameCtx) {
        if (nameCtx.PREFIXED_NAME() != null) {
            IRI iri = expandName(nameCtx);
            for (OWLFacet f : OWLFacet.values()) {
                if (f.getIRI().equals(iri)) return f;
            }
            throw new IllegalStateException("Unknown facet IRI: " + iri);
        }
        return facetFromKeyword(nameCtx.getText());
    }

    private OWLFacet facetFromKeyword(String keyword) {
        switch (keyword) {
            case "matches":        return OWLFacet.PATTERN;
            case "length":         return OWLFacet.LENGTH;
            case "minLength":      return OWLFacet.MIN_LENGTH;
            case "maxLength":      return OWLFacet.MAX_LENGTH;
            case "min":            return OWLFacet.MIN_INCLUSIVE;
            case "max":            return OWLFacet.MAX_INCLUSIVE;
            case "minExclusive":   return OWLFacet.MIN_EXCLUSIVE;
            case "maxExclusive":   return OWLFacet.MAX_EXCLUSIVE;
            case "totalDigits":    return OWLFacet.TOTAL_DIGITS;
            case "fractionDigits": return OWLFacet.FRACTION_DIGITS;
            case "langRange":      return OWLFacet.LANG_RANGE;
            default: throw new IllegalStateException("Unknown facet keyword: " + keyword);
        }
    }

    private OWLLiteral buildFacetLiteral(DLESyntaxParser.LiteralContext lit) {
        if (lit instanceof DLESyntaxParser.StringLiteralContext) {
            return stringLiteral(lit.getText());
        }
        if (lit instanceof DLESyntaxParser.NumberLiteralContext) {
            String s = lit.getText();
            if (s.contains(".")) return df.getOWLLiteral(Double.parseDouble(s));
            return df.getOWLLiteral(Integer.parseInt(s));
        }
        return df.getOWLLiteral(Boolean.parseBoolean(lit.getText()));
    }

    /** Strips surrounding double-quotes and unescapes basic sequences. */
    private OWLLiteral stringLiteral(String tokenText) {
        String inner = tokenText.substring(1, tokenText.length() - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
        return df.getOWLLiteral(inner);
    }
}
