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
import java.util.Collection;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDataCardinalityRestriction;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDisjointDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.semanticweb.owlapi.io.OWLOntologyDocumentTarget;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
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

    /**
     * Where this document is being written, when that is known.
     *
     * <p>Only needed to write an import back the way it came. A relative reference is
     * resolved to an absolute IRI on the way in — it has to be, because a relative path
     * cannot be carried inside a {@code file:} IRI — and writing that absolute path back
     * would put a machine-specific location into a document that is likely under version
     * control. With the target known it can be made relative again.
     */
    @Nullable
    private IRI targetDocumentIRI;

    @Override
    public void storeOntology(OWLOntology o, IRI documentIRI, OWLDocumentFormat format)
            throws OWLOntologyStorageException {
        targetDocumentIRI = documentIRI;
        try {
            super.storeOntology(o, documentIRI, format);
        } finally {
            targetDocumentIRI = null;
        }
    }

    @Override
    public void storeOntology(OWLOntology o, OWLOntologyDocumentTarget target,
                              OWLDocumentFormat format)
            throws OWLOntologyStorageException {
        targetDocumentIRI = target.getDocumentIRI().orElse(null);
        try {
            super.storeOntology(o, target, format);
        } finally {
            targetDocumentIRI = null;
        }
    }

    /**
     * Where the document being written came from, used when the output has no location of
     * its own — writing to stdout or a stream.
     *
     * <p>Without it, {@code owltx in.dle > out.dle} wrote every import as an absolute local
     * path while {@code owltx in.dle out.dle} kept it relative, so the two invocations
     * produced different documents. The source location is where the reference was relative
     * to in the first place, which makes it the right guess rather than merely a guess.
     */
    @Nullable
    private IRI sourceDocumentIRI() {
        if (currentOntology == null) return null;
        IRI iri = currentOntology.getOWLOntologyManager().getOntologyDocumentIRI(currentOntology);
        return iri != null && iri.toString().startsWith("file:") ? iri : null;
    }

    /** Escapes what the STRING token treats as special, so the value reads back unchanged. */
    private static String escapeForString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Renders an import target: a quoted relative path when it sits beside this document or
     * below it, and an absolute IRI otherwise.
     *
     * <p>Relativised only downward. {@link java.net.URI#relativize} declines to produce
     * {@code ../} chains, which is the behaviour wanted here — a path that climbs out of the
     * document's own directory is more fragile than an absolute one.
     */
    private String renderImport(IRI importIRI) {
        IRI location = targetDocumentIRI != null ? targetDocumentIRI : sourceDocumentIRI();
        if (location != null) {
            try {
                java.net.URI target = new java.net.URI(location.toString());
                java.net.URI imported = new java.net.URI(importIRI.toString());
                if ("file".equals(target.getScheme()) && "file".equals(imported.getScheme())
                        && !target.isOpaque() && !imported.isOpaque()) {
                    java.net.URI relative = target.resolve(".").relativize(imported);
                    if (!relative.isAbsolute()) {
                        // The decoded path, not the URI's text: relativize hands back
                        // percent-encoding, so a file named "my vocab.dle" would be written
                        // as "my%20vocab.dle" — legal, and reloadable, but not the spelling
                        // it came in as, which is the point of keeping the relative form.
                        String path = relative.getPath();
                        if (path == null || path.isEmpty()) {
                            // Nothing to name relatively — the import *is* the directory, or
                            // carries only a fragment. An absolute IRI is the honest answer.
                            return "<" + importIRI + ">";
                        }
                        // Decoded, because a quoted reference is a literal file name: this
                        // is the exact inverse of the encoding the reader applies, so the
                        // name comes back as it was written. The `./` is needed for the one
                        // character the encoding leaves alone — a colon in the first
                        // segment would otherwise read back as a scheme.
                        if (DLEOntologyParser.firstSegmentHasColon(path)) path = "./" + path;
                        return "\"" + escapeForString(path) + "\"";
                    }
                }
            } catch (java.net.URISyntaxException e) {
                // fall through to the absolute form
            }
        }
        return "<" + importIRI + ">";
    }

    /** Current ontology being stored; set for the duration of {@code storeOntology}. */
    @Nullable private OWLOntology currentOntology;
    /** Annotation assertions already written inline; set for the duration of {@code storeOntology}. */
    @Nullable private Set<OWLAnnotationAssertionAxiom> writtenAnnotations;
    /**
     * Every axiom the entity-block pass wrote, so the ones it never reached can be found.
     *
     * <p>The base storer walks entities and writes each axiom under one of them, which
     * leaves an axiom mentioning no entity it walks with nowhere to go. It was then dropped
     * in silence: a {@code DatatypeDefinition}, whose subject is a datatype and datatypes
     * get no block, and an identity axiom whose whole signature is anonymous —
     * {@code DifferentIndividuals(_:x _:y)} — both disappeared.
     */
    @Nullable private Set<OWLAxiom> writtenAxioms;
    /** Prefixes in force while storing: the ontology's, overridden by the caller's. */
    @Nullable private Map<String, String> currentPrefixes;
    /** Per-document memo for {@link #usedAsARole}; see there for why it is needed. */
    private final Map<OWLEntity, Boolean> roleEvidence = new java.util.HashMap<>();
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
        writtenAxioms = new HashSet<>();
        roleEvidence.clear();
        currentPrefixes = prefixesFor(o, outputFormat);
        declareUncoveredNamespaces(o, currentPrefixes);
        if (!currentPrefixes.isEmpty()) {
            DefaultPrefixManager pm = new DefaultPrefixManager();
            // Cleared first. A fresh DefaultPrefixManager pre-seeds owl:, rdf:, rdfs: and
            // xsd: in BOTH directions, and setPrefix replaces only the forward entry — so a
            // document that binds owl: to its own namespace left the reverse entry pointing
            // the real OWL namespace at `owl:`. The renderer then wrote `owl:topObjectProperty`
            // meaning that document's namespace, which named a different entity entirely, and
            // the round trip invented a class and an axiom. Clearing makes the reverse map
            // describe only what the document actually declares.
            pm.clear();
            // The default prefix last, so it wins the reverse map. DefaultPrefixManager
            // keeps one CURIE per namespace and the last one set takes it, so when a second
            // prefix is bound to the document's own namespace — which happens when an
            // entity is named for a reserved word — every name in that namespace would
            // otherwise be written prefixed. Only the reserved name needs to be; see
            // DLESyntaxObjectRenderer#stripDefaultPrefix.
            currentPrefixes.forEach((prefix, iri) -> {
                if (!":".equals(prefix)) pm.setPrefix(prefix, iri);
            });
            String defaultNamespace = currentPrefixes.get(":");
            if (defaultNamespace != null) pm.setPrefix(":", defaultNamespace);
            renderer.setPrefixManager(pm);
        }
        try {
            super.storeOntology(o, printWriter, outputFormat);
            writeAxiomsWithNoBlock(o, printWriter);
        } finally {
            currentOntology = null;
            writtenAnnotations = null;
            writtenAxioms = null;
            roleEvidence.clear();
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
     * gets a statement only when punned. That keeps this quiet: of the seven example
     * documents, the four containing the one punned SNOMED CT identifier gain two lines
     * each — its own two kinds — and the other three gain nothing.
     *
     * <p>Both forms are existing DL and OWL: {@code X ⊑ ⊤} and
     * {@code X ⊑ owl:topObjectProperty}. Nothing is added to the syntax, and both are
     * tautologies, so a reader that ignores them loses nothing but the disambiguation.
     *
     * @return true if anything was queued for the block
     */
    private boolean writeKindStatements(OWLEntity entity) {
        if (currentOntology == null) return false;
        IRI iri = entity.getIRI();
        // Never about the built-in vocabulary. Its kind is fixed by OWL, so a statement
        // says nothing — and one of these is the super of every punned property, which made
        // the writer emit `owl:topObjectProperty ⊑ owl:topObjectProperty` once the rule
        // about the super of a pun was added.
        if (RESERVED_KINDS.contains(iri)) return false;
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

        String name = shortFormOrNull(iri);
        // No DLe spelling means no statement can be written about it here — every branch
        // below both decides on the name and emits it.
        if (name == null) return false;
        boolean punned = isClass && isProperty;
        // A class directly beneath a pun needs marking too. Role classification crosses a
        // pun downward — that is what makes SNOMED CT's attribute children roles — so a
        // child meant as a concept is read as a role unless the document says otherwise.
        // `Child ⊑ ⊤` puts it beyond reach, using the same mechanism as everything else here.
        // ...but only one the reader's own case guess will not keep. That guess is the same
        // rule as `startsUpperCase` here, and it applies below a pun of either kind, so a
        // capitalised child needs no help: marking it wrote a line that said what the reader
        // already worked out, and — because the line comes back as a real `X ⊑ ⊤` axiom on
        // the next read — grew the axiom set and moved the line's own position by one pass.
        // What still needs it is a child the guess cannot rescue: a numeric name, as all of
        // SNOMED CT's are, or a lower-case one meant as a concept.
        boolean classUnderPun =
            isClass && !punned && !startsUpperCase(name) && subsumedByAPun(iri);
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
        boolean classContradictsCase =
            isClass && readerCanGuessRole(name, iri) && inAGuessableRolePair(iri);
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
        // Below a pun, only a child the reader's guess would claim as a concept needs the
        // statement — and that guess tests the local part for a capital, so it is
        // `startsUpperCase` here, not `readerCanGuessRole`. The two reader rules genuinely
        // differ: the pun-child guess strips the prefix, the sub-property guess refuses any
        // name that has one. Each test below mirrors the one it is about.
        //
        // Conflating them marked every numeric child of a pun, which is every SNOMED CT
        // attribute — names the reader classifies correctly on its own, from the pun above
        // them.
        // The reader's case guess yields an OBJECT property and nothing else — `a ⊑ b`
        // between two bare lower-case names is read as a sub-property pair of object
        // properties. So the guess can rescue an object property with such a name, and can
        // never rescue a data property: `a ⊑ b` between two data properties came back as
        // two object properties, silently, with no statement written because the name
        // looked like a role and the writer asked no further.
        // ...and not where a punned sub-property has already put this name inside the
        // reader's class barrier. The guess needs both sides of the pair to look like
        // roles, and a pun has been explicitly marked a concept.
        boolean caseCanRescue = readerCanGuessRole(name, iri) && !entity.isOWLDataProperty()
            && !supersAPunnedProperty(entity);
        boolean propertyContradictsCase = isProperty && !caseCanRescue
            && ((startsUpperCase(name) && propertySubsumedByAPun(entity))
                || !hasRoleEvidence(entity));

        // An IRI that is somehow both an object and a data property cannot be described at
        // all: DLe has one statement per role kind and a name can only have one, so the two
        // lines contradict each other and the reader now refuses the pair outright. Writing
        // just one is no better — which one got written depended on which entity the base
        // class happened to ask about, and the document then failed to re-read with a
        // message about datatypes. Saying nothing leaves the reader to classify from use,
        // which is the only evidence that survives.
        //
        // OWL 2 DL forbids this punning, so nothing well-formed arrives here.
        boolean dualRoleKinds = isObjectProperty && isDataProperty;

        // A pun needs BOTH of its statements: the pair is what says it is punned. If the
        // property half cannot be spelled — no declared prefix maps to the OWL namespace,
        // and DLe has no angle-bracket form in a name position — then writing the class
        // half alone leaves `X ⊑ ⊤` on something the reader must treat as a role, which is
        // strictly worse than writing nothing: it adds a vacuous axiom and still corrupts
        // the kind. So the two halves stand or fall together.
        boolean punStatements = punned && !dualRoleKinds && punIsFullySpellable(iri);

        // An entity the document only declares appears in no other axiom, so there is
        // nothing for the reader to classify it from and the convention cannot rescue it
        // whatever its case. With no statement, nothing about it is written at all and the
        // declaration is simply lost: of the seven declaration-only shapes only a data
        // property and a capitalised object property survived, and those two only because
        // some other rule happened to emit their statement. `Declaration(Class(:Solo))` has a
        // perfectly good spelling in `Solo ⊑ ⊤`, and a lower-case object property in
        // `r ⊑ owl:topObjectProperty`.
        boolean declarationOnly = declaresNothingElse(iri);

        boolean wrote = false;
        if (entity.isOWLClass()
                && (punStatements || classContradictsCase || classUnderPun || declarationOnly)
                && !thingSubsumptionExists(iri)) {
            pendingKindStatements.add(name + " ⊑ ⊤");
            wrote = true;
        }
        // The kind is taken from the entity being written, not from the signature. Reading
        // it from the signature meant an IRI that is both an object and a data property had
        // `owl:topDataProperty` written by both passes — twice, with the object statement
        // never written at all, and the result did not parse.
        if (isPropertyEntity && !dualRoleKinds
                && (punStatements || propertyContradictsCase || declarationOnly)) {
            boolean data = entity.isOWLDataProperty();
            String top = topPropertyName(data);
            if (top != null && !topSubPropertyExists(iri, data)) {
                pendingKindStatements.add(name + " ⊑ " + top);
                wrote = true;
            }
        }
        return wrote;
    }

    /**
     * Whether this IRI appears in no axiom but its own declaration.
     *
     * <p>Such an entity has no use to be read from, so nothing the reader does can recover
     * its kind, and every rule above is about *correcting* a reading rather than supplying
     * one. Asked of the IRI rather than the entity so that a name declared under two kinds
     * still counts as used.
     */
    private boolean declaresNothingElse(IRI iri) {
        if (currentOntology == null) return false;
        return currentOntology.referencingAxioms(iri)
            .allMatch(axiom -> axiom.getAxiomType() == AxiomType.DECLARATION
                || axiom.getAxiomType() == AxiomType.ANNOTATION_ASSERTION);
    }

    /** The built-in entities whose kind OWL already fixes; never worth a statement. */
    private static final Set<IRI> RESERVED_KINDS = Set.of(
        OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI(),
        OWLRDFVocabulary.OWL_TOP_DATA_PROPERTY.getIRI(),
        OWLRDFVocabulary.OWL_BOTTOM_OBJECT_PROPERTY.getIRI(),
        OWLRDFVocabulary.OWL_BOTTOM_DATA_PROPERTY.getIRI(),
        OWLRDFVocabulary.OWL_THING.getIRI(),
        OWLRDFVocabulary.OWL_NOTHING.getIRI(),
        IRI.create(EntityTypeScanner.RDFS_LITERAL_IRI));

    /**
     * Whether every statement a punned name needs can be spelled in this document.
     *
     * <p>Asked before either half is written; see the call site for why a half-written pun
     * is worse than none. An IRI that is somehow both an object and a data property needs
     * both top names, so both must be spellable.
     */
    private boolean punIsFullySpellable(IRI iri) {
        if (currentOntology.containsObjectPropertyInSignature(iri)
                && topPropertyName(false) == null) {
            return false;
        }
        return !(currentOntology.containsDataPropertyInSignature(iri)
            && topPropertyName(true) == null);
    }

    @Nullable
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
    /**
     * Whether this datatype's kind has to be stated for the reader to recover it.
     *
     * <p>A built-in is known by its namespace and needs nothing. A datatype the document
     * defines is settled by the definition itself. What is left is a name that is only
     * declared, which nothing in the text distinguishes from a class.
     */
    private boolean datatypeKindNeedsStating(IRI iri) {
        if (currentOntology == null) return false;
        if (org.semanticweb.owlapi.vocab.OWL2Datatype.isBuiltIn(iri)) return false;
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        return currentOntology.datatypeDefinitions(df.getOWLDatatype(iri)).count() == 0;
    }

    /** The name to write for the top data range, or null if this document cannot spell it. */
    @Nullable
    private String topDataRangeName() {
        String rendered = renderer.shortForm(
            IRI.create(EntityTypeScanner.RDFS_LITERAL_IRI));
        // As with the top properties: a bare name resolves into the default namespace on the
        // way back in, so an unprefixed rendering is no use as a marker.
        return rendered != null && rendered.indexOf(':') > 0 ? rendered : null;
    }

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
        // Indexed by sub-property, for the same reason {@link #thingSubsumptionExists} is
        // indexed by sub-class: this runs once per property written, so streaming every
        // sub-property axiom in the document made writing quadratic. Measured on capitalised
        // properties with no role evidence, which is the shape that reaches here — 8 000
        // took 6s, 16 000 took 29s and 32 000 took 121s, each doubling roughly quadrupling.
        //
        // The index keys on the sub-property, so it also replaces the identity and
        // anonymity tests the scan had to make for itself.
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        if (data) {
            return currentOntology
                .dataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(iri))
                .anyMatch(ax -> ax.getSuperProperty().isOWLTopDataProperty());
        }
        return currentOntology
            .objectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(iri))
            .anyMatch(ax -> ax.getSuperProperty().isOWLTopObjectProperty());
    }

    /**
     * Whether this class sits either side of a name-to-name subsumption whose other side
     * also lacks an upper-case signal — the shape the reader's sub-property heuristic
     * claims. Only then does a lower-case class need to say it is one.
     */
    private boolean inAGuessableRolePair(IRI iri) {
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(iri);
        return Stream.concat(
                currentOntology.subClassAxiomsForSubClass(cls)
                    .map(OWLSubClassOfAxiom::getSuperClass),
                currentOntology.subClassAxiomsForSuperClass(cls)
                    .map(OWLSubClassOfAxiom::getSubClass))
            .filter(other -> !other.isAnonymous())
            // shortFormOrNull, not shortForm: this is a heuristic question about some other
            // class, and an IRI with no DLe spelling must not fail the save. It used to, so
            // whether a document could be written turned on the capitalisation of an
            // unrelated name — `:thing1 ⊑ <urn:isbn:123>` threw where `:Thing1` did not.
            .filter(other -> shortFormOrNull(other.asOWLClass().getIRI()) != null)
            .anyMatch(other -> readerCanGuessRole(
                shortFormOrNull(other.asOWLClass().getIRI()), other.asOWLClass().getIRI()));
    }

    /** Whether any data cardinality in this axiom is written without its filler. */
    private static boolean hasUnqualifiedDataCardinality(OWLAxiom axiom) {
        return axiom.nestedClassExpressions()
            .filter(OWLDataCardinalityRestriction.class::isInstance)
            .map(OWLDataCardinalityRestriction.class::cast)
            .anyMatch(r -> r.getFiller().isTopDatatype());
    }

    /**
     * Whether an axiom, once rendered, shows the reader that its subject is a role.
     *
     * <p>The question is about the DLe text, not the axiom. A declaration writes nothing.
     * An annotation assertion says nothing about kind. A sub-property axiom renders as
     * {@code p ⊑ q}, which is exactly a class subsumption — that ambiguity is the reason
     * kind statements exist at all.
     *
     * <p>And an equivalence between two named properties renders as {@code p ≡ q}, which is
     * likewise exactly a class equivalence. Counting it as evidence meant two data
     * properties related only by {@code EquivalentDataProperties} were written with no
     * statement and read back as object properties. Anything else — a restriction, a domain
     * or range, a characteristic, a chain, an inverse — puts the name somewhere only a role
     * can go.
     */
    private static boolean pinsTheKind(OWLAxiom axiom, boolean dataProperty) {
        if (axiom instanceof OWLDeclarationAxiom
                || axiom instanceof OWLSubObjectPropertyOfAxiom
                || axiom instanceof OWLSubDataPropertyOfAxiom
                || axiom instanceof OWLAnnotationAssertionAxiom) {
            return false;
        }
        if (axiom instanceof OWLEquivalentObjectPropertiesAxiom) {
            return ((OWLEquivalentObjectPropertiesAxiom) axiom).properties()
                .anyMatch(OWLPropertyExpression::isAnonymous);
        }
        if (axiom instanceof OWLEquivalentDataPropertiesAxiom) {
            return false;   // a data property expression is always named
        }
        // A data property needs evidence of WHICH KIND of role it is, and three forms give
        // none: `Func(p)`, `Disj(p, q)` and the domain idiom `∃p.⊤ ⊑ C` are written exactly
        // the same way for both kinds. An object property can rely on them, because a role
        // with no data evidence is what the reader guesses object from — but for a data
        // property they say only "role", and the reader then guesses wrong.
        //
        // This is the same error as counting the case convention as a rescue for a data
        // property: role evidence is not kind evidence. It cost a silent, stable
        // DataProperty → ObjectProperty on any document whose only mention of a data
        // property was one of these three.
        if (dataProperty
                && (axiom instanceof OWLFunctionalDataPropertyAxiom
                    || axiom instanceof OWLDisjointDataPropertiesAxiom
                    || axiom instanceof OWLDataPropertyDomainAxiom
                    || axiom instanceof OWLHasKeyAxiom)) {
            return false;
        }
        // An *unqualified* data cardinality is the fourth spelling that says only "role".
        // `\u22652 d` has no filler, so it reads exactly like the object form, and a
        // document whose only mention of a data property was `C \u2291 \u22652 d` came
        // back with it an object property. The qualified form `\u22652 d.xsd:string`
        // names a datatype and does pin the kind, so only the unqualified one is excluded.
        if (dataProperty && hasUnqualifiedDataCardinality(axiom)) {
            return false;
        }
        return true;
    }

    /**
     * Whether the reader's own case guess can take this name for a role.
     *
     * <p>Delegates to {@link EntityTypeScanner#caseSuggestsRole}, which is the reader's
     * actual rule, rather than restating it. Restating it is precisely how this went wrong:
     * the writer asked whether the local part was upper or lower case while the reader
     * required a bare name, and two ordinary families of document fell through the gap in
     * opposite directions — prefixed and digit-initial properties losing their axioms,
     * prefixed classes gaining vacuous ones. Sharing the method makes that class of bug
     * unavailable.
     *
     * <p>The IRI is passed because the rule excludes datatypes, and a datatype is known by
     * its namespace and not by how it is spelled.
     */
    private boolean readerCanGuessRole(String name, IRI iri) {
        return EntityTypeScanner.caseSuggestsRole(name, iri.toString());
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
     * Whether some sub-property of this one is punned.
     *
     * <p>The mirror of {@link #propertySubsumedByAPun}, and needed for the same reason from
     * the other end. A punned name carries a concept statement, which puts it in the
     * reader's class barrier; the barrier then propagates <em>up</em> the hierarchy, so the
     * property above a pun is read as a concept and the sub-property axiom between them
     * becomes a subsumption.
     *
     * <p>The case convention cannot rescue it: that guess needs both sides of the pair to
     * look like roles, and a punned name has been explicitly marked as a concept. So the
     * super has to say what it is, however ordinary its name looks.
     */
    private boolean supersAPunnedProperty(OWLEntity entity) {
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        // Object properties only. The caller reaches this behind
        // `!entity.isOWLDataProperty()`, which short-circuits, so a data-property branch
        // here was unreachable — and unnecessary too, since a data property always gets a
        // kind statement anyway.
        IRI iri = entity.getIRI();
        return currentOntology
            .objectSubPropertyAxiomsForSuperProperty(df.getOWLObjectProperty(iri))
            .map(OWLSubObjectPropertyOfAxiom::getSubProperty)
            .filter(sub -> !sub.isAnonymous())
            .map(sub -> sub.getNamedProperty().getIRI())
            .anyMatch(currentOntology::containsClassInSignature);
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
        if (usedAsARole(entity)) return true;
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        IRI iri = entity.getIRI();
        // The supers are carried as entities, not IRIs, because that is what can be looked
        // up in an index — see {@link #usedAsARole}. They are properties of the same kind as
        // the sub, which is what the axiom type already guarantees.
        Stream<OWLEntity> supers = entity.isOWLDataProperty()
            ? currentOntology.dataSubPropertyAxiomsForSubProperty(df.getOWLDataProperty(iri))
                .map(OWLSubDataPropertyOfAxiom::getSuperProperty)
                .filter(sup -> !sup.isAnonymous())
                .map(sup -> (OWLEntity) sup.asOWLDataProperty())
            : currentOntology.objectSubPropertyAxiomsForSubProperty(df.getOWLObjectProperty(iri))
                .map(OWLSubObjectPropertyOfAxiom::getSuperProperty)
                .filter(sup -> !sup.isAnonymous())
                .map(sup -> (OWLEntity) sup.getNamedProperty());
        return supers.anyMatch(this::usedAsARole);
    }

    /**
     * Whether any axiom puts this entity where only a role can go.
     *
     * <p>Takes an entity rather than an IRI, which matters a great deal. OWL API indexes
     * referencing axioms by entity; given an IRI it cannot use that index, and instead
     * streams every axiom in the ontology into a set — four full passes, eagerly, so even
     * {@code anyMatch} cannot cut it short. Asking per property then costs a scan of the
     * whole document, which made writing quadratic: 32 000 properties under one super took
     * 99s against 0.9s before this mechanism existed, each doubling roughly quadrupling.
     *
     * <p>The properties have to be <em>capitalised</em> for that measurement, and the same
     * goes for reproducing it. A lower-case object property makes {@code readerCanGuessRole}
     * true, so {@link #hasRoleEvidence} is never asked and nothing here runs: 32 000 of those
     * emit one kind statement between them and take no measurable time. Leaving the case out
     * is what made this figure look invented — the obvious fixture built {@code p0…pN} and
     * measured a path it never entered.
     *
     * <p>It is also the more accurate question. A class axiom mentioning the same name is
     * not evidence that the name is a role, and the IRI form counted it as such.
     *
     * <p>Memoised for the document being written, because {@link #hasRoleEvidence} looks one
     * hop up the hierarchy, so every property under a shared super asks about that same
     * super. Cleared with the rest of the per-document state in {@link #storeOntology}.
     */
    private boolean usedAsARole(OWLEntity entity) {
        Boolean known = roleEvidence.get(entity);
        if (known != null) return known;
        boolean data = entity.isOWLDataProperty();
        boolean answer = currentOntology.referencingAxioms(entity)
            .anyMatch(ax -> pinsTheKind(ax, data));
        roleEvidence.put(entity, answer);
        return answer;
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
        //
        // Lower-case *and* in guessable role pairs, for that measurement and for reproducing
        // it: what brings a class here is `classContradictsCase`, which needs both halves of
        // a `⊑` to look like roles. 100 000 lower-case classes that are not paired that way
        // never reach this at all, which is the other half of why these figures looked
        // unreproducible.
        OWLDataFactory df = currentOntology.getOWLOntologyManager().getOWLDataFactory();
        return currentOntology.subClassAxiomsForSubClass(df.getOWLClass(iri))
            .anyMatch(ax -> ax.getSuperClass().isOWLThing());
    }

    /** Whether a name's local part begins with an upper-case letter. */
    private static boolean startsUpperCase(String name) {
        String local = localPartOf(name);
        return !local.isEmpty() && Character.isUpperCase(local.charAt(0));
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
     * Gives a prefix to every namespace the document uses but does not declare.
     *
     * <p>Writing a name needs a prefix that covers its IRI. When none did, the renderer fell
     * through to the bare local part and threw the namespace away — and a bare name is read
     * back into the default namespace, so the entity silently became a different entity:
     *
     * <pre>
     * SubClassOf(:C &lt;http://other.example.com/vocab#Person&gt;)
     *   &rarr; C &sqsube; Person
     *   &rarr; SubClassOf(:C :Person)        // :Person is now in the document's own namespace
     * </pre>
     *
     * <p>Any ontology that names anything outside its own namespace hit this, which is most
     * ontologies that import or align with another — and it was silent in both directions,
     * so nothing in the output showed that a namespace had been dropped.
     *
     * <p>Minted names are {@code ns1:}, {@code ns2:} and so on, assigned in namespace order
     * so that the same document always produces the same names, and skipping any name the
     * document has already used for something else.
     */
    private static void declareUncoveredNamespaces(OWLOntology o, Map<String, String> prefixes) {
        Collection<String> covered = prefixes.values();
        Set<String> uncovered = new TreeSet<>();
        namesWritten(o).forEach(iri -> {
            String namespace = namespaceToCover(iri);
            // Compared exactly. Asking whether any declared prefix is a leading substring of
            // the whole IRI says yes far too often: with `ex:` bound to
            // `http://example.org/ex/`, the IRI `http://example.org/ex/deep#B` counted as
            // covered, so nothing was minted for `.../deep#` and the name went out as
            // `ex:deep#B` — where `#B` begins a comment. The class was lost, a comment was
            // invented, and the document no longer parsed.
            if (!covered.contains(namespace)) {
                uncovered.add(namespace);
            }
        });
        // A name the grammar keeps for itself cannot be written bare, so the namespace it
        // sits in needs a prefix other than the default to spell it with. Only the default
        // namespace is affected: every other name is written prefixed already.
        String defaultNamespace = prefixes.get(":");
        if (defaultNamespace != null && !hasNonDefaultPrefix(prefixes, defaultNamespace)
                && namesWritten(o).anyMatch(iri -> DLESyntaxObjectRenderer
                    .isReservedLocalName(iri.getRemainder().orElse("")))) {
            uncovered.add(defaultNamespace);
        }

        int next = 1;
        for (String namespace : uncovered) {
            String name;
            do {
                name = "ns" + next++ + ":";
            } while (prefixes.containsKey(name));
            prefixes.put(name, namespace);
        }
    }

    /**
     * The namespace a name needs a prefix for.
     *
     * <p>{@link IRI#getNamespace} splits so that the remainder is an NCName, and a
     * digit-initial local part is not one: for {@code <http://snomed.info/id/762705008>} it
     * hands back the whole IRI and an empty remainder. Minting for that produces a prefix
     * with nothing after the colon — {@code ns1: \u2291 A} — which does not parse. DLe writes
     * such a name with an explicit prefix and colon ({@code sct:762705008}), so what has to
     * be covered is the namespace up to the last separator.
     *
     * <p>The loose leading-substring test this replaced hid that: any declared prefix that
     * happened to be a leading substring of the full IRI counted, so nothing was minted and
     * nothing went wrong until the namespace really was uncovered.
     */
    private static String namespaceToCover(IRI iri) {
        String full = iri.toString();
        // The latest split that leaves a tail the lexer will accept. Taking the OWL API's own
        // split instead produced local parts DLe cannot write: it splits so the remainder is
        // an NCName, where a dot is legal — so `<...#A.B>` gave the remainder `A.B`, which
        // reads back as a restriction over `A`, and `<...#762705008>` gave an *empty*
        // remainder and the whole IRI as the namespace, which minted a prefix with nothing
        // after the colon. Both wrote a document this reader cannot read.
        // Forwards, so the *longest* spellable tail wins: `<http://snomed.info/id/762705008>`
        // splits at the last slash and keeps the whole identifier, rather than shortening to
        // a one-character tail that would also have been legal.
        for (int cut = 1; cut < full.length(); cut++) {
            if (DLESyntaxObjectRenderer.isSpellableLocalName(full.substring(cut))) {
                return full.substring(0, cut);
            }
        }
        // No tail is spellable, which happens when the IRI ends in a character no name may
        // contain — `<...#>` or `<.../A.>`. Nothing can be minted that helps; the existing
        // fallback writes it and the reader refuses it, loudly, which is the honest outcome.
        return iri.getNamespace();
    }

    /** Whether some prefix other than the default already covers this namespace. */
    private static boolean hasNonDefaultPrefix(Map<String, String> prefixes, String namespace) {
        return prefixes.entrySet().stream()
            .anyMatch(e -> !":".equals(e.getKey()) && namespace.equals(e.getValue()));
    }

    /**
     * Every IRI this document will write as a name.
     *
     * <p>The signature covers entities. Annotation assertions are the other source: both a
     * subject and an IRI-valued object may name something the signature never mentions.
     */
    private static Stream<IRI> namesWritten(OWLOntology o) {
        Stream<IRI> entities = o.signature().map(OWLEntity::getIRI);
        Stream<IRI> annotationSubjects = o.axioms(AxiomType.ANNOTATION_ASSERTION)
            .map(OWLAnnotationAssertionAxiom::getSubject)
            .filter(IRI.class::isInstance).map(IRI.class::cast);
        Stream<IRI> annotationValues = o.axioms(AxiomType.ANNOTATION_ASSERTION)
            .map(OWLAnnotationAssertionAxiom::getValue)
            .filter(IRI.class::isInstance).map(IRI.class::cast);
        return Stream.concat(entities, Stream.concat(annotationSubjects, annotationValues));
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

    /**
     * The entity whose block is being written, and its own axioms held back for ordering.
     *
     * <p>The base class fetches an entity's axioms itself and writes them in whatever order
     * the ontology's indexes hand them over, which is not the same order twice: five runs
     * over one document produced five different files, from four subsumptions on a single
     * subject upward. Axiom hash codes and the signature order are both stable, so the
     * variation is in the axiom index alone.
     *
     * <p>There is no hook for the order, so the axioms are collected as the base class
     * offers them and written, sorted, when the entity's own section ends. What is held is
     * narrow on purpose — only the axioms between {@link #beginWritingAxioms} and
     * {@link #beginWritingUsage}:
     *
     * <ul>
     * <li>The comment and annotation lines this class emits itself are already written by
     *     the time holding starts, so they stay ahead of the logical axioms where DLe puts
     *     them. Holding them too sorted {@code @label} in among the subsumptions and pushed
     *     both after them.
     * <li>The usage section is flushed past, not absorbed. The base class has already
     *     sorted it, it is a separate part of the block, and merging it in also changed
     *     which statement heads the block — see {@link #flushHeldAxioms} for why that
     *     matters to comments.
     * </ul>
     */
    @Nullable
    private OWLEntity currentEntity;
    private final List<OWLAxiom> heldAxioms = new ArrayList<>();
    /**
     * Kind statements for the block being written, held for the same sort as its axioms.
     *
     * <p>They used to be printed straight out, above the block. That put a line in a place
     * the equivalent axiom would never be rendered: {@code X ⊑ ⊤} comes back from the reader
     * as a real {@code SubClassOf(X, owl:Thing)}, and on the next write it was sorted into
     * the block instead — so the first write and the second disagreed, and writing was not
     * idempotent. Sorting the statement by the same text that the axiom will produce makes
     * the two passes agree.
     */
    private final List<String> pendingKindStatements = new ArrayList<>();
    private boolean holdingAxioms;

    @Override
    protected void beginWritingAxiom(PrintWriter writer) {
        if (holdingAxioms) return;
        super.beginWritingAxiom(writer);
    }

    @Override
    protected void writeAxiom(OWLEntity entity, OWLAxiom axiom, PrintWriter writer) {
        if (writtenAxioms != null) writtenAxioms.add(axiom);
        if (holdingAxioms) {
            heldAxioms.add(axiom);
            return;
        }
        super.writeAxiom(entity, axiom, writer);
    }

    /**
     * Writes the axioms the entity-block pass never reached.
     *
     * <p>An axiom goes under one of the entities the base storer walks — classes, object and
     * data properties, individuals. An axiom that mentions none of them has no block, and
     * was simply lost: a {@code DatatypeDefinition} is about a datatype, and
     * {@code DifferentIndividuals(_:x _:y)} is about nothing named at all.
     *
     * <p>Sorted by their rendered text, so a document does not depend on the order a hash
     * set happened to produce. An axiom that renders to nothing is skipped rather than
     * written as a blank line — that is how the writer declines the forms it cannot spell,
     * such as a one-property disjointness, and this pass must not undo those decisions.
     */
    private void writeAxiomsWithNoBlock(OWLOntology o, PrintWriter writer) {
        if (writtenAxioms == null) return;
        // Logical axioms, plus the annotation-property domain and range — which are not
        // logical axioms, so this pass never saw them and the renderer's working visit methods
        // for them were never reached. A document whose only content was an
        // AnnotationPropertyDomain came out empty, at exit 0.
        //
        // SubAnnotationPropertyOf is deliberately not here. Its only DLe spelling is
        // `ap ⊑ bp`, which is indistinguishable from a sub-property axiom between two object
        // properties, and that is how it reads back — turning an annotation-property axiom
        // into an object-property one and punning the name across two property kinds, out of
        // the OWL 2 DL profile. Dropping it loses an axiom; writing it changes one, and a
        // changed axiom is the worse outcome. Stating the annotation kind is what would fix
        // it, and DLe has no form for that yet.
        List<String> lines = Stream.concat(
                o.logicalAxioms().map(axiom -> (OWLAxiom) axiom),
                Stream.of(AxiomType.ANNOTATION_PROPERTY_DOMAIN,
                          AxiomType.ANNOTATION_PROPERTY_RANGE)
                    .flatMap(type -> o.axioms(type).map(axiom -> (OWLAxiom) axiom)))
            .filter(axiom -> !writtenAxioms.contains(axiom))
            .map(axiom -> getRendering(null, axiom))
            .filter(text -> text != null && !text.trim().isEmpty())
            .distinct()
            .sorted()
            .collect(Collectors.toCollection(java.util.ArrayList::new));
        // Datatype kind statements go here rather than in an entity block, because the
        // inherited renderer's entity loop has no datatype pass at all — which is why a
        // datatype that is only declared had nothing written about it anywhere.
        //
        // The reader knows a built-in datatype by its namespace, and one the document
        // *defines* by its definition. A name that is only declared is neither, so
        // `⊤ ⊑ ∀d.T` came back as an object property range with T a class — the kind lost in
        // both directions at once, silently.
        //
        // `T ⊑ rdfs:Literal` is the datatype counterpart of `C ⊑ ⊤` and
        // `r ⊑ owl:topObjectProperty`: every datatype lies beneath OWL 2's top data range, so
        // it asserts nothing that was not already true. Alone among the three it is not also
        // an axiom — OWL has no datatype subsumption, only DatatypeDefinition, which is an
        // equivalence and would say something far stronger — so the reader consumes it into a
        // declaration and there is nothing to filter out afterwards.
        String literal = topDataRangeName();
        if (literal != null) {
            o.datatypesInSignature()
                .filter(dt -> datatypeKindNeedsStating(dt.getIRI()))
                .map(dt -> shortFormOrNull(dt.getIRI()))
                .filter(java.util.Objects::nonNull)
                .map(dtName -> dtName + " ⊑ " + literal)
                .sorted()
                .forEach(lines::add);
        }
        if (lines.isEmpty()) return;
        writer.println();
        lines.forEach(writer::println);
    }

    @Override
    protected void beginWritingAxioms(OWLEntity entity, PrintWriter writer) {
        currentEntity = entity;
        heldAxioms.clear();
        pendingKindStatements.clear();
        holdingAxioms = false;
        entityHadContent = false;
        // Suppress internal dle: entities — their labels are embedded inline in expressions.
        if (entity.getIRI().toString().startsWith(DLESyntaxAxiomVisitor.DLE_NS)) {
            // Mark their annotations as written to prevent re-emission later.
            if (currentOntology != null && writtenAnnotations != null) {
                currentOntology.annotationAssertionAxioms(entity.getIRI())
                    .forEach(writtenAnnotations::add);
            }
            holdingAxioms = true;
            return;
        }
        if (currentOntology == null || writtenAnnotations == null) {
            holdingAxioms = true;
            return;
        }

        // Emit dle:comment annotations as # lines before the entity's logical axioms.
        if (writeComments(entity.getIRI(), currentOntology, writer)) {
            entityHadContent = true;
        }

        if (writeKindStatements(entity)) {
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

        // Only now: everything above belongs ahead of the logical axioms, and is already
        // in an order of its own.
        holdingAxioms = true;
    }

    @Override
    protected void beginWritingUsage(int size, PrintWriter writer) {
        // The entity's own axioms end here, so this is where they are written. The usage
        // axioms that follow are a separate section, already sorted by the base class.
        flushHeldAxioms(writer);
        super.beginWritingUsage(size, writer);
    }

    @Override
    protected void endWritingAxioms(PrintWriter writer) {
        flushHeldAxioms(writer);
        if (entityHadContent) {
            writer.println();
            entityHadContent = false;
        }
    }

    /**
     * Writes the entity's own axioms in a fixed order.
     *
     * <p>Two keys, and the second is the one that makes the output stable. First, a
     * statement that <em>opens</em> with this entity's name comes before one that does not,
     * so the block leads with what defines the entity rather than with a qualifier of it:
     *
     * <pre>
     * belongsToSpecies &#8849; relationship      Func(belongsToSpecies)
     * Func(belongsToSpecies)                     belongsToSpecies &#8849; relationship
     * &#8707;belongsToSpecies.&#8868; &#8849; Animal          &#8707;belongsToSpecies.&#8868; &#8849; Animal
     * </pre>
     *
     * <p>This is a readability rule and nothing more. It is <em>not</em> what keeps the
     * comments above a block attached to the right entity on the way back in — a comment
     * attaches to the first <em>name</em> of the statement below it, and in every form the
     * renderer produces for an entity's own axiom, including {@code Func(r)},
     * {@code Disj(r, s)} and {@code &#8707;r.&#8868; &#8849; C}, that first name is the entity itself. Both
     * orderings preserve the same comments across a round trip of the corpus.
     *
     * <p>Second, the rendered text, which is what actually makes writing deterministic and
     * a diff between two versions of a document readable. Ties fall back to OWL API's axiom
     * order so the comparator stays total.
     *
     * <p>Each axiom is rendered once and the text kept, since rendering is what the base
     * class's {@code writeAxiom} does with it anyway.
     */
    private void flushHeldAxioms(PrintWriter writer) {
        if (!holdingAxioms) return;
        holdingAxioms = false;
        List<String> lines = new ArrayList<>();
        for (OWLAxiom axiom : heldAxioms) {
            lines.add(getRendering(currentEntity, axiom));
        }
        heldAxioms.clear();
        // A kind statement is already the text an axiom would render to, so it joins the
        // list as text and takes its place by the same rule.
        lines.addAll(pendingKindStatements);
        pendingKindStatements.clear();

        String own = ownName();
        lines.sort(Comparator
            .comparingInt((String text) -> startsWithName(text, own) ? 0 : 1)
            .thenComparing(Comparator.naturalOrder()));

        for (String text : lines) {
            super.beginWritingAxiom(writer);
            lastRenderingEmpty = text.isEmpty();
            if (!text.isEmpty()) writer.write(text);
            endWritingAxiom(writer);
        }
    }

    /**
     * This entity's own name as it is rendered, or null if it has none.
     *
     * <p>An IRI with no declared prefix and no remainder — {@code <urn:isbn:123>} — has no
     * short form, and asking for one throws. That is the right answer when the name has to
     * be written, but here it is only a sort key, and an ordering preference is no reason
     * to fail a save that would otherwise succeed. Such an entity's axioms sort by their
     * rendered text alone.
     */
    @Nullable
    private String ownName() {
        return currentEntity == null ? null : shortFormOrNull(currentEntity.getIRI());
    }

    /**
     * This IRI's short form, or null if it has none.
     *
     * <p>An IRI with no declared prefix and no remainder — {@code <urn:isbn:123>} — cannot
     * be spelled as a DLe name, and asking for one throws. That is the right answer where
     * the name has to be written, and the renderer raises it there. It is the wrong answer
     * for the two callers here, which only want to know <em>how</em> to write something they
     * may well not write at all: an ordering preference and a kind statement. Neither is a
     * reason to fail a save that would otherwise succeed.
     */
    @Nullable
    private String shortFormOrNull(IRI iri) {
        try {
            return renderer.shortForm(iri);
        } catch (RuntimeException noShortForm) {
            return null;
        }
    }

    /** Whether a rendering opens with this name as a whole token. */
    private static boolean startsWithName(@Nullable String rendering, @Nullable String name) {
        if (rendering == null || name == null || name.isEmpty()) return false;
        return rendering.length() > name.length()
            && rendering.startsWith(name)
            && rendering.charAt(name.length()) == ' ';
    }

    @Override
    protected void endWritingAxiom(PrintWriter writer) {
        // While the block's axioms are held back nothing has been rendered yet, so there is
        // no line to terminate; the flush calls this again for each one, in order.
        if (holdingAxioms) return;
        if (!lastRenderingEmpty) {
            writer.println();
        }
        lastRenderingEmpty = false;
    }

    @Override
    protected void endWritingOntology(OWLOntology ontology, PrintWriter writer) {
        // Write any annotation assertions whose subject was not a named entity
        // in the ontology signature (e.g. annotations on external IRIs, blank nodes).
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

        // A comment whose subject never got a block of its own would otherwise be dropped
        // here: the entity-block pass never saw it, and this pass used to filter every
        // dle:comment out as internal. That is silent loss of something the author wrote,
        // so it is written as # lines instead. It loses its attachment — there is no
        // statement left to sit above — but the text survives.
        ontology.axioms(AxiomType.ANNOTATION_ASSERTION).sorted()
            .filter(ax -> !already.contains(ax))
            .filter(ax -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(ax.getProperty().getIRI())
                       || DLESyntaxAxiomVisitor.DLE_INLINE_COMMENT_IRI.equals(ax.getProperty().getIRI()))
            .forEach(ax -> {
                if (ax.getValue() instanceof OWLLiteral) {
                    writeCommentLines(((OWLLiteral) ax.getValue()).getLiteral(), writer);
                }
            });

        // And the document's own trailing comment block, which belongs to no entity at all.
        ontology.annotations()
            .filter(a -> DLESyntaxAxiomVisitor.DLE_COMMENT_IRI.equals(a.getProperty().getIRI()))
            .sorted()
            .forEach(a -> {
                if (a.getValue() instanceof OWLLiteral) {
                    writeCommentLines(((OWLLiteral) a.getValue()).getLiteral(), writer);
                }
            });
    }

    /**
     * Writes a stored comment literal back out as one {@code #} line per line of it.
     *
     * <p>Followed by a blank line, matching what the entity-block path produces. Without it
     * the same comment written through the two paths differed by one trailing newline, so a
     * comment that moved from a block to here on one pass changed the file on the next.
     */
    private static void writeCommentLines(String literal, PrintWriter writer) {
        for (String line : literal.split("\n", -1)) {
            writer.println(line.isEmpty() ? "#" : "# " + line);
        }
        writer.println();
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
        //
        // The version is emitted independently of the ontology IRI. It used to be nested
        // inside it, so a document declaring @version and no @ontology had its version
        // written nowhere: the reader gives such a document the default IRI — a version IRI
        // cannot be held without one — and that default is exactly what the test below
        // suppresses.
        // An ontology IRI is written whenever there is one. The test used to exclude one
        // particular IRI, because the reader invented that IRI for documents which declared
        // none — so a document whose ontology IRI genuinely *was* that one silently lost it.
        // The reader no longer invents anything, so there is nothing to suppress.
        OWLOntologyID id = ontology.getOntologyID();
        boolean named = id.getOntologyIRI().isPresent();
        if (named) {
            writer.println("@ontology <" + id.getOntologyIRI().get() + ">");
        }
        if (id.getVersionIRI().isPresent()) {
            writer.println("@version <" + id.getVersionIRI().get() + ">");
        }
        if (named || id.getVersionIRI().isPresent()) {
            writer.println();
        }
        ontology.importsDeclarations().sorted().forEach(decl ->
            writer.println("@import " + renderImport(decl.getIRI())));
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
