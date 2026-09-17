package org.semanticweb.owlapi.dlesyntax;

import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxObjectRenderer;
import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntax;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDatatypeDefinitionAxiom;
import org.semanticweb.owlapi.model.OWLDatatypeRestriction;
import org.semanticweb.owlapi.model.OWLFacetRestriction;
import org.semanticweb.owlapi.model.OWLAsymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
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
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNegativeObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
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
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLObjectOneOf;
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

    /** Creates a new renderer with no ontology or prefix manager set. */
    public DLESyntaxObjectRenderer() {}

    @Nullable
    private PrefixManager prefixManager;
    @Nullable
    private OWLOntology ontology;

    /**
     * Sets the ontology used to resolve DLE-namespace IRIs back to their original labels.
     *
     * @param ontology the ontology to use for label lookup, or {@code null} to clear it
     */
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
     *
     * @param pm the prefix manager to use, or {@code null} to clear it
     */
    public void setPrefixManager(@Nullable PrefixManager pm) {
        prefixManager = pm;
        if (pm != null) {
            setShortFormProvider(entity -> {
                String curie = pm.getPrefixIRI(entity.getIRI());
                if (curie == null) curie = computeCurie(pm, entity.getIRI().toString());
                return curie != null ? stripDefaultPrefix(curie)
                    : entity.getIRI().getRemainder().orElse(entity.getIRI().toString());
            });
        }
    }

    /**
     * Strips the leading colon from a CURIE that belongs to the default (empty)
     * namespace prefix, e.g. {@code ":Dog"} becomes {@code "Dog"}.
     * Prefixed names with an explicit prefix (e.g. {@code "xsd:boolean"}) are unchanged.
     *
     * <p>A local part beginning with a digit keeps its colon. The bare form has no
     * spelling for it — {@code NAME} requires a NameStart, so {@code 762705008}
     * would lex as a number and the document would not parse. {@code :762705008} is
     * the {@code DEFAULT_NAME} form and reads back to the same IRI.
     */
    /**
     * Words the grammar keeps for itself, which therefore cannot be written bare.
     *
     * <p>{@code Self} is the {@code ObjectHasSelf} filler, {@code true} and {@code false}
     * are the boolean literals, and {@code key} opens a key expression. An entity named for
     * one of them produced a document that would not reload — or, in the worst case, one
     * that reloaded and meant something else: a class {@code :Self} as the filler of a
     * restriction was written {@code A ⊑ ∃r.Self} and came back {@code ObjectHasSelf(:r)},
     * with the class gone and the axiom changed.
     *
     * <p>They are only a problem bare. Prefixed, they are ordinary names — {@code ex:Self}
     * lexes as one token — so the fix is to write the prefix rather than to refuse.
     */
    private static final java.util.Set<String> RESERVED_LOCAL_NAMES =
        java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
            java.util.Arrays.asList("Self", "true", "false", "key")));

    static boolean isReservedLocalName(String local) {
        return RESERVED_LOCAL_NAMES.contains(local);
    }

    private String stripDefaultPrefix(String curie) {
        if (!curie.startsWith(":")) return curie;
        String local = curie.substring(1);
        if (spellableOnlyWithPrefix(local)) return curie;
        if (isReservedLocalName(local)) {
            // Bare, this would be the keyword. Any other prefix on the same namespace
            // names the same entity and reads back as a name; the storer makes sure one
            // exists.
            String prefixed = alternativePrefixFor(local);
            if (prefixed != null) return prefixed;
        }
        return local;
    }

    /**
     * The same local name under a prefix other than the default, if one is declared.
     *
     * <p>Only the default prefix forces a bare name, so any other prefix bound to the same
     * namespace is a spelling that works.
     */
    @Nullable
    private String alternativePrefixFor(String local) {
        if (prefixManager == null) return null;
        String defaultNamespace = prefixManager.getPrefixName2PrefixMap().get(":");
        if (defaultNamespace == null) return null;
        for (Map.Entry<String, String> entry
                : prefixManager.getPrefixName2PrefixMap().entrySet()) {
            if (!":".equals(entry.getKey()) && defaultNamespace.equals(entry.getValue())) {
                return entry.getKey() + local;
            }
        }
        return null;
    }

    /**
     * Whether a default-namespace local part needs its colon kept, i.e. it is a legal
     * {@code DEFAULT_NAME} but not a legal bare {@code NAME}.
     *
     * <p>{@code DEFAULT_NAME : ':' [0-9] NameChar*} — an ASCII digit, then name characters.
     * {@code Character.isDigit} is not the right test: it is true for the whole Unicode Nd
     * category, so a name like {@code ٠x} (Arabic-Indic zero) was written {@code :٠x}, which
     * the lexer rejects, even though the bare form was legal and round-tripped before.
     *
     * <p>The rest of the local part is checked too. Only {@code charAt(0)} was, so
     * {@code 1.Dog} was written {@code :1.Dog} — a dot is not a name character — and read
     * back as a restriction over a property {@code 1}, silently becoming a different
     * ontology. Returning false here keeps the bare form, which fails loudly instead.
     */
    private static boolean spellableOnlyWithPrefix(String local) {
        if (local.isEmpty() || local.charAt(0) < '0' || local.charAt(0) > '9') return false;
        for (int i = 1; i < local.length(); i++) {
            if (!isNameChar(local.charAt(i))) return false;
        }
        return true;
    }

    /** {@code NameChar} from the grammar: {@code NameStart | [0-9] | '-'}. */
    private static boolean isNameChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c == '-' || c == '_') return true;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        return (c >= '\u00C0' && c <= '\u02FF') || (c >= '\u0370' && c <= '\u037D')
            || (c >= '\u037F' && c <= '\u1FFF') || (c >= '\u200C' && c <= '\u200D')
            || (c >= '\u2070' && c <= '\u207A') || (c >= '\u207C' && c <= '\u218F')
            || (c >= '\u2C00' && c <= '\u2FEF') || (c >= '\u3001' && c <= '\uD7FF')
            || (c >= '\uF900' && c <= '\uFDCF') || (c >= '\uFDF0' && c <= '\uFFFD');
    }

    /**
     * Computes a CURIE by longest-namespace-prefix matching against the registered
     * prefix map, without any XML NCName constraints on the local part.
     * Used as a fallback when {@link PrefixManager#getPrefixIRI} returns null because
     * the local part is not a valid XML NCName (e.g. starts with a digit).
     */
    @Nullable
    private static String computeCurie(PrefixManager pm, String iriStr) {
        String bestPrefix = null;
        String bestNs = null;
        for (Map.Entry<String, String> e : pm.getPrefixName2PrefixMap().entrySet()) {
            String ns = e.getValue();
            if (iriStr.startsWith(ns) && (bestNs == null || ns.length() > bestNs.length())) {
                bestPrefix = e.getKey();
                bestNs = ns;
            }
        }
        return bestNs != null ? bestPrefix + iriStr.substring(bestNs.length()) : null;
    }

    /**
     * Returns the short form of an IRI using the prefix manager if available,
     * falling back to plain prefix matching (for local parts that are not valid
     * XML NCNames, e.g. numeric SNOMED-CT codes), then to the IRI remainder.
     */
    private String shortFormIRI(IRI iri) {
        if (prefixManager != null) {
            String curie = prefixManager.getPrefixIRI(iri);
            if (curie == null) curie = computeCurie(prefixManager, iri.toString());
            if (curie != null) return stripDefaultPrefix(curie);
        }
        return iri.getRemainder().orElseThrow(() ->
            new IllegalStateException("No prefix/namespace found for IRI: " + iri));
    }

    /** Package-private: used by {@link DLESyntaxStorerBase} to render IRIs consistently. */
    String shortForm(IRI iri) {
        return shortFormIRI(iri);
    }

    /** Renders an OWLLiteral as a DLE literal token: NUMBER/BOOL unquoted, strings quoted. */
    /**
     * Writes a literal, wherever the visitor reaches one.
     *
     * <p>Without this override, {@code accept(this)} on a literal fell through to the
     * inherited DL renderer, which writes the bare lexical form: no quotes, no language tag,
     * no escaping. The new assertion forms render their value that way, so
     * {@code DataPropertyAssertion(:p :bob "Robert")} was written {@code (bob,Robert):p} and
     * read back as an <em>object</em> property assertion with an invented individual — and
     * a value containing a space produced a document that would not load at all. Integers
     * and booleans were the only kinds that survived, which is what a test using
     * {@code getOWLLiteral(7)} could not see.
     *
     * <p>Every literal now goes through {@link #renderLiteral}, so a new call site cannot
     * reintroduce this by forgetting to ask.
     */
    @Override
    public void visit(OWLLiteral node) {
        write(renderLiteral(node));
    }

    /**
     * The grammar's {@code NUMBER} token, which is the only bare form a value may take.
     *
     * <p>Not every numeric literal can be spelled that way. {@code xsd:double} admits
     * {@code NaN}, {@code INF} and exponents, and {@code xsd:integer} is unbounded, so
     * testing the datatype is not enough — the lexical form has to be tested too.
     */
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    /**
     * A literal in the form the reader will give back.
     *
     * <p>Numbers and booleans are written bare, everything else quoted. The bare form is
     * conditional on the spelling and not just on the datatype: {@code "NaN"^^xsd:double}
     * wrote {@code (a,NaN):d}, which came back an <em>object</em> property assertion against
     * an invented individual {@code :NaN} — silently, with the document still loading. That
     * is the same corruption as an unquoted string, and it survived the fix for the string
     * case because this branch was never guarded.
     */
    private String renderLiteral(OWLLiteral lit) {
        if (lit.isBoolean()) return lit.getLiteral();
        if ((lit.isInteger() || lit.isDouble() || lit.isFloat())
                && NUMBER.matcher(lit.getLiteral()).matches()) {
            return lit.getLiteral();
        }
        return quoted(lit);
    }

    /**
     * A string literal, with its language tag when it has one.
     *
     * <p>The tag used to be dropped, silently and everywhere: 177 of the 292 annotation
     * assertions in one corpus document are tagged, and every one came back as a plain
     * string — a different literal, and a different axiom. Any multilingual vocabulary lost
     * every language it had.
     */
    /** Implicit, and so never written: a plain string is just a string. */
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private String quoted(OWLLiteral lit) {
        String escaped = lit.getLiteral().replace("\\", "\\\\").replace("\"", "\\\"");
        String text = "\"" + escaped + "\"";
        if (lit.hasLang()) {
            return text + "@" + lit.getLang();
        }
        // A tag and a datatype are mutually exclusive, so at most one of these appears.
        // xsd:string is left off: it is what a bare string already means, and writing it
        // would change every existing document for no gain.
        String datatype = lit.getDatatype().getIRI().toString();
        return XSD_STRING.equals(datatype) ? text
            : text + "^^" + shortFormIRI(lit.getDatatype().getIRI());
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
            return quoted((OWLLiteral) value);
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

    // ── Assertions about individuals ────────────────────────────────────────
    //
    // The textbook spelling, from Introduction to Description Logic: `a:C`, `(a,b):r`,
    // `¬(a,b):r`, and the data forms. The inherited renderer writes the functional-ish
    // `C(a)` and `r(a,b)`, which DLe's grammar cannot read at all — `Animal(bob)` is the
    // head of a predicate definition and fails asking for `≝`.
    //
    // The class assertion is always written spaced. `a:C` is the same sequence of
    // characters as a prefixed name, and the reader resolves it from the declared
    // prefixes — but it can only do so when the individual's name is not itself a declared
    // prefix. Writing the space means the output never depends on that.
    //
    // Writing these also removes the doubled negation the inherited renderer produces:
    // its visit method writes ¬ and then calls writePropertyAssertion, which tests the
    // axiom type and writes ¬ again. Two signs for one negation reads as no negation at
    // all, so every negative assertion it emitted meant the opposite of the axiom.

    @Override
    public void visit(OWLClassAssertionAxiom axiom) {
        axiom.getIndividual().accept(this);
        write(" : ");
        writeNested(axiom.getClassExpression());
    }

    @Override
    public void visit(OWLObjectPropertyAssertionAxiom axiom) {
        writeAssertion(axiom.getSubject(), axiom.getObject(), axiom.getProperty(), false);
    }

    @Override
    public void visit(OWLNegativeObjectPropertyAssertionAxiom axiom) {
        writeAssertion(axiom.getSubject(), axiom.getObject(), axiom.getProperty(), true);
    }

    @Override
    public void visit(OWLDataPropertyAssertionAxiom axiom) {
        writeAssertion(axiom.getSubject(), axiom.getObject(), axiom.getProperty(), false);
    }

    @Override
    public void visit(OWLNegativeDataPropertyAssertionAxiom axiom) {
        writeAssertion(axiom.getSubject(), axiom.getObject(), axiom.getProperty(), true);
    }

    /** Writes {@code (subject,object):property}, negated or not. */
    private void writeAssertion(OWLObject subject, OWLObject object, OWLObject property,
                                boolean negated) {
        if (negated) write(DLSyntax.NOT);
        write("(");
        subject.accept(this);
        write(",");
        object.accept(this);
        write("):");
        property.accept(this);
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

    /**
     * An equivalence of one operand is not written, because a lone name is not a statement.
     *
     * <p>{@code EquivalentClasses(:A :A)} is vacuous and OWL API collapses it to a single
     * operand, whereupon the inherited renderer wrote a bare {@code A} on its own line and
     * the document stopped loading — the same shape of defect as the one-property
     * {@code Disj(p)}, from the same cause. Two or more operands delegate unchanged, so the
     * chained form {@code A \u2261 B \u2261 C} is untouched.
     */
    @Override
    public void visit(OWLEquivalentClassesAxiom axiom) {
        if (axiom.classExpressions().limit(2).count() < 2) return;
        super.visit(axiom);
    }

    @Override
    public void visit(OWLEquivalentObjectPropertiesAxiom axiom) {
        if (axiom.properties().limit(2).count() < 2) return;
        super.visit(axiom);
    }

    @Override
    public void visit(OWLEquivalentDataPropertiesAxiom axiom) {
        if (axiom.properties().limit(2).count() < 2) return;
        super.visit(axiom);
    }

    /**
     * A datatype definition, written as the equivalence it is.
     *
     * <p>Nothing was written for one at all, so the axiom and the datatype both disappeared.
     * DL has no separate notation, but a definition *is* an equivalence and a data range on
     * one side makes it unambiguous — a class equivalence cannot have one.
     */
    @Override
    public void visit(OWLDatatypeDefinitionAxiom axiom) {
        write(shortFormIRI(axiom.getDatatype().getIRI()));
        write(" ");
        write(DLSyntax.EQUIVALENT_TO);
        write(" ");
        writeNested(axiom.getDataRange());
    }

    /**
     * A disjoint union, written as the two things it says.
     *
     * <p>DL has no notation for it, and the inherited renderer reached for {@code =} —
     * producing <code>A=B &sqcup; C</code>, where {@code =} is the cardinality and identity
     * operator and the line is not a statement at all. The document would not load.
     *
     * <p>{@code DisjointUnion(A, B, C)} says two things: A is the union of B and C, and B
     * and C are disjoint. Both have notation, so both are written, and the pair reads back
     * as the pair. That is two axioms where there was one — equivalent, and the only
     * alternative would be inventing a symbol for a construct DL does not have.
     */
    @Override
    public void visit(OWLDisjointUnionAxiom axiom) {
        visit(axiom.getOWLEquivalentClassesAxiom());
        write("\n");
        visit(axiom.getOWLDisjointClassesAxiom());
    }

    /**
     * Class disjointness, written pairwise as {@code X \u2291 \u00ac Y}.
     *
     * <p>The inherited renderer had two faults here, and each produced a document that said
     * the wrong thing or nothing at all.
     *
     * <p>It wrote the complemented operand without nesting it, so a complex one lost its
     * parentheses and changed meaning completely:
     * {@code DisjointClasses(ObjectIntersectionOf(:A :B) :C)} became
     * <code>C &sqsube; &not; A &sqcap; B</code>, which reads as
     * <code>C &sqsube; (&not;A) &sqcap; B</code> — C is a B that is not an A, rather than C
     * being disjoint from A-and-B. Exit 0, document loads, different axiom. The direct
     * {@code SubClassOf(:C ObjectComplementOf(ObjectIntersectionOf(:A :B)))} was written
     * correctly as <code>C &sqsube; &not;(A &sqcap; B)</code>, which is where the shape for
     * this came from.
     *
     * <p>And it joined the pairs with commas on one line —
     * <code>A &sqsube; &not; B, A &sqsube; &not; C, B &sqsube; &not; C</code> — which is not
     * a statement DLe has, so a three-way disjointness stopped the document loading. One
     * statement per line reads back as the pairwise axioms, which is what a disjointness of
     * three or more means.
     */
    @Override
    public void visit(OWLDisjointClassesAxiom axiom) {
        List<OWLClassExpression> operands =
            axiom.classExpressions().collect(java.util.stream.Collectors.toList());
        // Vacuous, and `A` alone is not a statement; see visit(OWLEquivalentClassesAxiom).
        if (operands.size() < 2) return;
        boolean firstPair = true;
        for (int i = 0; i < operands.size(); i++) {
            for (int j = i + 1; j < operands.size(); j++) {
                if (!firstPair) write("\n");
                firstPair = false;
                writeNested(operands.get(i));
                write(" ");
                write(DLSyntax.SUBCLASS);
                write(" ");
                write(DLSyntax.NOT);
                writeNested(operands.get(j));
            }
        }
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

    /**
     * An n-ary role axiom, as in {@code Disj(p, q)}.
     *
     * <p>The grammar is {@code DISJ '(' name (',' name)+ ')'}, so two names is the minimum.
     * A degenerate one-property axiom does arrive — OWL API accepts
     * {@code DisjointObjectProperties(:p :p)} and collapses the pair — and it used to be
     * written {@code Disj(p)}, which stopped the whole document reloading. It says nothing,
     * so nothing is written for it.
     *
     * <p>Dropping it is only defensible because it is vacuous. The general problem of an
     * axiom the writer cannot spell is filed as #22 and #23, and wants a channel that tells
     * the user rather than a decision taken quietly here.
     */
    private void writeNaryRoleAxiom(String keyword, java.util.stream.Stream<? extends OWLPropertyExpression> props) {
        List<OWLPropertyExpression> list = props.collect(java.util.stream.Collectors.toList());
        if (list.size() < 2) return;
        write(keyword);
        write("(");
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
            && facets.stream().allMatch(DLESyntaxObjectRenderer::isCompactFacet);
        if (compact) {
            // xsd:integer[≥1 ⊓ ≤5]
            write(shortFormIRI(restriction.getDatatype().getIRI()));
            write("[");
            for (int i = 0; i < facets.size(); i++) {
                if (i > 0) write(" \u2293 ");
                OWLFacetRestriction fr = facets.get(i);
                write(numericFacetSymbol(fr.getFacet()));
                // Safe because isCompactFacet has established that this value is spelled
                // as a NUMBER; the compact bracket carries no other form. Quoting it here
                // instead would emit `[\u2265"1"]`, which the grammar rejects.
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

    /**
     * Whether the reader will rebuild this value's datatype from its spelling alone.
     *
     * <p>A bare number carries no datatype, and the reader types it by looking at the text:
     * digits give {@code xsd:integer}, a decimal point gives {@code xsd:double}. Only those
     * two survive being written bare.
     */
    private static boolean reconstructsFromSpellingAlone(OWLLiteral literal) {
        String datatype = literal.getDatatype().getIRI().toString();
        return literal.getLiteral().contains(".")
            ? "http://www.w3.org/2001/XMLSchema#double".equals(datatype)
            : "http://www.w3.org/2001/XMLSchema#integer".equals(datatype);
    }

    /**
     * Whether a facet can take the compact bracket form, as in {@code xsd:int[\u22651]}.
     *
     * <p>Two things have to hold, and each was assumed. Only the four ordered bounds have a
     * symbol: {@code totalDigits} fell through to its short form and was written hard against
     * its value as {@code [totalDigits5]}, which is not a token the grammar has. And the
     * compact form's value is a {@code NUMBER}, so an ordered bound whose value is a date or
     * an exponent does not fit it either. Both now take the keyword form, which reads back.
     */
    private static boolean isCompactFacet(OWLFacetRestriction fr) {
        switch (fr.getFacet()) {
            case MIN_INCLUSIVE:
            case MAX_INCLUSIVE:
            case MIN_EXCLUSIVE:
            case MAX_EXCLUSIVE:
                // Two conditions, and both are about what comes back. The compact bracket
                // holds a NUMBER and nothing else, so the spelling has to be one — a date,
                // an exponent or INF cannot go there. And it has no room for a datatype, so
                // the reader rebuilds one from the spelling alone: an integer becomes
                // xsd:integer and a decimal becomes xsd:double. Using the compact form for
                // any other datatype silently retyped the bound — xsd:int became
                // xsd:integer, xsd:decimal became xsd:double — so those take the keyword
                // form, where the datatype can be written out.
                return NUMBER.matcher(fr.getFacetValue().getLiteral()).matches()
                    && reconstructsFromSpellingAlone(fr.getFacetValue());
            default:
                return false;
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

    /**
     * An enumeration of individuals, as one set.
     *
     * <p>The inherited renderer wrote it as a union of singletons — {@code ObjectOneOf(:b
     * :c)} became <code>{b} &sqcup; {c}</code> — which is a fair reading of the semantics
     * and a different axiom. Re-reading gave
     * {@code ObjectUnionOf(ObjectOneOf(:b) ObjectOneOf(:c))}, and because each pass wrapped
     * the operands again the text grew a parenthesis level at a time:
     * <code>({b}) &sqcup; ({c})</code>, then <code>(({b})) &sqcup; (({c}))</code>.
     *
     * <p>The class assertion count never changed, so the regression job could not see it.
     *
     * <p>The reader has always understood <code>{b, c}</code> as a single enumeration; this
     * is the writer catching up with it. {@link #visit(OWLDataOneOf)} did so already, which
     * is why the value form never had the problem.
     */
    @Override
    public void visit(OWLObjectOneOf node) {
        write("{");
        List<OWLIndividual> individuals =
            node.individuals().collect(java.util.stream.Collectors.toList());
        for (Iterator<OWLIndividual> it = individuals.iterator(); it.hasNext();) {
            it.next().accept(this);
            if (it.hasNext()) {
                write(",");
            }
        }
        write("}");
    }

    @Override
    public void visit(OWLDataOneOf node) {
        write("{");
        List<OWLLiteral> values = node.values().collect(java.util.stream.Collectors.toList());
        for (Iterator<OWLLiteral> it = values.iterator(); it.hasNext();) {
            // Through renderLiteral, not by hand. Quoting it here dropped the language tag
            // and escaped nothing, so `DataOneOf("say \"hi\"" "b"@fr)` was written
            // `{"b","say "hi""}` — the tag gone and the document unreadable.
            write(renderLiteral(it.next()));
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
            if (value instanceof OWLLiteral) {
                String labelText = ((OWLLiteral) value).getLiteral();
                // Suppress predicate-signature labels (e.g. "afterNow(x)"): the predicate
                // definition line already encodes that information.
                if (labelText.startsWith(subject + "(") && labelText.endsWith(")")) {
                    return;
                }
                // Suppress a default label whose value is the entity's own local name,
                // since DefaultLabelAdder puts it back on the way in. Only an untagged one:
                // `rdfs:label :C "C"@en` is not the label that gets regenerated — that one
                // comes back plain — so suppressing it silently changed the literal, and
                // with it the axiom.
                if (axiom.getSubject() instanceof IRI && !((OWLLiteral) value).hasLang()) {
                    String localName = ((IRI) axiom.getSubject()).getRemainder().orElse(null);
                    if (labelText.equals(localName)) {
                        return;
                    }
                }
            }
            write("@label ");
            write(subject);
            write(" ");
            write(renderValue(value));
        // Both shorthands take a STRING in the grammar, so a non-literal value has to go
        // through the general form instead. `rdfs:seeAlso` pointing at another resource is
        // its commonest use, and it was written `@storage C Elsewhere` — a document that
        // would not reload. `rdfs:isDefinedBy` below has always had this guard.
        } else if (OWLRDFVocabulary.RDFS_COMMENT.getIRI().equals(propIRI)
                && value instanceof OWLLiteral) {
            write("@doc ");
            write(subject);
            write(" ");
            write(renderValue(value));
        } else if (OWLRDFVocabulary.RDFS_SEE_ALSO.getIRI().equals(propIRI)
                && value instanceof OWLLiteral) {
            write("@storage ");
            write(subject);
            write(" ");
            write(renderValue(value));
        } else if (OWLRDFVocabulary.RDFS_IS_DEFINED_BY.getIRI().equals(propIRI)
                && value instanceof OWLLiteral) {
            write("@db ");
            write(subject);
            // As with @label: a tagged literal is a different literal, so suppressing it
            // as redundant and letting the reader regenerate it loses the tag.
            OWLLiteral dbValue = (OWLLiteral) value;
            if (!dbValue.getLiteral().equals(subject) || dbValue.hasLang()) {
                write(" ");
                write(renderValue(value));
            }
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
    public void visit(OWLSubObjectPropertyOfAxiom axiom) {
        axiom.getSubProperty().accept(this);
        write(" ");
        write(DLSyntax.SUBCLASS);
        write(" ");
        axiom.getSuperProperty().accept(this);
    }

    @Override
    public void visit(OWLSubDataPropertyOfAxiom axiom) {
        axiom.getSubProperty().accept(this);
        write(" ");
        write(DLSyntax.SUBCLASS);
        write(" ");
        axiom.getSuperProperty().accept(this);
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
