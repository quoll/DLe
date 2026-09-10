package org.semanticweb.owlapi.dlesyntax;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.semanticweb.owlapi.dlsyntax.renderer.DLSyntaxStorerBase;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import java.util.stream.Stream;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.DefaultPrefixManager;

/**
 * Extends {@link DLSyntaxStorerBase}, substituting {@link DLESyntaxObjectRenderer}
 * so that annotations and other extended constructs are rendered correctly.
 */
public abstract class DLESyntaxStorerBase extends DLSyntaxStorerBase {

    /** Creates a new base storer instance. */
    protected DLESyntaxStorerBase() {}

    /** Prefix/namespace pairs that are implicit in every DLE file and need not be declared. */
    static final Map<String, String> DLE_DEFAULT_PREFIXES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(":",     "http://quoll.github.io/DLe/ontology#");
        m.put("dle:",  "http://quoll.github.io/DLe/vocab#");
        m.put("owl:",  "http://www.w3.org/2002/07/owl#");
        m.put("rdf:",  "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        m.put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        m.put("xsd:",  "http://www.w3.org/2001/XMLSchema#");
        m.put("xml:",  "http://www.w3.org/XML/1998/namespace");
        DLE_DEFAULT_PREFIXES = Collections.unmodifiableMap(m);
    }

    /** Renderer used to produce DLE syntax strings for axioms. */
    private final DLESyntaxObjectRenderer renderer = new DLESyntaxObjectRenderer();

    /** Current ontology being stored; set for the duration of {@code storeOntology}. */
    @Nullable private OWLOntology currentOntology;
    /** Annotation assertions already written inline; set for the duration of {@code storeOntology}. */
    @Nullable private Set<OWLAnnotationAssertionAxiom> writtenAnnotations;
    /** Prefixes in force while storing: the ontology's, overridden by the caller's. */
    @Nullable private Map<String, String> currentPrefixes;
    /** Set to true when {@code getRendering} returns {@code ""} so {@code endWritingAxiom} suppresses the blank line. */
    private boolean lastRenderingEmpty = false;
    /** Set to true whenever an axiom renders to non-empty content for the current entity;
     *  {@code endWritingAxioms} emits a blank-line separator only when this is true. */
    private boolean entityHadContent = false;

    @Override
    protected void storeOntology(OWLOntology o, PrintWriter printWriter, OWLDocumentFormat outputFormat) {
        renderer.setOntology(o);
        currentOntology = o;
        writtenAnnotations = new HashSet<>();
        currentPrefixes = prefixesFor(o, outputFormat);
        if (!currentPrefixes.isEmpty()) {
            DefaultPrefixManager pm = new DefaultPrefixManager();
            currentPrefixes.forEach(pm::setPrefix);
            renderer.setPrefixManager(pm);
        }
        try {
            super.storeOntology(o, printWriter, outputFormat);
        } finally {
            currentOntology = null;
            writtenAnnotations = null;
            currentPrefixes = null;
        }
    }

    /**
     * States what kind of thing a name is, where the reader could not otherwise tell.
     *
     * <p>DL writes class subsumption and sub-property subsumption identically as
     * {@code a ⊑ b}, so a reader has to infer which hierarchy a pair belongs to. It manages
     * on structure where there is any, and otherwise on the convention that concepts are
     * capitalised and roles are not. Two situations defeat both, and only those two are
     * written down:
     *
     * <ul>
     *   <li>a <b>pun</b> — a name that is a class <em>and</em> a property, as SNOMED CT's
     *       attribute roots are. Both statements are written; the pair is what marks it.</li>
     *   <li>a name whose <b>case contradicts its kind</b> — a lower-case class, or a
     *       capitalised property.</li>
     * </ul>
     *
     * <p>A numeric local name, which SNOMED CT uses throughout, contradicts nothing, so it
     * gets a statement only when punned. That keeps this quiet: across the seven example
     * documents it writes two lines, both for the one punned SNOMED CT identifier.
     *
     * <p>Both forms are existing DL and OWL: {@code X ⊑ ⊤} and
     * {@code X ⊑ owl:topObjectProperty}. Nothing is added to the syntax, and both are
     * tautologies, so a reader that ignores them loses nothing but the disambiguation.
     *
     * @return true if anything was written
     */
    private boolean writeKindStatements(OWLEntity entity, PrintWriter writer) {
        if (currentOntology == null) return false;
        IRI iri = entity.getIRI();
        boolean isClass = currentOntology.containsClassInSignature(iri);
        boolean isObjectProperty = currentOntology.containsObjectPropertyInSignature(iri);
        boolean isDataProperty = currentOntology.containsDataPropertyInSignature(iri);
        boolean isProperty = isObjectProperty || isDataProperty;

        // One statement per pass, and only from the pass for the kind it describes. Keying
        // this on `!entity.isOWLClass()` is not enough: an IRI can be a property *and* a
        // named individual, annotation property or datatype, and every one of those passes
        // would then write the property statement again.
        boolean isPropertyEntity = entity.isOWLObjectProperty() || entity.isOWLDataProperty();
        if (!entity.isOWLClass() && !isPropertyEntity) return false;
        if (entity.isOWLClass() && !isClass) return false;
        if (isPropertyEntity && !isProperty) return false;

        String name = renderer.shortForm(iri);
        boolean punned = isClass && isProperty;
        // A class directly beneath a pun needs marking too. Role classification crosses a
        // pun downward — that is what makes SNOMED CT's attribute children roles — so a
        // child meant as a concept is read as a role unless the document says otherwise.
        // `Child ⊑ ⊤` puts it beyond reach, using the same mechanism as everything else here.
        boolean classUnderPun = isClass && !punned && subsumedByAPun(iri);
        // A name contradicts the convention only if its case actively says the wrong
        // thing. A numeric local name — SNOMED CT's, for instance — says nothing either
        // way, so it needs a statement only when punned. Testing `!startsUpperCase`
        // instead would write a statement for every numeric class in the document: 56
        // lines for one where a single pun is the only ambiguity.
        // A lower-case class only needs marking when a reader would actually get it wrong.
        // What gets it wrong is the sub-property heuristic: two lower-case names either side
        // of a `⊑` are read as a role pair. Anywhere else the name is already pinned as a
        // class by its own axioms, and marking it added a vacuous `SubClassOf(X, owl:Thing)`
        // on the way back in for nothing — which is the whole of the round-trip cost this
        // mechanism used to carry.
        boolean classContradictsCase = isClass && startsLowerCase(name) && inALowerCasePair(iri);
        // The property-side counterpart of the narrowing above: a capitalised role gets a
        // statement only where a reader would actually misread it.
        //
        // What saves it otherwise is role evidence — the name, or a name directly above it,
        // used somewhere only a role can go. `IsPartOf ⊑ hasPart` alongside `∃hasPart.B`
        // needs nothing, because the restriction settles what hasPart is and the pair then
        // settles IsPartOf. `Studies ⊑ rel` on its own needs the statement: nothing makes
        // either name a role, so the pair reads as a class subsumption and the sub-property
        // axiom is lost.
        //
        // Below a pun it is always needed, because the reader's last-resort case guess
        // applies there and takes a capitalised child for a concept.
        boolean propertyContradictsCase = isProperty && startsUpperCase(name)
            && (propertySubsumedByAPun(entity) || !hasRoleEvidence(entity));

        boolean wrote = false;
        if (entity.isOWLClass() && (punned || classContradictsCase || classUnderPun)
                && !thingSubsumptionExists(iri)) {
            writer.println(name + " ⊑ ⊤");
            wrote = true;
        }
        // The kind is taken from the entity being written, not from the signature. Reading
        // it from the signature meant an IRI that is both an object and a data property had
        // `owl:topDataProperty` written by both passes — twice, with the object statement
        // never written at all, and the result did not parse.
        if (isPropertyEntity && (punned || propertyContradictsCase)) {
            boolean data = entity.isOWLDataProperty();
            String top = topPropertyName(data);
            if (top != null && !topSubPropertyExists(iri, data)) {
                writer.println(name + " ⊑ " + top);
                wrote = true;
            }
        }
        return wrote;
    }

    /**
     * The name to write for a top property, or null if this document cannot spell it.
     *
     * <p>Rendered through the prefix manager rather than hard-coded as {@code "owl:…"}. The
     * reader resolves these to IRIs precisely because a document may bind {@code owl:} to
     * some other namespace; writing the literal text in such a document produced a line that
     * meant a different entity, and the round trip both lost axioms and gained invented ones.
     *
     * <p>Null when no declared prefix maps to the OWL namespace — the statement is then
     * inexpressible, and writing something that resolves elsewhere would be worse than
     * writing nothing.
     */
    @Nullable
    private String topPropertyName(boolean data) {
        IRI iri = data ? OWLRDFVocabulary.OWL_TOP_DATA_PROPERTY.getIRI()
                       : OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI();
        String rendered = renderer.shortForm(iri);
        // shortForm falls back to the bare local part when nothing matches, and a bare name
        // resolves into the default namespace on the way back in.
        return rendered != null && rendered.indexOf(':') > 0 ? rendered : null;
    }

    /**
     * Whether the ontology already states {@code X ⊑ owl:top…Property}, which the ordinary
     * axiom renderer writes as the identical line — the property-side counterpart of
     * {@link #thingSubsumptionExists}. Without it the statement was written twice, and the
     * duplicate collapsed on the next write, so writing was not idempotent.
     */
    private boolean topSubPropertyExists(IRI iri, boolean data) {
        if (data) {
            return currentOntology.axioms(AxiomType.SUB_DATA_PROPERTY)
                .anyMatch(ax -> !ax.getSubProperty().isAnonymous()
                    && iri.equals(ax.getSubProperty().asOWLDataProperty().getIRI())
                    && ax.getSuperProperty().isOWLTopDataProperty());
        }
        return currentOntology.axioms(AxiomType.SUB_OBJECT_PROPERTY)
            .anyMatch(ax -> !ax.getSubProperty().isAnonymous()
                && iri.equals(ax.getSubProperty().getNamedProperty().getIRI())
                && ax.getSuperProperty().isOWLTopObjectProperty());
    }

    /**
     * Whether this class sits either side of a name-to-name subsumption whose other side
     * also lacks an upper-case signal — the shape the reader's sub-property heuristic
     * claims. Only then does a lower-case class need to say it is one.
     */
    private boolean inALowerCasePair(IRI iri) {
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(iri);
        return Stream.concat(
                currentOntology.subClassAxiomsForSubClass(cls)
                    .map(OWLSubClassOfAxiom::getSuperClass),
                currentOntology.subClassAxiomsForSuperClass(cls)
                    .map(OWLSubClassOfAxiom::getSubClass))
            .filter(other -> !other.isAnonymous())
            .anyMatch(other -> !startsUpperCase(
                renderer.shortForm(other.asOWLClass().getIRI())));
    }

    /** Whether this property is a direct sub-property of a name that is also a class. */
    private boolean propertySubsumedByAPun(OWLEntity entity) {
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        IRI iri = entity.getIRI();
        Stream<IRI> supers = entity.isOWLDataProperty()
            ? currentOntology.dataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(iri))
                .map(OWLSubDataPropertyOfAxiom::getSuperProperty)
                .filter(sup -> !sup.isAnonymous())
                .map(sup -> sup.asOWLDataProperty().getIRI())
            : currentOntology.objectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(iri))
                .map(OWLSubObjectPropertyOfAxiom::getSuperProperty)
                .filter(sup -> !sup.isAnonymous())
                .map(sup -> sup.getNamedProperty().getIRI());
        return supers.anyMatch(currentOntology::containsClassInSignature);
    }

    /**
     * Whether the document shows, somewhere a reader will see it, that this is a role.
     *
     * <p>Anything other than a declaration or a name-to-name sub-property axiom puts the name
     * in a position only a role can occupy — a restriction, a domain or range, a
     * characteristic, a chain. One hop up the hierarchy counts too, since the reader
     * propagates a classification across a {@code ⊑} pair.
     *
     * <p>One hop rather than the transitive closure, deliberately. Getting this wrong in the
     * direction of "no evidence" costs one redundant line; getting it wrong the other way
     * loses a sub-property axiom. A deeper chain than one hop simply gets the line.
     */
    private boolean hasRoleEvidence(OWLEntity entity) {
        if (usedAsARole(entity.getIRI())) return true;
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        IRI iri = entity.getIRI();
        Stream<IRI> supers = entity.isOWLDataProperty()
            ? currentOntology.dataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(iri))
                .map(OWLSubDataPropertyOfAxiom::getSuperProperty)
                .filter(sup -> !sup.isAnonymous())
                .map(sup -> sup.asOWLDataProperty().getIRI())
            : currentOntology.objectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(iri))
                .map(OWLSubObjectPropertyOfAxiom::getSuperProperty)
                .filter(sup -> !sup.isAnonymous())
                .map(sup -> sup.getNamedProperty().getIRI());
        return supers.anyMatch(this::usedAsARole);
    }

    /** Whether any axiom puts this IRI where only a role can go. */
    private boolean usedAsARole(IRI iri) {
        return currentOntology.referencingAxioms(iri)
            .anyMatch(ax -> !(ax instanceof OWLDeclarationAxiom)
                && !(ax instanceof OWLSubObjectPropertyOfAxiom)
                && !(ax instanceof OWLSubDataPropertyOfAxiom)
                && !(ax instanceof OWLAnnotationAssertionAxiom));
    }

    /** Whether this class is a direct sub-class of a name that is both a class and a property. */
    private boolean subsumedByAPun(IRI iri) {
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        return currentOntology.subClassAxiomsForSubClass(df.getOWLClass(iri))
            .map(OWLSubClassOfAxiom::getSuperClass)
            .filter(sup -> !sup.isAnonymous())
            .map(sup -> sup.asOWLClass().getIRI())
            .anyMatch(sup -> currentOntology.containsObjectPropertyInSignature(sup)
                || currentOntology.containsDataPropertyInSignature(sup));
    }

    /**
     * Whether the ontology already states {@code SubClassOf(X, owl:Thing)}, which the
     * ordinary axiom renderer writes as {@code X ⊑ ⊤} — the identical line. Without this
     * check both sources fire and the statement is written twice.
     */
    private boolean thingSubsumptionExists(IRI iri) {
        // Indexed by sub-class. Streaming every SUBCLASS_OF axiom per candidate made writing
        // quadratic — 190s for 100 000 lower-case-named classes against 2s before.
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        return currentOntology.subClassAxiomsForSubClass(df.getOWLClass(iri))
            .anyMatch(ax -> ax.getSuperClass().isOWLThing());
    }

    /** Whether a name's local part begins with an upper-case letter. */
    private static boolean startsUpperCase(String name) {
        String local = localPartOf(name);
        return !local.isEmpty() && Character.isUpperCase(local.charAt(0));
    }

    /** Whether a name's local part begins with a lower-case letter. */
    private static boolean startsLowerCase(String name) {
        String local = localPartOf(name);
        return !local.isEmpty() && Character.isLowerCase(local.charAt(0));
    }

    private static String localPartOf(String name) {
        int colon = name.lastIndexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * Writes a subject's {@code dle:comment} annotations as {@code #} lines.
     *
     * <p>Shared by the per-entity path and the predicate-definition path, because a
     * predicate IRI is not an OWL entity: it never appears in the signature, so
     * {@code beginWritingAxioms} is never called for it and its comments would
     * otherwise be dropped. Returns whether anything was written.
     *
     * @return true if at least one comment line was emitted
     */
    private boolean writeComments(IRI subject, OWLOntology ontology, PrintWriter writer) {
        if (writtenAnnotations == null) return false;
        boolean[] wrote = {false};
        // Each annotation may hold a multi-line block, joined with \n by the parser.
        ontology.annotationAssertionAxioms(subject)
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .sorted()
            .forEach(ax -> {
                if (writtenAnnotations.add(ax) && ax.getValue() instanceof OWLLiteral) {
                    for (String line : ((OWLLiteral) ax.getValue()).getLiteral().split("\n", -1)) {
                        writer.println("# " + line);
                    }
                    wrote[0] = true;
                }
            });
        return wrote[0];
    }

    /**
     * The prefixes to write with, merged from the two places they can come from:
     * the format passed to {@code saveOntology} and the format the ontology was
     * loaded from.
     *
     * <p>Both matter. The usual call is
     * {@code saveOntology(o, new DLESyntaxDocumentFormat(), t)}, where the
     * ontology's own format is the only place document prefixes exist. But a
     * caller who invokes the parser directly gets a populated format back and may
     * hand it straight to {@code saveOntology}, and preferring the ontology's
     * format outright discarded it — dropping every {@code @prefix} line and
     * re-resolving bare names to the DLe default namespace on reload.
     *
     * <p>Neither source can simply win, because neither is ever empty: a fresh
     * {@code DLESyntaxDocumentFormat} already carries {@code owl:}, {@code rdf:},
     * {@code rdfs:}, {@code xsd:} and {@code xml:} from
     * {@code PrefixDocumentFormatImpl}. Letting it win would silently reset a
     * document that redeclares one of those, and the renderer would then write the
     * affected names bare, to be re-resolved against a different namespace on
     * reload. Letting the ontology win loses the same thing in the other
     * direction.
     *
     * <p>So a declaration that merely repeats a DLe default never displaces one
     * that says something, and where both say something the ontology's wins,
     * because that is the namespace its entities actually live in.
     */
    private static Map<String, String> prefixesFor(OWLOntology o, OWLDocumentFormat outputFormat) {
        Map<String, String> merged = new LinkedHashMap<>();
        contribute(merged, outputFormat);
        contribute(merged, o.getFormat());
        return merged;
    }

    /** Adds a format's prefixes, without letting a default value displace a real one. */
    private static void contribute(Map<String, String> merged,
                                   @Nullable OWLDocumentFormat format) {
        if (!(format instanceof PrefixDocumentFormat)) return;
        ((PrefixDocumentFormat) format).getPrefixName2PrefixMap().forEach((prefix, iri) -> {
            if (isDleDefault(prefix, iri)
                    && merged.containsKey(prefix)
                    && !isDleDefault(prefix, merged.get(prefix))) {
                return;
            }
            merged.put(prefix, iri);
        });
    }

    /** Whether a declaration says no more than DLe already assumes. */
    private static boolean isDleDefault(String prefix, String iri) {
        return iri.equals(DLE_DEFAULT_PREFIXES.get(prefix));
    }

    @Override
    protected void beginWritingAxioms(OWLEntity entity, PrintWriter writer) {
        entityHadContent = false;
        // Suppress internal dle: entities — their labels are embedded inline in expressions.
        if (entity.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            // Mark their annotations as written to prevent re-emission later.
            if (currentOntology != null && writtenAnnotations != null) {
                currentOntology.annotationAssertionAxioms(entity.getIRI())
                    .forEach(writtenAnnotations::add);
            }
            return;
        }
        if (currentOntology == null || writtenAnnotations == null) return;

        // Emit dle:comment annotations as # lines before the entity's logical axioms.
        if (writeComments(entity.getIRI(), currentOntology, writer)) {
            entityHadContent = true;
        }

        if (writeKindStatements(entity, writer)) {
            entityHadContent = true;
        }

        // Emit dle:inlineComment annotations as # lines (same treatment as dle:comment).
        currentOntology.annotationAssertionAxioms(entity.getIRI())
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .sorted()
            .forEach(ax -> {
                if (writtenAnnotations.add(ax) && ax.getValue() instanceof OWLLiteral) {
                    writer.println("# " + ((OWLLiteral) ax.getValue()).getLiteral());
                    entityHadContent = true;
                }
            });

        // Emit all other annotations for this entity inline, before its logical axioms.
        currentOntology.annotationAssertionAxioms(entity.getIRI()).sorted().forEach(ax -> {
            if (writtenAnnotations.add(ax)) {
                beginWritingAxiom(writer);
                writeAxiom(null, ax, writer);
                endWritingAxiom(writer);
            }
        });
    }

    @Override
    protected void endWritingAxioms(PrintWriter writer) {
        if (entityHadContent) {
            writer.println();
            entityHadContent = false;
        }
    }

    @Override
    protected void endWritingAxiom(PrintWriter writer) {
        if (!lastRenderingEmpty) {
            writer.println();
        }
        lastRenderingEmpty = false;
    }

    @Override
    protected void endWritingOntology(OWLOntology ontology, PrintWriter writer) {
        // Write any annotation assertions whose subject was not a named entity
        // in the ontology signature (e.g. annotations on external IRIs, blank nodes).
        // dle:comment annotations are internal and are never emitted standalone.
        Set<OWLAnnotationAssertionAxiom> already = writtenAnnotations != null
            ? writtenAnnotations : new HashSet<>();
        ontology.axioms(AxiomType.ANNOTATION_ASSERTION).sorted()
            .filter(ax -> !already.contains(ax))
            .filter(ax -> !DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI())
                       && !DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .forEach(ax -> {
                beginWritingAxiom(writer);
                writeAxiom(null, ax, writer);
                endWritingAxiom(writer);
            });
    }

    @Override
    protected void beginWritingOntology(OWLOntology ontology, PrintWriter writer) {
        String resource = "/org/semanticweb/owlapi/dlesyntax/dle-syntax-header.txt";
        try (InputStream in = DLESyntaxStorerBase.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("DLE syntax header resource not found: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read DLE syntax header resource: " + e.getMessage(), e);
        }
        // The header resource already ends with a blank line; no extra println() needed.

        // Emit ontology/version/import declarations if present.
        OWLOntologyID id = ontology.getOntologyID();
        if (id.getOntologyIRI().isPresent()
                && !DLESyntaxAxiomVisitor.DLE_DEFAULT_ONTOLOGY_IRI.equals(id.getOntologyIRI().get())) {
            writer.println("@ontology <" + id.getOntologyIRI().get() + ">");
            if (id.getVersionIRI().isPresent()) {
                writer.println("@version <" + id.getVersionIRI().get() + ">");
            }
            writer.println();
        }
        ontology.importsDeclarations().sorted().forEach(decl ->
            writer.println("@import <" + decl.getIRI() + ">"));
        if (ontology.importsDeclarations().findAny().isPresent()) {
            writer.println();
        }

        // Emit prefix declarations that differ from the DLE defaults.
        // The six standard prefixes are implicit in every DLE file and need not be repeated.
        if (currentPrefixes != null) {
            long[] count = {0};
            currentPrefixes.forEach((prefix, iri) -> {
                String defaultIri = DLE_DEFAULT_PREFIXES.get(prefix);
                if (defaultIri == null || !defaultIri.equals(iri)) {
                    writer.println("@prefix " + prefix + " <" + iri + ">");
                    count[0]++;
                }
            });
            if (count[0] > 0) writer.println();
        }

        // Emit predicate definitions (rdf:value annotations with '→') near the top,
        // before logical axioms. Mark them so they are not re-emitted later.
        IRI rdfValueIRI = IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");
        if (writtenAnnotations != null) {
            long[] count = {0};
            ontology.axioms(AxiomType.ANNOTATION_ASSERTION).sorted()
                .filter(ax -> rdfValueIRI.equals(ax.getProperty().getIRI()))
                .filter(ax -> ax.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) ax.getValue()).getLiteral().contains("\u2192"))
                .forEach(ax -> {
                    if (writtenAnnotations.add(ax)) {
                        // A predicate's own comments, before its definition — the
                        // position they occupied in the source. Nothing else will
                        // emit them: a predicate IRI is not an OWL entity, so it is
                        // never passed to beginWritingAxioms.
                        if (ax.getSubject() instanceof IRI) {
                            writeComments((IRI) ax.getSubject(), ontology, writer);
                        }
                        beginWritingAxiom(writer);
                        writeAxiom(null, ax, writer);
                        endWritingAxiom(writer);
                        count[0]++;
                    }
                });
            if (count[0] > 0) writer.println();
        }
    }

    @Override
    protected String getRendering(@Nullable OWLEntity subject, OWLAxiom axiom) {
        // Declaration axioms carry no DL content and the base renderer produces ""
        // for them, which would cause a spurious blank line from endWritingAxiom.
        if (axiom instanceof OWLDeclarationAxiom) {
            lastRenderingEmpty = true;
            return "";
        }
        // Suppress all axioms in the entity block of dle: internal classes —
        // their content (EquivalentClasses, rdfs:label) is either rendered inline
        // elsewhere or is an internal implementation detail.
        if (subject != null && subject.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            lastRenderingEmpty = true;
            return "";
        }
        // dle:comment and dle:inlineComment annotations are emitted as # lines
        // in beginWritingAxioms — suppress here.
        if (axiom instanceof OWLAnnotationAssertionAxiom) {
            IRI propIRI = ((OWLAnnotationAssertionAxiom) axiom).getProperty().getIRI();
            if (DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(propIRI)
                    || DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(propIRI)) {
                lastRenderingEmpty = true;
                return "";
            }
        }
        lastRenderingEmpty = false;
        String rendered = renderer.render(axiom);
        if (rendered.isEmpty()) {
            lastRenderingEmpty = true;
        } else {
            entityHadContent = true;
        }
        return rendered;
    }
}
