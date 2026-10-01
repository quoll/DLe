package org.semanticweb.owlapi.dlesyntax;

import org.antlr.v4.runtime.ParserRuleContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.semanticweb.owlapi.model.*;

import javax.annotation.Nullable;
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
    /** Annotation property IRI used to preserve DLE block {@code #} comments through the OWL model. */
    static final IRI DLE_COMMENT_IRI = IRI.create(DLE_NS + "comment");
    /** Annotation property IRI used to preserve DLE trailing inline {@code #} comments. */
    static final IRI DLE_INLINE_COMMENT_IRI = IRI.create(DLE_NS + "inlineComment");
    private static final IRI RDF_VALUE_IRI =
        IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");

    private final OWLDataFactory df;
    private final Set<String> objectPropertyNames;
    private final Set<String> dataPropertyNames;
    private final Set<String> predicateNames;
    /**
     * Datatypes the document defines itself, by {@code Code ≡ [xsd:string ⊓ […]]}.
     *
     * <p>A built-in list cannot know about these, so without them a document's own datatype
     * was read as a class and every data property ranged on it became an object property.
     */
    private final Set<String> datatypeNames;

    /** Whether this name is a datatype: defined here, or one of the known ones. */
    private boolean namesADatatype(String text, @Nullable IRI iri) {
        return datatypeNames.contains(text)
            || EntityTypeScanner.isDatatypeIri(iri == null ? null : iri.toString());
    }
    /** Names stated to be roles by {@code X ⊑ owl:topObjectProperty}; see visitSubClassAxiom. */
    private final Set<String> explicitRoleNames;
    /**
     * Names the document uses as annotation properties.
     *
     * <p>Lets `ap ⊑ bp` be read as the annotation subsumption it is, when the document has
     * said elsewhere what `ap` is. Without this the pair went through the object branch and
     * punned the name across two property kinds.
     */
    private final Set<String> annotationPropertyNames;
    /**
     * Names that are both a role and a class. A name resolves to a single kind, so without
     * this a punned name is a property everywhere and every class position it appears in is
     * rejected — which made stating a pun the thing that broke it.
     */
    private final Set<String> punnedNames;
    /** IRIs of {@link #punnedNames}, resolved on first use; see {@link #isPunned(IRI)}. */
    private Set<IRI> punnedIRIs;
    /**
     * IRIs whose kind the document stated outright. The dual-declaration resolver must not
     * second-guess these: a document that says a name is punned is the only authority on the
     * matter, and pushing the pun down to the name's children is exactly what the statements
     * exist to prevent.
     */
    private final Set<IRI> statedKindIRIs = new HashSet<>();
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
    private int currentLine = -1;

    /** Ontology IRI from {@code @ontology}, null if not declared. */
    private IRI ontologyIRI = null;
    /** Version IRI from {@code @version}, null if not declared. */
    private IRI versionIRI  = null;
    /**
     * Comments that belong to the document rather than to any entity.
     *
     * <p>Two kinds reach here: the block after the last statement, and a block above a
     * statement that names nothing — `@prefix` and the other header directives — which was
     * previously dropped outright.
     */
    private final List<String> documentComments = new ArrayList<>();
    /** Where @version was declared, so a refusal raised after the parse can point at it. */
    private int versionLine = -1;
    private int versionColumn;
    /** `@import <iri>` references, used exactly as written. */
    private final List<String> iriImportRefs = new ArrayList<>();
    /** `@import "…"` references: an IRI with a retrievable scheme, or a file path. */
    private final List<String> quotedImportRefs = new ArrayList<>();

    DLESyntaxAxiomVisitor(OWLDataFactory df,
                          Set<String> objectPropertyNames,
                          Set<String> dataPropertyNames,
                          Set<String> predicateNames,
                          Set<String> explicitRoleNames,
                          Set<String> annotationPropertyNames,
                          Set<String> punnedNames,
                          Set<String> datatypeNames,
                          CommonTokenStream tokenStream) {
        this.df = df;
        this.objectPropertyNames = objectPropertyNames;
        this.dataPropertyNames   = dataPropertyNames;
        this.predicateNames      = predicateNames;
        this.datatypeNames       = datatypeNames;
        this.explicitRoleNames   = explicitRoleNames;
        this.annotationPropertyNames = annotationPropertyNames;
        this.punnedNames         = punnedNames;
        this.tokenStream         = tokenStream;
    }

    List<OWLAxiom> getAxioms()        { return axioms; }

    /** Problems that did not stop the parse; see {@link #warnings}. */
    List<String> getWarnings()        { return warnings; }
    List<String> getDocumentComments() { return documentComments; }
    Map<String, String> getPrefixes() { return prefixes; }

    /** IRIs whose kind the document stated; see {@link #statedKindIRIs}. */
    Set<IRI> getStatedKindIRIs() { return statedKindIRIs; }
    IRI getOntologyIRI()              { return ontologyIRI; }
    IRI getVersionIRI()               { return versionIRI; }
    int getVersionLine()              { return versionLine; }
    int getVersionColumn()            { return versionColumn; }
    /** `@import <iri>` references; see visitImportDecl. */
    List<String> getIriImportRefs()    { return iriImportRefs; }

    /** `@import "…"` references; see visitImportDecl. */
    List<String> getQuotedImportRefs() { return quotedImportRefs; }

    // ── Prefix declarations ──────────────────────────────────────────────────


    /** Prefix labels this document declared, as opposed to the standard pre-seeded ones. */
    private final Set<String> declaredPrefixes = new HashSet<>();

    /**
     * Problems that do not stop the parse.
     *
     * <p>Collected as well as logged. A log line needs an slf4j binding to go anywhere, and
     * there is none in this module's tests or in a plain embedding, so a warning that only
     * logged would be neither visible to a caller nor testable here.
     */
    private final List<String> warnings = new ArrayList<>();

    /**
     * Warns when two prefixes are declared for one namespace.
     *
     * <p>Nothing goes wrong until the same entity is written both ways, and then it goes
     * wrong quietly. Classification is keyed on the name as written, so {@code Attr} and
     * {@code a:Attr} are two unrelated names to it while resolving to one IRI for everything
     * downstream. A kind stated under one spelling and used under the other therefore never
     * pairs up: the name is not seen as punned, a sub-property axiom below it is refiled as
     * a class subsumption, and its {@code X ⊑ ⊤} survives as a real subsumption instead of
     * being read as the declaration it was written as.
     *
     * <p>Keying classification on resolved IRIs is the actual fix, and is a change across
     * the whole of it. This is not that: the writer renders each IRI through one prefix, so
     * the situation cannot arise from a round trip and needs an authored document to reach.
     * The warning is here so that if one ever does, it is visible rather than a silently
     * mis-typed hierarchy.
     */
    private void warnOnDuplicateNamespace(String label, String namespace) {
        for (Map.Entry<String, String> existing : prefixes.entrySet()) {
            // Only against prefixes this document declared. The map is pre-seeded with the
            // standard ones, and binding a second prefix to a seeded namespace is a
            // supported thing to do — `@prefix o: <…owl#>` works and is tested. Comparing
            // against the seeds would warn about that, and about a document redeclaring the
            // default prefix, neither of which is the hazard here.
            if (!declaredPrefixes.contains(existing.getKey())) continue;
            if (existing.getValue().equals(namespace) && !existing.getKey().equals(label)) {
                String message = "prefixes " + existing.getKey() + " and " + label
                    + " are both declared for <" + namespace + ">. Entity kinds are tracked"
                    + " per spelling, so writing one entity both ways can misclassify it;"
                    + " use one prefix per namespace";
                // Recorded only. The parser logs every warning it collects, so logging
                // here as well produced two records of one problem, under two logger names.
                warnings.add(message);
                return;
            }
        }
    }

    @Override
    public OWLObject visitPrefixDecl(DLESyntaxParser.PrefixDeclContext ctx) {
        // PNAME_NS token text includes the trailing colon, e.g. "xsd:" or ":"
        String prefixLabel = ctx.PNAME_NS().getText();        // strip the trailing colon to get the namespace identifier
        String prefixName  = prefixLabel.endsWith(":") ? prefixLabel : prefixLabel + ":";
        // Either spelling: the token carries its own delimiters, angle brackets or quotes.
        String iri = EntityTypeScanner.namespaceOf(ctx);
        warnOnDuplicateNamespace(prefixName, iri);
        declaredPrefixes.add(prefixName);
        prefixes.put(prefixName, iri);
        return null;
    }

    @Override
    public OWLObject visitOntologyDecl(DLESyntaxParser.OntologyDeclContext ctx) {
        // A document names itself once. Two declarations are not a merge and not a
        // choice; taking the last silently discards the first, which is how a document
        // ends up identified as something its author never intended.
        if (ontologyIRI != null) {
            throw new DLESemanticException(
                "duplicate @ontology: this document already declared itself as <"
                    + ontologyIRI + ">, and an ontology has one identity",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        ontologyIRI = requireAbsolute(identityIri(ctx.iriRef(), ctx.STRING(), "@ontology", ctx),
            "@ontology", ctx);
        return null;
    }

    @Override
    public OWLObject visitVersionDecl(DLESyntaxParser.VersionDeclContext ctx) {
        if (versionIRI != null) {
            throw new DLESemanticException(
                "duplicate @version: this document already declared version <"
                    + versionIRI + ">",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        versionIRI = requireAbsolute(identityIri(ctx.iriRef(), ctx.STRING(), "@version", ctx),
            "@version", ctx);
        versionLine = ctx.start.getLine();
        versionColumn = ctx.start.getCharPositionInLine();
        return null;
    }

    /**
     * Requires the angle-bracket form for an identity, refusing a bare or prefixed name.
     *
     * <p>The grammar's {@code iriRef} allows either, and a name is expanded through the
     * prefix map — so {@code @ontology onto} silently became
     * {@code <http://quoll.github.io/DLe/ontology#onto>}, an identity minted under this
     * project's own namespace. Worse, which namespace it landed in depended on whether a
     * {@code @prefix :} line had been read yet, so moving the declaration changed the
     * document's identity. That is the same hazard as the fixed sentinel this replaced, and
     * {@link #requireAbsolute} cannot catch it because the expansion is absolute.
     *
     * <p>Only the identity declarations are restricted. {@code @import} keeps the name form,
     * where resolving through a prefix is a convenience and names no local resource.
     */
    /**
     * The IRI an identity directive names, written either way.
     *
     * <p>`@import` has always taken a quoted string as well as an angle-bracket IRI, so an
     * author who has written one writes the other — and a document naming itself
     * {@code @ontology "https://…"} was refused with {@code extraneous input} and a list of
     * the tokens the parser wanted instead, which explains nothing about what was wrong.
     *
     * <p>Unlike an import, there is no relative reading to fall back on: an identity must be
     * an absolute IRI, and {@link #requireAbsolute} still enforces that on either form. A
     * language tag is refused for the same reason it is on an import — this names a document,
     * not text.
     */
    private IRI identityIri(@Nullable DLESyntaxParser.IriRefContext ref,
                            @Nullable org.antlr.v4.runtime.tree.TerminalNode quoted,
                            String keyword, ParserRuleContext ctx) {
        if (quoted != null) {
            String text = quoted.getText();
            if (languageTag(text) != null) {
                throw new DLESemanticException(
                    keyword + " cannot carry a language tag: " + text
                        + ". It names a document, not text.",
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
            }
            return IRI.create(unquote(text));
        }
        requireExplicitIri(ref, keyword, ctx);
        return expandIriRef(ref);
    }

    private void requireExplicitIri(DLESyntaxParser.IriRefContext ref, String keyword,
                                    org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ref.IRI() == null) {
            throw new DLESemanticException(
                keyword + " must be written as a full IRI in angle brackets, not as a name."
                    + " A name is expanded through the prefix map, so <" + ref.getText()
                    + "> would identify this document relative to a namespace — and to"
                    + " whichever prefix declarations happened to precede it.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
    }

    /**
     * Rejects a relative IRI used as an ontology or version identity.
     *
     * <p>OWL 2 requires both to be absolute, and for good reason: they identify the
     * document to everything that imports it, so an identity that means different
     * things depending on where it is read is not an identity. OWL API does not check
     * this, and a relative one survives as far as the first importer, which is a much
     * worse place to find out.
     */
    private IRI requireAbsolute(IRI iri, String keyword,
                                org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (!iri.toURI().isAbsolute()) {
            throw new DLESemanticException(
                keyword + " must be an absolute IRI, but <" + iri + "> is relative."
                    + " An ontology's identity has to mean the same thing to everything"
                    + " that imports it.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        return iri;
    }

    @Override
    public OWLObject visitImportDecl(DLESyntaxParser.ImportDeclContext ctx) {
        // The two forms mean different things, so they are kept apart.
        //
        // `@import <iri>` is an IRI and is handed to OWL API as written — no resolution, no
        // interpretation. That is the escape hatch for any scheme, including ones only an
        // IRI mapper or catalogue can resolve.
        //
        // `@import "…"` is the extension. It is an IRI only when it carries a scheme OWL API
        // can actually retrieve; otherwise it is a file path, taken literally. The caller
        // resolves it, because only the caller knows where this document is.
        if (ctx.STRING() != null) {
            String ref = ctx.STRING().getText();
            if (languageTag(ref) != null) {
                throw new DLESemanticException(
                    "an import reference cannot carry a language tag: " + ref
                        + ". It names a document, not text.",
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
            }
            quotedImportRefs.add(unquote(ref));
        } else {
            iriImportRefs.add(expandIriRef(ctx.iriRef()).toString());
        }
        return null;
    }

    /**
     * Strips the quotes from a STRING token and unescapes it, the same way everywhere.
     *
     * <p>Shared with {@link #stringLiteral}: one token type should not have two escaping
     * dialects. A backslash before anything else is kept, which matters for the reference
     * form this is used for — a Windows path such as {@code "C:\vocab.dle"} must not lose
     * its separator.
     */
    static String unquote(String token) {
        // The closing quote is not necessarily the last character: a STRING token may carry
        // a language tag after it. No tag can contain a quote, so the last one in the token
        // is the closing one either way.
        int close = token.lastIndexOf('"');
        return token.substring(1, close)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    /**
     * The language tag of a STRING token, or null if it has none.
     *
     * <p>Everything after the closing quote, without the {@code @}.
     */
    @Nullable
    static String languageTag(String token) {
        int close = token.lastIndexOf('"');
        // The '@' has to be there. Anything after the closing quote used to count as a tag,
        // so once `^^` existed `"2024-01-01"^^xsd:date` reported a tag of `^xsd:date` — and
        // the facet check then refused a perfectly good typed value for carrying a language.
        if (close + 1 >= token.length() || token.charAt(close + 1) != '@') return null;
        return token.substring(close + 2);
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

        String label = ctx.name(0).getText() + "(" + String.join(",", args) + ")";

        OWLAnnotationProperty predProp = df.getOWLAnnotationProperty(predicateIRI);
        axioms.add(df.getOWLDeclarationAxiom(predProp));
        // rdf:value before rdfs:label, because the writer renders the `≝` statement from the
        // rdf:value assertion and `firstWritableAxiom` hands a comment to the first axiom the
        // statement produced. With the label first, a comment above a predicate definition
        // landed on an axiom that is never written as a statement: nothing emitted it, and it
        // came back as a document comment at the far end of the file, moving once per pass.
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(RDF_VALUE_IRI),
            predicateIRI, df.getOWLLiteral(rdfValue)));
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
            predicateIRI, df.getOWLLiteral(label)));
        return null;
    }

    // ── Annotations ──────────────────────────────────────────────────────────

    @Override
    public OWLObject visitLabelAnnotation(DLESyntaxParser.LabelAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = annotationLiteral(ctx.annotationString());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitDocAnnotation(DLESyntaxParser.DocAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = annotationLiteral(ctx.annotationString());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_COMMENT.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitStorageAnnotation(DLESyntaxParser.StorageAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = annotationLiteral(ctx.annotationString());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_SEE_ALSO.getIRI()),
            subject, value));
        return null;
    }

    @Override
    public OWLObject visitDbAnnotation(DLESyntaxParser.DbAnnotationContext ctx) {
        IRI subject = expandName(ctx.name());
        OWLLiteral value = ctx.annotationString() != null
            ? annotationLiteral(ctx.annotationString())
            : df.getOWLLiteral(ctx.name().getText());
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
        OWLAnnotationSubject subject = annotationSubject(ctx.name(0));
        IRI propIRI  = expandName(ctx.name(1));
        OWLAnnotationValue value = (OWLAnnotationValue) visit(ctx.annotationValue());
        axioms.add(df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(propIRI), subject, value));
        return null;
    }

    @Override
    public OWLObject visitStringAnnotationValue(DLESyntaxParser.StringAnnotationValueContext ctx) {
        return annotationLiteral(ctx.annotationString());
    }

    @Override
    public OWLObject visitIriAnnotationValue(DLESyntaxParser.IriAnnotationValueContext ctx) {
        // A blank node is a legal annotation value as well as a legal subject.
        if (isBlankNodeName(ctx.name().getText())) {
            return df.getOWLAnonymousIndividual(ctx.name().getText());
        }
        return expandName(ctx.name());
    }

    /**
     * An annotation's subject: an IRI, or a blank node.
     *
     * <p>OWL allows either, and the writer emits either — but this read the subject as an IRI
     * unconditionally, so `@ann _:genid2147483648 note "s"` came back as "unknown prefix
     * '_:'". A document with a blank node anywhere in an annotation was written and then
     * refused, which is the one place `_:` was still not understood after the logical
     * positions were fixed.
     */
    private OWLAnnotationSubject annotationSubject(DLESyntaxParser.NameContext ctx) {
        if (isBlankNodeName(ctx.getText())) {
            return df.getOWLAnonymousIndividual(ctx.getText());
        }
        return expandName(ctx);
    }

    // ── Axioms ───────────────────────────────────────────────────────────────

    @Override
    public OWLObject visitSubClassAxiom(DLESyntaxParser.SubClassAxiomContext ctx) {
        // The two kind statements, consumed rather than turned into axioms.
        //
        // `X ⊑ owl:topObjectProperty` and `X ⊑ owl:topDataProperty` say what kind of thing
        // X is, for a name whose kind cannot be inferred: a pun, or one that breaks the
        // convention that concepts are capitalised. Both are tautologies in OWL — every
        // object property is a sub-property of owl:topObjectProperty — so emitting them as
        // axioms would add nothing. The writer re-emits the kind from the entity's own
        // declarations, and suppresses its statement when the axiom is already there, so
        // consuming here costs the tautology and nothing else.
        //
        // `X ⊑ ⊤` is only consumed when X is *also* stated to be a role, i.e. when the pair
        // marks a pun. On its own it stays the ordinary subsumption it has always been,
        // which is what leaves untouched the corpus documents that open with it.
        if (consumeKindStatement(ctx)) return null;

        // DisjointObjectProperties / DisjointDataProperties: p ⊓ q ⊑ ⊥
        // Must be detected from parse tree before visiting, to avoid asClass() failure.
        List<DLESyntaxParser.NameContext> disjointNames = allIntersectedNameCtxs(ctx.classExpr(0));
        if (disjointNames != null && isBottomClassExpr(ctx.classExpr(1))) {
            List<String> texts = disjointNames.stream()
                .map(n -> n.getText()).collect(Collectors.toList());
            // The same refusal as the Disj(...) spelling, which this is the other half of.
            // `p \u2293 p \u2291 \u22a5` built the unary axiom OWL rejects, and the
            // writer then spelled it `Disj(p)`, a form the grammar does not have, so the
            // document stopped reloading.
            List<IRI> disjointIris = disjointNames.stream().map(this::expandName)
                .collect(Collectors.toList());
            boolean repeated = new LinkedHashSet<>(disjointIris).size() < disjointIris.size();
            if (repeated && (texts.stream().allMatch(objectPropertyNames::contains)
                    || texts.stream().allMatch(dataPropertyNames::contains))) {
                throw new DLESemanticException(
                    "a property is named twice in this disjointness statement. Disjointness"
                        + " holds between different properties; name each one once.",
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
            }
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

        // No functional/inverse-functional recognition here any more. It used to turn
        // `⊤ ⊑ ≤1 r` into `FunctionalObjectProperty(r)`, which is a true equivalence but a
        // lossy one in practice: `FunctionalObjectProperty(r)` and `SubClassOf(⊤ ≤1 r)` are
        // two OWL axioms, the writer spells them differently, and reading both as the first
        // meant the second could not survive a round trip. Each axiom now has one spelling —
        // `Func(r)`, `Func(d)` and `Func(r⁻)` for the three dedicated ones, the cardinality
        // syntax for the cardinality ones — so nothing has to be recognised back.

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
        // Domain, the textbook's spelling: `(≥1 p) ⊑ C`.
        //
        // The one form that works for both kinds, because it names no filler and so does not
        // have to say which universe the filler lives in. That is why the textbook writes a
        // data property domain this way: `∃d.⊤ ⊑ C` would put the top *concept* where a data
        // range belongs, and OWL keeps the two universes disjoint.
        //
        // Only a minimum of one. `≥2 p ⊑ C` says something else and stays the subsumption it
        // is, as does any bound with a filler narrower than the top of its universe.
        if (lhs instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality min = (OWLObjectMinCardinality) lhs;
            if (min.getCardinality() == 1 && min.getFiller().isOWLThing()) {
                axioms.add(df.getOWLObjectPropertyDomainAxiom(min.getProperty(), asClass(rhs)));
                return null;
            }
        }
        if (lhs instanceof OWLDataMinCardinality) {
            OWLDataMinCardinality min = (OWLDataMinCardinality) lhs;
            if (min.getCardinality() == 1 && min.getFiller().isTopDatatype()) {
                axioms.add(df.getOWLDataPropertyDomainAxiom(min.getProperty(), asClass(rhs)));
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

        // `ap ⊑ bp` between two annotation properties. DLe writes an annotation subsumption
        // exactly as it writes an object one, so which it is can only come from what the
        // document says elsewhere about the names — `@ann`, `domain` or `range`. Without
        // this the pair went through the object branch below and punned the name across two
        // property kinds, out of the OWL 2 DL profile.
        //
        // Both sides, because one annotation property beneath an object property is not an
        // axiom OWL has; that stays a mixed-hierarchy error, reported below.
        String lhsName = loneName(ctx.classExpr(0));
        String rhsName = loneName(ctx.classExpr(1));
        if (lhsName != null && rhsName != null
                && annotationPropertyNames.contains(lhsName)
                && annotationPropertyNames.contains(rhsName)) {
            axioms.add(df.getOWLSubAnnotationPropertyOfAxiom(
                df.getOWLAnnotationProperty(expandNameText(lhsName)),
                df.getOWLAnnotationProperty(expandNameText(rhsName))));
            return null;
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
        // A data property under an object property, or the reverse. OWL has no such axiom —
        // the two hierarchies are disjoint — so this cannot be built. Say that, rather than
        // falling through to the class backstop and blaming a datatype for it.
        if ((lhs instanceof OWLDataPropertyExpression && rhs instanceof OWLObjectPropertyExpression)
                || (lhs instanceof OWLObjectPropertyExpression
                    && rhs instanceof OWLDataPropertyExpression)) {
            throw new DLESemanticException(
                "cannot subsume " + describeKind(lhs) + " " + describe(lhs) + " under "
                    + describeKind(rhs) + " " + describe(rhs)
                    + ". OWL keeps object and data properties in separate hierarchies, so one"
                    + " cannot be a sub-property of the other. State the intended kind with"
                    + " ⊑ owl:topObjectProperty or ⊑ owl:topDataProperty.",
                currentLine, 0);
        }

        // Disjointness: X ⊑ ¬ Y
        //
        // DL says disjointness with a subsumption of a complement, which is how the writer
        // spells it, so reading it literally lost the axiom type on every round trip. There
        // is one spelling and two OWL axioms, so only one of them can survive; the
        // disjointness is the more specific of the two and is what the writer started from.
        //
        // A disjointness of three or more is written as its pairs, and comes back as those
        // pairs — the same statement, since that is what disjointness of a set means.
        if (rhs instanceof OWLObjectComplementOf && lhs instanceof OWLClassExpression) {
            OWLClassExpression complemented = ((OWLObjectComplementOf) rhs).getOperand();
            OWLClassExpression subject = asClass(lhs);
            // Not when the two sides are the same class. `A ⊑ ¬A` says A is empty, which is
            // a real thing to say; `DisjointClasses(:A :A)` is a set of one class and says
            // nothing at all, so the conversion would throw the axiom away — and OWL API
            // collapses the pair, leaving a degenerate axiom the writer cannot spell either.
            if (!subject.equals(complemented)) {
                axioms.add(df.getOWLDisjointClassesAxiom(subject, complemented));
                return null;
            }
        }

        // Mixed: one side is a named property and the other resolved as a class.
        // This occurs at the boundary of dual-use hierarchies (e.g. SNOMED-CT attribute root).
        // Coerce the property side to a class so the class node keeps its class identity.
        //
        // Written against OWLProperty rather than OWLObjectProperty: the object-only version
        // left the data case with no path but the asClass backstop, so a data property under
        // a class name — including a SNOMED CT concrete-domain attribute under a punned root
        // — rejected the whole document.
        if (lhs instanceof OWLProperty && rhs instanceof OWLClass) {
            axioms.add(df.getOWLSubClassOfAxiom(
                df.getOWLClass(((OWLProperty) lhs).getIRI()),
                (OWLClass) rhs));
            return null;
        }
        if (lhs instanceof OWLClass && rhs instanceof OWLProperty) {
            axioms.add(df.getOWLSubClassOfAxiom(
                (OWLClass) lhs,
                df.getOWLClass(((OWLProperty) rhs).getIRI())));
            return null;
        }

        axioms.add(df.getOWLSubClassOfAxiom(asClass(lhs), asClass(rhs)));
        return null;
    }

    @Override
    public OWLObject visitEquivAxiom(DLESyntaxParser.EquivAxiomContext ctx) {
        List<OWLObject> operands = ctx.classExpr().stream()
            .map(this::visit).collect(Collectors.toList());

        // Equivalence holds within one kind, so the whole statement is a property
        // equivalence if any operand can only be a property. A bare OWLClass among them is
        // a property the scanner did not classify — an upper-case name, most often — and is
        // coerced rather than allowed to split the statement across two kinds.
        boolean objects = operands.stream().anyMatch(OWLObjectPropertyExpression.class::isInstance);
        boolean data = operands.stream().anyMatch(OWLDataPropertyExpression.class::isInstance);

        // A datatype definition: Code ≡ [xsd:string ⊓ [minLength 3]]
        //
        // DL has no separate notation for one, and a definition is an equivalence, so `≡`
        // is the spelling. Without it the writer produced nothing at all for a
        // DatatypeDefinition — the axiom and the datatype both vanished — and the statement
        // a person would reach for was answered with "expected a class expression here".
        //
        // One side a data range and the other a bare name is unambiguous: a class
        // equivalence cannot have a data range on either side.
        if (operands.size() == 2) {
            // The name comes from operand i and the range from the other one, so i == 0 tries
            // the left-hand side as the defined datatype. It used to be the other way round,
            // which was invisible while one side was always a built-in — the guard below
            // rejects a built-in as the defined name, so the correct side won by elimination.
            // With a datatype on both sides nothing eliminated anything and the right-hand
            // side won: `T2 ≡ T` was read as `DatatypeDefinition(:T :T2)`, which writes as
            // `T ≡ T2`, which reads as `DatatypeDefinition(:T2 :T)`. The document alternated
            // between two forms forever and never reached a fixed point — the only such
            // document found in 727 fixtures.
            for (int i = 0; i < 2; i++) {
                OWLObject range = operands.get(1 - i);
                DLESyntaxParser.ClassExprContext other = ctx.classExpr(i);
                String name = loneName(other);
                // Any data range, a plain datatype included: `Code ≡ xsd:string` is an
                // alias, and OWL's DatatypeDefinition takes any range.
                //
                // The guard is against a *built-in* datatype on the left, so that
                // `xsd:string ≡ xsd:string` does not qualify. It deliberately does not use
                // namesADatatype: that already includes the name being defined here, which
                // the scanner recorded on its way past, so guarding with it excluded every
                // definition from being read as one.
                if (name == null || !(range instanceof OWLDataRange)) continue;
                IRI nameIri = expandNameText(name);
                if (!EntityTypeScanner.isDatatypeIri(
                        nameIri == null ? null : nameIri.toString())) {
                    axioms.add(df.getOWLDatatypeDefinitionAxiom(
                        df.getOWLDatatype(expandNameText(name)), (OWLDataRange) range));
                    return null;
                }
            }
        }

        // Inverse properties: r ≡ s⁻
        //
        // The same choice as the disjointness idiom above. DLe writes both
        // `InverseObjectProperties(:r :s)` and
        // `EquivalentObjectProperties(:r ObjectInverseOf(:s))` as `r ≡ s⁻`, so one of them
        // has to be what it reads back as, and the dedicated axiom is the more specific.
        //
        // Both sides named, exactly two operands: `r⁻ ≡ s⁻` says r and s are equivalent
        // rather than inverse, and stays the general equivalence it is.
        if (operands.size() == 2) {
            OWLObject first = operands.get(0);
            OWLObject second = operands.get(1);
            OWLObjectPropertyExpression named = null;
            OWLObjectPropertyExpression inverted = null;
            for (int i = 0; i < 2; i++) {
                OWLObject candidate = i == 0 ? first : second;
                OWLObject other = i == 0 ? second : first;
                if (candidate instanceof OWLObjectProperty
                        && other instanceof OWLObjectPropertyExpression
                        && ((OWLObjectPropertyExpression) other).isAnonymous()
                        && !((OWLObjectPropertyExpression) other)
                            .getInverseProperty().getSimplified().isAnonymous()) {
                    named = (OWLObjectPropertyExpression) candidate;
                    inverted = ((OWLObjectPropertyExpression) other)
                        .getInverseProperty().getSimplified();
                }
            }
            if (named != null) {
                axioms.add(df.getOWLInverseObjectPropertiesAxiom(named, inverted));
                return null;
            }
        }

        // A class expression is not a property, so an equivalence with one on either side
        // is not between properties whatever the names suggested. Without this the operand
        // went into the cast below and what the user saw was
        // `OWLObjectIntersectionOfImpl cannot be cast to OWLObjectPropertyExpression` — an
        // OWL API internal, for a document whose only fault is not saying what its names
        // are. The scanner now reads `⊓`, `⊔` and `¬` as class evidence, which settles the
        // cases that arose from a guess; this catches whatever a stated kind can still
        // build, and any shape not yet thought of.
        if (objects || data) {
            for (OWLObject operand : operands) {
                if (operand instanceof OWLObjectPropertyExpression
                        || operand instanceof OWLDataPropertyExpression
                        || operand instanceof OWLClass) {
                    continue;
                }
                throw new DLESemanticException(
                    "cannot make a property equivalent to a class expression."
                        + " `⊓`, `⊔` and `¬` build classes, not properties, so an"
                        + " equivalence using one is between classes — but a name in this"
                        + " one is already a property. State the intended kind with"
                        + " `X ⊑ ⊤` for a class.",
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
            }
        }
        if (objects && !data) {
            List<OWLObjectPropertyExpression> props = operands.stream()
                .map(o -> o instanceof OWLClass
                    ? df.getOWLObjectProperty(((OWLClass) o).getIRI())
                    : (OWLObjectPropertyExpression) o)
                .collect(Collectors.toList());
            axioms.add(df.getOWLEquivalentObjectPropertiesAxiom(props));
            return null;
        }
        if (data && !objects) {
            List<OWLDataPropertyExpression> props = operands.stream()
                .map(o -> o instanceof OWLClass
                    ? df.getOWLDataProperty(((OWLClass) o).getIRI())
                    : (OWLDataPropertyExpression) o)
                .collect(Collectors.toList());
            axioms.add(df.getOWLEquivalentDataPropertiesAxiom(props));
            return null;
        }
        if (objects && data) {
            throw new DLESemanticException(
                "this equivalence mixes a data property with an object property."
                    + " Equivalence holds between properties of one kind.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        axioms.add(df.getOWLEquivalentClassesAxiom(
            operands.stream().map(this::asClass).collect(Collectors.toList())));
        return null;
    }

    @Override
    public OWLObject visitFunctionalPropertyAxiom(DLESyntaxParser.FunctionalPropertyAxiomContext ctx) {
        // A bare cardinality statement abbreviates `⊤ ⊑ <expr>`, and is read as exactly that.
        //
        // It used to build `FunctionalObjectProperty` from the property alone, discarding the
        // symbol, the number and the filler — so `≤1 r.C`, `≤3 r.⊤` and `≥5 r.C` all came out
        // as functionality, the last of them asserting something its own document contradicts.
        // Only `≤1 r.⊤` was ever right, and that one still reads as at-most-one because that
        // is what it says.
        axioms.add(df.getOWLSubClassOfAxiom(df.getOWLThing(),
            cardinalityRestriction(ctx.cardSymbol(), ctx.NUMBER().getText(),
                ctx.propertyExpr(), visit(ctx.classExpr()))));
        return null;
    }

    @Override
    public OWLObject visitHasKeyAxiom(DLESyntaxParser.HasKeyAxiomContext ctx) {
        OWLClassExpression ce = asClass(visit(ctx.classExpr()));
        List<OWLPropertyExpression> keys = ctx.keyExpr().propertyExpr().stream()
            .map(expr -> {
                DLESyntaxParser.NameContext n = PropertyExprs.coreName(expr);
                OWLObject obj = nameToPropertyOrClass(n);
                if (obj instanceof OWLDataPropertyExpression) {
                    refuseInverseOnDataProperty("key", expr);
                    return (OWLPropertyExpression) obj;
                }
                // An object property, or an unclassified name — which defaults to one.
                return (OWLPropertyExpression) buildObjectProp(expr);
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
        axioms.add(df.getOWLTransitiveObjectPropertyAxiom(objectOnlyProp("Trans", ctx.propertyExpr())));
        return null;
    }

    @Override
    public OWLObject visitFunctionalRoleAxiom(DLESyntaxParser.FunctionalRoleAxiomContext ctx) {
        String name = PropertyExprs.coreNameText(ctx.propertyExpr());
        if (dataPropertyNames.contains(name)) {
            refuseInverseOnDataProperty("Func", ctx.propertyExpr());
            axioms.add(df.getOWLFunctionalDataPropertyAxiom(
                df.getOWLDataProperty(expandName(PropertyExprs.coreName(ctx.propertyExpr())))));
        } else {
            // `Func(r⁻)` is OWL's InverseFunctionalObjectProperty, which is the axiom the
            // writer now emits it for. Built as FunctionalObjectProperty(ObjectInverseOf(r))
            // it says the same thing in a shape OWL has a dedicated axiom for, and the round
            // trip changed the axiom type every pass.
            OWLObjectPropertyExpression property = buildObjectProp(ctx.propertyExpr());
            if (property.isAnonymous()) {
                axioms.add(df.getOWLInverseFunctionalObjectPropertyAxiom(
                    property.getInverseProperty().getSimplified()));
            } else {
                axioms.add(df.getOWLFunctionalObjectPropertyAxiom(property));
            }
        }
        return null;
    }

    @Override
    public OWLObject visitReflexiveRoleAxiom(DLESyntaxParser.ReflexiveRoleAxiomContext ctx) {
        axioms.add(df.getOWLReflexiveObjectPropertyAxiom(objectOnlyProp("Ref", ctx.propertyExpr())));
        return null;
    }

    @Override
    public OWLObject visitIrreflexiveRoleAxiom(DLESyntaxParser.IrreflexiveRoleAxiomContext ctx) {
        axioms.add(df.getOWLIrreflexiveObjectPropertyAxiom(objectOnlyProp("Irref", ctx.propertyExpr())));
        return null;
    }

    @Override
    public OWLObject visitSymmetricRoleAxiom(DLESyntaxParser.SymmetricRoleAxiomContext ctx) {
        axioms.add(df.getOWLSymmetricObjectPropertyAxiom(objectOnlyProp("Sym", ctx.propertyExpr())));
        return null;
    }

    @Override
    public OWLObject visitAsymmetricRoleAxiom(DLESyntaxParser.AsymmetricRoleAxiomContext ctx) {
        axioms.add(df.getOWLAsymmetricObjectPropertyAxiom(objectOnlyProp("Asym", ctx.propertyExpr())));
        return null;
    }

    @Override
    public OWLObject visitDisjointRoleAxiom(DLESyntaxParser.DisjointRoleAxiomContext ctx) {
        // Disjointness, unlike the characteristics above, has both forms. It used to build
        // the object one whatever the names were, so `Disj(p, q)` over two data properties
        // declared them as object properties as well — the same illegal punning. The
        // intersection spelling `p ⊓ q ⊑ ⊥` already routed by kind; this now matches it.
        List<String> names = ctx.propertyExpr().stream()
            .map(PropertyExprs::coreNameText).collect(Collectors.toList());
        // Deduped on the resolved IRI, not the spelling: with two prefixes bound to one
        // namespace, `Disj(e1:p, e2:p)` named one property twice, passed a text-based
        // check, and OWL then collapsed the pair into the unary axiom this rejects.
        // The resolved IRI and whether it is inverted: `Disj(r, r⁻)` names two different
        // property expressions, so it is not the repeat that `Disj(r, r)` is.
        List<String> resolved = ctx.propertyExpr().stream()
            .map(p -> expandName(PropertyExprs.coreName(p))
                + (PropertyExprs.isInverse(p) ? "\u207b" : ""))
            .collect(Collectors.toList());
        // Disjointness needs two distinct properties. `Disj(p, p)` built a unary axiom,
        // which OWL rejects as a profile violation and which the writer then emitted as
        // `Disj(p)` — a form the grammar does not accept, so the document would not reload.
        if (new LinkedHashSet<>(resolved).size() < resolved.size()) {
            throw new DLESemanticException(
                "a property is named twice in this disjointness statement. Disjointness"
                    + " holds between different properties; name each one once.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        if (names.stream().anyMatch(dataPropertyNames::contains)) {
            if (!names.stream().allMatch(dataPropertyNames::contains)) {
                throw new DLESemanticException(
                    "Disj mixes a data property with an object property: " + names
                        + ". Disjointness holds between properties of one kind.",
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
            }
            ctx.propertyExpr().forEach(p -> refuseInverseOnDataProperty("Disj", p));
            List<OWLDataPropertyExpression> dataProps = ctx.propertyExpr().stream()
                .map(p -> (OWLDataPropertyExpression) df.getOWLDataProperty(
                    expandName(PropertyExprs.coreName(p))))
                .collect(Collectors.toList());
            axioms.add(df.getOWLDisjointDataPropertiesAxiom(dataProps));
            return null;
        }
        List<OWLObjectPropertyExpression> props = ctx.propertyExpr().stream()
            .map(this::buildObjectProp).collect(Collectors.toList());
        axioms.add(df.getOWLDisjointObjectPropertiesAxiom(props));
        return null;
    }

    /**
     * An object property expression for a characteristic that is object-only.
     *
     * <p>OWL permits an expression here, and the writer emits one — {@code Trans(r⁻)} comes
     * out of any ontology with an inverse in that position — so the reader has to accept it.
     */
    private OWLObjectPropertyExpression objectOnlyProp(
            String keyword, DLESyntaxParser.PropertyExprContext ctx) {
        objectOnlyProp(keyword, PropertyExprs.coreName(ctx));
        return buildObjectProp(ctx);
    }

    /**
     * Refuses an inverse on a data property.
     *
     * <p>OWL has no inverse for a data property: a value is not a thing that can point back.
     * The characteristics with both forms therefore have to check, because the expression is
     * legal syntax and only the property's kind makes it wrong.
     */
    private void refuseInverseOnDataProperty(
            String keyword, DLESyntaxParser.PropertyExprContext ctx) {
        if (PropertyExprs.isInverse(ctx)) {
            throw new DLESemanticException(
                keyword + " is applied to the inverse of " + PropertyExprs.coreNameText(ctx)
                    + ", which is used as a data property in this document. A data property"
                    + " has no inverse in OWL — a value cannot point back at what holds it.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
    }

    /**
     * The object property a characteristic is about, refusing a data property.
     *
     * <p>Transitivity, symmetry, asymmetry, reflexivity and irreflexivity are relations
     * between two individuals; OWL defines them for object properties only, and there is no
     * data-property counterpart to fall back on. Every one of these built an object
     * property regardless, so a document applying {@code Trans} to a name the rest of it
     * used with a datatype produced an ontology declaring that name as both kinds at once —
     * punning OWL 2 DL forbids, which no reasoner will load and which DLe cannot write
     * back. Functionality is the exception, and has both forms.
     */
    private OWLObjectProperty objectOnlyProp(String keyword, DLESyntaxParser.NameContext ctx) {
        String name = ctx.getText();
        if (dataPropertyNames.contains(name)) {
            throw new DLESemanticException(
                keyword + " applies to object properties only, and " + name + " is used as a"
                    + " data property in this document. There is no " + keyword + " for data"
                    + " properties; Func is the only characteristic that has both forms.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        return objectProp(ctx);
    }

    private OWLObjectProperty objectProp(DLESyntaxParser.NameContext ctx) {
        return df.getOWLObjectProperty(expandName(ctx));
    }

    @Override
    public OWLObject visitSubPropertyChainAxiom(DLESyntaxParser.SubPropertyChainAxiomContext ctx) {
        OWLObjectPropertyExpression superProp = buildObjectProp(ctx.propertyExpr());
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
        OWLObject inner = visit(ctx.primary());
        // `¬` complements a data range as readily as a class, and the writer emits it for
        // one — `DataComplementOf(xsd:string)` goes out as `¬xsd:string`. Building the
        // object form regardless then failed asClass, so the document the writer had just
        // produced came back as "expected a class expression here".
        if (inner instanceof OWLDataRange) {
            return df.getOWLDataComplementOf((OWLDataRange) inner);
        }
        return df.getOWLObjectComplementOf(asClass(inner));
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
        return buildPredicateClass("\u2203", ctx.propertyExpr(),
            Parens.predicateName(ctx.predicateRef()));
    }

    @Override
    public OWLObject visitMultiRoleAllValuesFrom(DLESyntaxParser.MultiRoleAllValuesFromContext ctx) {
        return buildPredicateClass("\u2200", ctx.propertyExpr(),
            Parens.predicateName(ctx.predicateRef()));
    }

    @Override
    public OWLObject visitSomeValuesFrom(DLESyntaxParser.SomeValuesFromContext ctx) {
        // ∃r.Self → ObjectHasSelf(r). Parentheses around the filler are stripped
        // first, so ∃r.(Self) and ∃r.((Self)) are the same expression.
        DLESyntaxParser.AtomContext atom = Parens.atomOf(ctx.primary());
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
        return cardinalityRestriction(ctx.cardSymbol(), ctx.NUMBER().getText(),
            ctx.propertyExpr(), visit(ctx.primary()));
    }

    /**
     * One cardinality restriction, from the four parts every spelling of one supplies.
     *
     * <p>Shared because the same restriction appears in two grammar rules — inside a class
     * expression, and as a statement of its own abbreviating `⊤ ⊑ …` — and the statement
     * form used to build something else entirely.
     */
    private OWLClassExpression cardinalityRestriction(
            DLESyntaxParser.CardSymbolContext symbol, String number,
            DLESyntaxParser.PropertyExprContext propertyExpr, OWLObject filler) {
        int n = (int) Double.parseDouble(number);
        boolean isMin = symbol.MIN() != null;
        boolean isMax = symbol.MAX() != null;

        if (dataPropertyNames.contains(propName(propertyExpr))) {
            OWLDataPropertyExpression prop = df.getOWLDataProperty(
                expandName(propCtxName(propertyExpr)));
            OWLDataRange range = asDataRange(filler);
            if (isMin)       return df.getOWLDataMinCardinality(n, prop, range);
            else if (isMax)  return df.getOWLDataMaxCardinality(n, prop, range);
            else             return df.getOWLDataExactCardinality(n, prop, range);
        } else {
            OWLObjectPropertyExpression prop = buildObjectProp(propertyExpr);
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
        // `locatedIn⁻` in class position. The inner propertyExpr may itself carry
        // parentheses and inverse markers, so parity is counted over the whole
        // thing: `(locatedIn)⁻` is an inverse, `locatedIn⁻⁻` is not.
        OWLObjectProperty prop =
            df.getOWLObjectProperty(expandName(PropertyExprs.coreName(ctx.propertyExpr())));
        return PropertyExprs.isInverse(ctx.propertyExpr())
            ? prop
            : df.getOWLObjectInverseOf(prop);
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
        boolean hasIndividuals = elems.stream()
            .anyMatch(e -> e instanceof DLESyntaxParser.IndividualElemContext);
        // An enumeration is a set of individuals or a set of values, and OWL has a
        // different constructor for each — ObjectOneOf and DataOneOf. A mixture is neither,
        // and asking for one used to hand the user a raw ClassCastException naming two
        // parser context classes, with no line, no column and nothing to act on: every
        // element was cast to a literal as soon as any one of them was.
        if (hasLiterals && hasIndividuals) {
            String individual = elems.stream()
                .filter(DLESyntaxParser.IndividualElemContext.class::isInstance)
                .map(org.antlr.v4.runtime.RuleContext::getText).findFirst().orElse("a name");
            String literal = elems.stream()
                .filter(DLESyntaxParser.LiteralElemContext.class::isInstance)
                .map(org.antlr.v4.runtime.RuleContext::getText).findFirst().orElse("a value");
            throw new DLESemanticException(
                "this enumeration mixes the individual " + individual + " with the value "
                    + literal + ". An enumeration is either a set of individuals or a set"
                    + " of values, so write two — one for each — rather than one of both.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        if (hasLiterals) {
            List<OWLLiteral> lits = elems.stream()
                .map(this::buildLiteral)
                .collect(Collectors.toList());
            return df.getOWLDataOneOf(lits);
        } else {
            List<OWLIndividual> inds = elems.stream()
                .map(e -> individual(((DLESyntaxParser.IndividualElemContext) e).name()))
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

    //
    // For each statement, any hidden `#` comment tokens immediately preceding it are
    // collected and attached as `dle:comment` annotation assertions on the first named
    // entity the statement references.
    /**
     * Captures the comment block after the last statement.
     *
     * <p>Every other comment is found by looking left from the statement below it, which
     * leaves a trailing block with nothing to look from: it was read, discarded, and gone.
     * That mattered more than it sounds, because the writer puts a comment at the head of
     * its entity's block and an entity whose axioms were all claimed by earlier blocks has
     * nothing else in it — so a comment could be written last and then be destroyed by the
     * very next read.
     *
     * <p>It has no entity to belong to, so it is kept on the ontology and written back at
     * the end of the document, which is where it was.
     */
    @Override
    public OWLObject visitOntology(DLESyntaxParser.OntologyContext ctx) {
        OWLObject result = visitChildren(ctx);
        if (tokenStream == null) return result;
        captureDeclarationComments(ctx);
        if (ctx.statement().isEmpty()) return result;
        DLESyntaxParser.StatementContext last = ctx.statement(ctx.statement().size() - 1);
        if (last.stop == null) return result;
        List<Token> hidden = tokenStream.getHiddenTokensToRight(
            last.stop.getTokenIndex(), Token.HIDDEN_CHANNEL);
        if (hidden == null) return result;
        StringBuilder sb = new StringBuilder();
        for (Token tok : hidden) {
            // An inline comment on the last statement's own line is already captured as
            // one, by visitStatement, and must not be taken twice.
            if (tok.getLine() == last.stop.getLine()) continue;
            String text = tok.getText();
            if (text.startsWith("#")) text = text.substring(1);
            if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        if (sb.length() > 0) documentComments.add(sb.toString());
        return result;
    }

    /**
     * Comments above the header directives, which no statement visit can reach.
     *
     * <p>{@code @prefix} and its three companions are not statements in the grammar — the
     * ontology rule takes them before {@code statement*} — so {@code visitStatement} never
     * sees them, and {@code getHiddenTokensToLeft} on the first real statement stops at the
     * last directive. A comment heading the file, which is the ordinary way to head one, was
     * therefore never captured by anything and vanished without trace.
     *
     * <p>They belong to the document, so they are kept the way a trailing comment is. The
     * generated header is skipped: it documents the syntax rather than the ontology, and
     * keeping it would grow the file by a copy of itself on every pass.
     */
    private void captureDeclarationComments(DLESyntaxParser.OntologyContext ctx) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);
            if (!(child instanceof DLESyntaxParser.PrefixDeclContext
                    || child instanceof DLESyntaxParser.OntologyDeclContext
                    || child instanceof DLESyntaxParser.VersionDeclContext
                    || child instanceof DLESyntaxParser.ImportDeclContext)) {
                continue;
            }
            ParserRuleContext decl = (ParserRuleContext) child;
            List<Token> hidden = tokenStream.getHiddenTokensToLeft(
                decl.start.getTokenIndex(), Token.HIDDEN_CHANNEL);
            if (hidden == null || hidden.isEmpty()) continue;
            int from = 0;
            if (hidden.get(0).getLine() == 1 && isGeneratedHeader(hidden.get(0))) {
                // The header is one contiguous run from line 1; a blank line ends it, and
                // shows up as a gap in the line numbers because whitespace is skipped.
                int previous = 0;
                while (from < hidden.size()) {
                    int line = hidden.get(from).getLine();
                    if (from == 0 || line == previous + 1) {
                        previous = line;
                        from++;
                    } else {
                        break;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Token tok : hidden.subList(from, hidden.size())) {
                String text = tok.getText();
                if (text.startsWith("#")) text = text.substring(1);
                if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
            if (sb.length() > 0) documentComments.add(sb.toString());
        }
    }

    /** The first line DLe's own generated header always carries. */
    private static final String GENERATED_HEADER_FIRST_LINE =
        "# DLe \u2014 Description Logic (Extended)";

    /**
     * Whether a comment block at the top of the file is the header DLe writes.
     *
     * <p>The header has to be dropped on the way in — it documents the syntax rather than the
     * ontology, and keeping it would grow the document by a copy of itself on every pass. But
     * the test for it was positional, not textual, so any comment block starting at line 1
     * was discarded, including one the author wrote.
     */
    private static boolean isGeneratedHeader(Token first) {
        return first.getText().trim().equals(GENERATED_HEADER_FIRST_LINE);
    }

    @Override
    public OWLObject visitStatement(DLESyntaxParser.StatementContext ctx) {
        currentLine = ctx.start.getLine();
        int axiomsBefore = axioms.size();
        OWLObject result = visitChildren(ctx);
        if (tokenStream == null) return result;

        // Resolve the subject IRI once; used by both block and inline comment capture.
        IRI subjectIRI = null;

        // ── Block comments preceding the statement ────────────────────────────
        List<Token> hidden = tokenStream.getHiddenTokensToLeft(
            ctx.start.getTokenIndex(), Token.HIDDEN_CHANNEL);
        if (hidden != null && !hidden.isEmpty()) {
            // Strip the file header: the initial contiguous block of comment lines
            // beginning at line 1. Blank lines appear as gaps in line numbers because
            // WS is skipped and not represented in the token stream.
            //
            // Only when it really is the generated header. Stripping any block that begins at
            // line 1 took the author's own leading comments with it — a comment above
            // `@prefix` has no statement below it to belong to, so it was simply gone, and
            // the test named for comment preservation exempted exactly those lines from its
            // own comparison.
            int realStart = 0;
            if (hidden.get(0).getLine() == 1 && isGeneratedHeader(hidden.get(0))) {
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
            // Drop anything sitting on the previous statement's own line. A comment there
            // is that statement's inline comment, and the branch below has already taken
            // it — so claiming it here as well wrote it out twice, once in each entity's
            // block. `A ⊑ B  # NOTE` followed by `C ⊑ D` produced a dle:inlineComment on A
            // and a dle:comment on C from the same six characters.
            int previousLine = previousStatementLine(ctx);
            int afterPrevious = 0;
            while (afterPrevious < hidden.size()
                    && hidden.get(afterPrevious).getLine() == previousLine) {
                afterPrevious++;
            }
            hidden = hidden.subList(afterPrevious, hidden.size());
            if (!hidden.isEmpty()) {
                String block = commentText(hidden);
                // The axiom this statement produced, if it produced one that gets written.
                // A comment belongs to the statement it sits above, and an axiom is the only
                // thing that carries a statement's identity through the writer — an entity
                // does not, because which entity's block an axiom lands in is decided by the
                // renderer, not by the comment. Attaching to an entity is what let a comment
                // be written at the far end of the document from its own statement, and then
                // stop being that entity's comment at all on the next read.
                subjectIRI = findFirstNameIRI(ctx);
                int statementAxiom = firstWritableAxiom(axiomsBefore, subjectIRI);
                if (statementAxiom >= 0 && !block.isEmpty()) {
                    axioms.set(statementAxiom, annotated(axioms.get(statementAxiom),
                        DLE_COMMENT_IRI, block));
                    return result;
                }
                if (subjectIRI == null) {
                    // No name in this statement to hang it on — `@prefix`, `@ontology`,
                    // `@version` and `@import` have none — so the comment was dropped.
                    // A comment above the first `@prefix` is the ordinary way to head a
                    // document, and every line of it was lost in silence.
                    //
                    // It belongs to the document, exactly as a trailing comment does, and is
                    // kept the same way. Position is not preserved: it comes back with the
                    // other document comments, which are unordered.
                    StringBuilder orphan = new StringBuilder();
                    for (Token tok : hidden) {
                        String text = tok.getText();
                        if (text.startsWith("#")) text = text.substring(1);
                        if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
                        if (orphan.length() > 0) orphan.append('\n');
                        orphan.append(text);
                    }
                    if (orphan.length() > 0) documentComments.add(orphan.toString());
                }
                if (subjectIRI != null) {
                    // Store the block as a single multi-line literal to preserve order.
                    StringBuilder sb = new StringBuilder();
                    for (Token tok : hidden) {
                        String text = tok.getText();
                        if (text.startsWith("#")) text = text.substring(1);
                        if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(text);
                    }
                    if (sb.length() > 0) {
                        axioms.add(df.getOWLAnnotationAssertionAxiom(
                            df.getOWLAnnotationProperty(DLE_COMMENT_IRI),
                            subjectIRI, df.getOWLLiteral(sb.toString())));
                    }
                }
            }
        }

        // ── Trailing inline comment on the same line as the statement ─────────
        if (ctx.stop != null) {
            List<Token> right = tokenStream.getHiddenTokensToRight(
                ctx.stop.getTokenIndex(), Token.HIDDEN_CHANNEL);
            if (right != null && !right.isEmpty()) {
                Token first = right.get(0);
                if (first.getLine() == ctx.stop.getLine()) {
                    String text = commentText(List.of(first));
                    if (subjectIRI == null) subjectIRI = findFirstNameIRI(ctx);
                    int statementAxiom = firstWritableAxiom(axiomsBefore, subjectIRI);
                    if (statementAxiom >= 0 && !text.isEmpty()) {
                        // On the axiom, like the block comment above, which is what keeps an
                        // inline comment inline: as an assertion on an entity it was written
                        // as a block comment above that entity and came back as an ordinary
                        // one, so the distinction survived exactly one pass.
                        axioms.set(statementAxiom, annotated(axioms.get(statementAxiom),
                            DLE_INLINE_COMMENT_IRI, text));
                    } else {
                        if (subjectIRI == null) subjectIRI = findFirstNameIRI(ctx);
                        if (subjectIRI != null && !text.isEmpty()) {
                            axioms.add(df.getOWLAnnotationAssertionAxiom(
                                df.getOWLAnnotationProperty(DLE_INLINE_COMMENT_IRI),
                                subjectIRI, df.getOWLLiteral(text)));
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * The index in {@link #axioms} of the first axiom this statement produced that the
     * writer will render as a statement, or -1 if it produced none.
     *
     * <p>A declaration is skipped: DLe has no line for one, so a comment attached to it
     * would have nowhere to be written. Statements that produce only declarations — a kind
     * statement, for instance — fall back to the entity form, which is where they were.
     *
     * <p>The axiom also has to be <em>about</em> the name the statement is about. A
     * statement is not one axiom: {@code MigrationInFuture ≡ ∃migrationDate.afterNow}
     * builds a synthetic {@code dle:} class for the predicate restriction first, and the
     * first axiom it produced was that class's label — which the writer suppresses,
     * because internal classes are rendered inline within the expressions that use them.
     * A comment attached there was written by nobody and lost without trace. Requiring the
     * subject rules the synthetic axioms out, since none of them mention it.
     *
     * @param subject the name the statement is about, or null if it has none
     */
    private int firstWritableAxiom(int from, @Nullable IRI subject) {
        for (int i = from; i < axioms.size(); i++) {
            OWLAxiom axiom = axioms.get(i);
            if (axiom instanceof OWLDeclarationAxiom) continue;
            if (subject == null) return i;
            if (isAbout(axiom, subject)) return i;
        }
        // Nothing this statement produced is about its own subject — leave it to the
        // entity form rather than guess, which is where a comment lived before axiom
        // annotations and is still written.
        return -1;
    }

    /** Whether {@code axiom} names {@code subject} as what it is about. */
    private static boolean isAbout(OWLAxiom axiom, IRI subject) {
        if (axiom instanceof OWLAnnotationAssertionAxiom) {
            // An annotation assertion's subject is an IRI, not an entity, so it is not in
            // the axiom's signature — which holds only the annotation property.
            return subject.equals(((OWLAnnotationAssertionAxiom) axiom).getSubject());
        }
        return axiom.signature().anyMatch(e -> subject.equals(e.getIRI()));
    }

    /** The same axiom carrying one more annotation. */
    private OWLAxiom annotated(OWLAxiom axiom, IRI property, String text) {
        Set<OWLAnnotation> annotations = new LinkedHashSet<>(axiom.getAnnotations());
        annotations.add(df.getOWLAnnotation(
            df.getOWLAnnotationProperty(property), df.getOWLLiteral(text)));
        return axiom.getAnnotatedAxiom(annotations);
    }

    /** Comment tokens joined into one block, each stripped of its `#` and one space. */
    private static String commentText(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token tok : tokens) {
            String text = tok.getText();
            if (text.startsWith("#")) text = text.substring(1);
            if (!text.isEmpty() && text.charAt(0) == ' ') text = text.substring(1);
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.toString();
    }

    /**
     * The line the previous statement ended on, or -1 when this is the first.
     *
     * <p>Only needed to tell a comment that follows a statement from one that precedes the
     * next: both are hidden tokens to the left of this statement's first token, and only
     * the line number distinguishes them.
     */
    private int previousStatementLine(DLESyntaxParser.StatementContext ctx) {
        if (tokenStream == null) return -1;
        for (int i = ctx.start.getTokenIndex() - 1; i >= 0; i--) {
            Token token = tokenStream.get(i);
            if (token.getChannel() != Token.HIDDEN_CHANNEL) {
                return token.getLine();
            }
        }
        return -1;
    }

    /** Returns the IRI of the first name token in the statement, or null. */
    /**
     * The first name written anywhere inside a subtree.
     *
     * <p>The same scan as {@link #findFirstNameIRI}, bounded to one expression rather than a
     * whole statement, so a class expression can be asked what it names first without the
     * individual to its left getting in the way.
     */
    @Nullable
    private IRI firstNameIn(ParserRuleContext ctx) {
        if (ctx.stop == null) return null;
        for (int i = ctx.start.getTokenIndex(); i <= ctx.stop.getTokenIndex(); i++) {
            Token tok = tokenStream.get(i);
            int type = tok.getType();
            if (type == DLESyntaxLexer.NAME || type == DLESyntaxLexer.PREFIXED_NAME
                    || type == DLESyntaxLexer.DEFAULT_NAME) {
                return expandNameText(tok.getText());
            }
        }
        return null;
    }

    private IRI findFirstNameIRI(DLESyntaxParser.StatementContext ctx) {
        // An assertion is the exception, because its first name is not what the statement
        // is about. `(bob,ann):knows` opens with an individual, so a comment above it was
        // attached to `bob` — and the writer puts a comment at the head of the block of the
        // entity it belongs to, which for that line is `knows`. The comment therefore
        // changed subject on every round trip. The class and property of an assertion are
        // where its block is, so they are what a comment above it is about.
        IRI subject = assertionSubject(ctx);
        if (subject != null) return subject;

        int start = ctx.start.getTokenIndex();
        int stop  = ctx.stop != null ? ctx.stop.getTokenIndex() : start;
        for (int i = start; i <= stop; i++) {
            Token tok = tokenStream.get(i);
            int type = tok.getType();
            if (type == DLESyntaxLexer.NAME || type == DLESyntaxLexer.PREFIXED_NAME
                    || type == DLESyntaxLexer.DEFAULT_NAME) {
                return expandNameText(tok.getText());
            }
        }
        return null;
    }

    /**
     * The entity an assertion statement is about, or null if this is not an assertion.
     *
     * <p>The property for the tuple forms, the class for a class assertion — matching the
     * entity block the writer places the statement in.
     */
    @Nullable
    private IRI assertionSubject(DLESyntaxParser.StatementContext ctx) {
        if (ctx.axiom() == null) return null;
        DLESyntaxParser.AxiomContext axiom = ctx.axiom();
        if (axiom instanceof DLESyntaxParser.ObjectAssertionAxiomContext) {
            return expandName(((DLESyntaxParser.ObjectAssertionAxiomContext) axiom).name(2));
        }
        if (axiom instanceof DLESyntaxParser.NegativeObjectAssertionAxiomContext) {
            return expandName(
                ((DLESyntaxParser.NegativeObjectAssertionAxiomContext) axiom).name(2));
        }
        if (axiom instanceof DLESyntaxParser.DataAssertionAxiomContext) {
            return expandName(((DLESyntaxParser.DataAssertionAxiomContext) axiom).name(1));
        }
        if (axiom instanceof DLESyntaxParser.NegativeDataAssertionAxiomContext) {
            return expandName(
                ((DLESyntaxParser.NegativeDataAssertionAxiomContext) axiom).name(1));
        }
        if (axiom instanceof DLESyntaxParser.ClassAssertionAxiomContext) {
            DLESyntaxParser.ClassExprContext cls =
                ((DLESyntaxParser.ClassAssertionAxiomContext) axiom).classExpr();
            String lone = loneName(cls);
            if (lone != null) return expandNameText(lone);
            // A complex class expression used to fall through to the individual, so the two
            // spellings of one axiom shape disagreed: `bob : Person` put the comment on
            // Person and `bob : Person ⊓ ¬Keeper` put it on bob. The statement is written in
            // the class's block either way, so a comment in the individual's block ended up
            // beneath its own statement, and on the *next* read — with nothing below it — it
            // stopped being an entity's comment at all and became a document comment.
            //
            // The first named class in the expression is where the block is, so it is what a
            // comment above the line is about.
            return firstNameIn(cls);
        }
        // The compact spelling of the same axiom. Without this case the comment above
        // `rex:Cat` was discarded in silence, while the one above `rex : Cat` was kept.
        if (axiom instanceof DLESyntaxParser.PrefixedClassAssertionAxiomContext) {
            String text = ((DLESyntaxParser.PrefixedClassAssertionAxiomContext) axiom)
                .PREFIXED_NAME().getText();
            return expandNameText(text.substring(text.indexOf(':') + 1));
        }
        return null;
    }

    /** Resolves a raw name token text (bare or prefixed) to a full IRI using the prefix map. */
    private IRI expandNameText(String text) {
        int colon = text.indexOf(':');
        if (colon == 0) {
            // ":1" — the default prefix stated explicitly. Without this the name
            // falls through to the bare branch below and expands to ns + ":1".
            String base = prefixes.get(":");
            return base == null ? null : IRI.create(base + text.substring(1));
        }
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

    // ── Assertions about individuals ────────────────────────────────────────

    @Override
    public OWLObject visitObjectAssertionAxiom(
            DLESyntaxParser.ObjectAssertionAxiomContext ctx) {
        requireBareSeparator(ctx.PNAME_NS(), ctx);
        axioms.add(df.getOWLObjectPropertyAssertionAxiom(
            df.getOWLObjectProperty(expandName(ctx.name(2))),
            individual(ctx.name(0)),
            individual(ctx.name(1))));
        return null;
    }

    @Override
    public OWLObject visitNegativeObjectAssertionAxiom(
            DLESyntaxParser.NegativeObjectAssertionAxiomContext ctx) {
        requireBareSeparator(ctx.PNAME_NS(), ctx);
        axioms.add(df.getOWLNegativeObjectPropertyAssertionAxiom(
            df.getOWLObjectProperty(expandName(ctx.name(2))),
            individual(ctx.name(0)),
            individual(ctx.name(1))));
        return null;
    }

    @Override
    public OWLObject visitDataAssertionAxiom(DLESyntaxParser.DataAssertionAxiomContext ctx) {
        requireBareSeparator(ctx.PNAME_NS(), ctx);
        axioms.add(df.getOWLDataPropertyAssertionAxiom(
            df.getOWLDataProperty(expandName(ctx.name(1))),
            individual(ctx.name(0)),
            literalOf(ctx.literal())));
        return null;
    }

    @Override
    public OWLObject visitNegativeDataAssertionAxiom(
            DLESyntaxParser.NegativeDataAssertionAxiomContext ctx) {
        requireBareSeparator(ctx.PNAME_NS(), ctx);
        axioms.add(df.getOWLNegativeDataPropertyAssertionAxiom(
            df.getOWLDataProperty(expandName(ctx.name(1))),
            individual(ctx.name(0)),
            literalOf(ctx.literal())));
        return null;
    }

    @Override
    public OWLObject visitClassAssertionAxiom(DLESyntaxParser.ClassAssertionAxiomContext ctx) {
        // Two readings are possible when the pieces were written without a space and the
        // first names a prefix — `ex:a:C` is either individual ex:a of class C, or
        // individual ex of class a:C. The prefixes the document declares decide it, and
        // they are all known by now: the grammar puts every prefixDecl before every
        // statement, so this is a guarantee rather than a scan-order accident.
        requireBareSeparator(ctx.PNAME_NS(), ctx);
        String first = ctx.name().getText();
        // The class side's own name, not its raw text: the reading-B gate below uses
        // loneName, which sees through parentheses, so building the IRI from getText()
        // disagreed with the gate — `ex:a:(C)` minted the IRI `…#(C)`.
        String loneClassName = loneName(ctx.classExpr());
        String second = loneClassName != null ? loneClassName : ctx.classExpr().getText();
        boolean spaced = !adjacent(ctx.name().getStop(), ctx.PNAME_NS().getSymbol())
            || !adjacent(ctx.PNAME_NS().getSymbol(), ctx.classExpr().getStart());

        // Reading B only exists when the class side is a single bare name: there is nothing
        // to move the first name's local part onto otherwise.
        int colon = first.indexOf(':');
        boolean readingBPossible = !spaced && colon > 0 && second.indexOf(':') < 0
            && loneClassName != null;
        if (readingBPossible) {
            boolean aValid = prefixes.containsKey(first.substring(0, colon + 1));
            String bPrefix = first.substring(colon + 1) + ":";
            boolean bValid = prefixes.containsKey(bPrefix);
            if (aValid && bValid) {
                throw new DLESemanticException(
                    "'" + first + ":" + second + "' is ambiguous: both '"
                        + first.substring(0, colon + 1) + "' and '" + bPrefix
                        + "' are declared prefixes, so this is either the individual "
                        + first + " of class " + second + ", or the individual "
                        + first.substring(0, colon) + " of class "
                        + first.substring(colon + 1) + ":" + second
                        + ". Put a space around the colon to say which.",
                    ctx.start.getLine(), ctx.start.getCharPositionInLine());
            }
            if (bValid) {
                // The individual is the part before the colon, in the default namespace.
                assertNamedClass(first.substring(0, colon),
                    first.substring(colon + 1) + ":" + second, ctx);
                return null;
            }
        }
        assertClass(first, ctx.classExpr(), ctx);
        return null;
    }


    @Override
    public OWLObject visitPrefixedClassAssertionAxiom(
            DLESyntaxParser.PrefixedClassAssertionAxiomContext ctx) {
        // `a:C` arrives as one token, indistinguishable from a prefixed name. A lone
        // prefixed name has never been a statement, so if the prefix is declared this is
        // simply not a statement; if it is not declared, the only reading left is the
        // assertion, and that is the textbook spelling.
        String text = ctx.PREFIXED_NAME().getText();
        int colon = text.indexOf(':');
        String prefix = text.substring(0, colon + 1);
        if (prefixes.containsKey(prefix)) {
            throw new DLESemanticException(
                "'" + text + "' on its own is not a statement. '" + prefix + "' is a"
                    + " declared prefix, so this reads as a name rather than as the"
                    + " assertion " + text.substring(colon + 1) + "("
                    + text.substring(0, colon) + "); put a space around the colon if the"
                    + " assertion is what was meant.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        assertNamedClass(text.substring(0, colon), text.substring(colon + 1), ctx);
        return null;
    }

    @Override
    public OWLObject visitSameIndividualAxiom(DLESyntaxParser.SameIndividualAxiomContext ctx) {
        axioms.add(df.getOWLSameIndividualAxiom(individuals(ctx.name(), true, ctx)));
        return null;
    }

    @Override
    public OWLObject visitDifferentIndividualsAxiom(
            DLESyntaxParser.DifferentIndividualsAxiomContext ctx) {
        axioms.add(df.getOWLDifferentIndividualsAxiom(
            individuals(ctx.name(), false, ctx)));
        return null;
    }

    /** Resolves the names of an identity or distinctness statement to named individuals. */
    private Set<OWLIndividual> individuals(List<DLESyntaxParser.NameContext> names,
                                           boolean same,
                                           org.antlr.v4.runtime.ParserRuleContext ctx) {
        // A LinkedHashSet, so a repeated name collapses without disturbing the order of the
        // rest. Collapsing to one member has to be caught: OWL API requires at least two,
        // and the two statements fail differently, so they are described differently.
        // `a = a` states nothing; `a ≠ a` states something that cannot hold.
        Set<OWLIndividual> result = new LinkedHashSet<>();
        for (DLESyntaxParser.NameContext name : names) {
            result.add(individual(name));
        }
        // `a ≠ b ≠ a` contains the unsatisfiable pair `a ≠ a`, and collapsing it silently
        // dropped exactly that. `=` is idempotent, so a repeat there is harmless.
        if (!same && result.size() != names.size()) {
            throw new DLESemanticException(
                "an individual is named twice in this distinctness statement, which says it"
                    + " is distinct from itself. Name each individual once.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        if (result.size() < 2) {
            throw new DLESemanticException(same
                ? "this says an individual is the same as itself, which states nothing."
                    + " Name two individuals, or remove the statement."
                : "this says an individual is distinct from itself, which nothing can"
                    + " satisfy. Name two individuals, or remove the statement.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        return result;
    }

    /** Builds the assertion with a class expression on the right, as the textbook allows. */
    private void assertClass(String individual, DLESyntaxParser.ClassExprContext cls,
                             org.antlr.v4.runtime.ParserRuleContext ctx) {
        axioms.add(df.getOWLClassAssertionAxiom(
            asClass(visit(cls)), individualOf(individual, cls.getText(), ctx)));
    }

    /** Builds the assertion where the class side has been re-split into a bare name. */
    private void assertNamedClass(String individual, String className,
                                  org.antlr.v4.runtime.ParserRuleContext ctx) {
        IRI classIri = expandNameText(className);
        // Through namesADatatype, which knows the datatypes this document defines as well as
        // the built-in ones. Asking isDatatypeIri alone meant `b:Code` was accepted as
        // ClassAssertion(:Code :b) while the spaced `b : Code` was refused — the same
        // assertion, two answers, and the accepted one puts a datatype in a class position.
        if (classIri != null && namesADatatype(className, classIri)) {
            // The spaced form refuses this through asClass; these paths built the class
            // directly and so accepted what the spaced spelling rejects — and then wrote
            // the spaced spelling back out, producing a document this reader will not read.
            throw new DLESemanticException(
                "expected a class expression here, but found the datatype " + className
                    + ". An individual cannot be asserted to be a datatype.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        if (classIri == null) {
            throw new DLESemanticException(
                "unknown prefix in the assertion '" + individual + " : " + className
                    + "'. Declare it with @prefix, or check for a typo.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        axioms.add(df.getOWLClassAssertionAxiom(
            df.getOWLClass(classIri), individualOf(individual, className, ctx)));
    }

    /** The blank-node prefix, which every RDF syntax reserves and so does this one. */
    static final String BLANK_NODE_PREFIX = "_:";

    static boolean isBlankNodeName(String text) {
        return text.startsWith(BLANK_NODE_PREFIX) && text.length() > BLANK_NODE_PREFIX.length();
    }

    /**
     * An individual, named or anonymous.
     *
     * <p>`_:` lexes as an ordinary prefix — `_` is a NameStart — so the reader only had to
     * stop resolving it and build an anonymous individual instead. Without that, the writer
     * emitted `_:genid2147483648 : A` and the reader answered "unknown prefix '_:'", so any
     * ontology with a blank node produced a document it could not read back.
     *
     * <p>The label is kept as written, which makes a DLe round trip stable. It is not
     * stable coming from RDF, where the label is generated on load — but that is true of
     * every syntax, and is why the label carries no meaning.
     */
    private OWLIndividual individual(DLESyntaxParser.NameContext ctx) {
        String text = ctx.getText();
        if (isBlankNodeName(text)) {
            return df.getOWLAnonymousIndividual(text);
        }
        return df.getOWLNamedIndividual(expandName(ctx));
    }

    private OWLIndividual individualOf(String individual, String className,
                                            org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (isBlankNodeName(individual)) {
            return df.getOWLAnonymousIndividual(individual);
        }
        IRI iri = expandNameText(individual);
        if (iri == null) {
            throw new DLESemanticException(
                "unknown prefix in the assertion '" + individual + " : " + className
                    + "'. Declare it with @prefix, or check for a typo.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        return df.getOWLNamedIndividual(iri);
    }

    /**
     * Requires the assertion separator to be a bare colon.
     *
     * <p>The grammar spells it {@code PNAME_NS}, which is {@code NameChar* ':'} — so it also
     * matches {@code foo:}. Nothing checked the label was empty, and all five forms
     * therefore accepted and silently discarded one: {@code a foo: C} built
     * {@code ClassAssertion(:C :a)}, and {@code (a,b) zz: r} the object assertion.
     */
    private void requireBareSeparator(org.antlr.v4.runtime.tree.TerminalNode separator,
                                      org.antlr.v4.runtime.ParserRuleContext ctx) {
        String text = separator.getText();
        if (!":".equals(text)) {
            throw new DLESemanticException(
                "'" + text + "' is not the separator of an assertion; write a bare ':'."
                    + " The colon here separates the parts of the statement and is not part"
                    + " of a name.",
                separator.getSymbol().getLine(),
                separator.getSymbol().getCharPositionInLine());
        }
    }

    /** Whether the second token begins immediately after the first ends. */
    private static boolean adjacent(Token left, Token right) {
        return left != null && right != null
            && left.getStopIndex() + 1 == right.getStartIndex();
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
        if (ctx.DEFAULT_NAME() != null) {
            // ":1" — the default prefix, stated because a digit-initial local part
            // has no bare form.
            return IRI.create(prefixes.getOrDefault(":", "") + text.substring(1));
        }
        if (ctx.PREFIXED_NAME() != null) {
            int colon = text.indexOf(':');
            String prefixLabel = text.substring(0, colon + 1); // "xsd:"
            String local       = text.substring(colon + 1);
            String base = prefixes.get(prefixLabel);
            if (base == null) {
                // An undeclared prefix is a mistake in the document, not a bug here.
                throw DLESemanticException.at(ctx,
                    "unknown prefix '" + prefixLabel + "'. Declare it with "
                        + "@prefix " + prefixLabel + " <namespace>, or check for a typo.");
            }
            return IRI.create(base + local);
        }
        // Bare name — use default prefix ":"
        String base = prefixes.getOrDefault(":", "");
        return IRI.create(base + text);
    }

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
     * Whether {@code fillerName} is a predicate rather than a class in this restriction.
     *
     * <p>Declared predicates only. This used to fall back to the case convention — a bare
     * lower-case filler not already known to be a property was taken for a predicate — and
     * that destroyed any class whose name broke the convention: {@code A ⊑ ∃r.lowerC} built
     * the skolem class {@code dle:E_lowerC_…} and dropped both {@code lowerC} and {@code r},
     * with the document loading cleanly (#37).
     *
     * <p>The guess existed twice, here and in the scanner's {@code checkUnaryPredicate},
     * which is the duplication {@code docs/inference-design.md} §3.3 is about: two copies of
     * one rule, and removing only the scanner's left this one still destroying the class.
     *
     * <p>A predicate is always declared — {@code greaterThan(x,y) ≝ …}, or a multi-role
     * reference {@code ∃a,b.p} whose comma makes it unambiguous — and the scan that records
     * declarations completes before this runs, so a declaration below the reference is
     * still found. There is nothing left to guess.
     */
    private boolean isPredicateName(String propName, String fillerName) {
        return predicateNames.contains(fillerName);
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
            // Canonical form, not the raw source text: ∃(a⁻),b.p and ∃a⁻,b.p are
            // the same expression and must hash to the same class IRI.
            sb.append(PropertyExprs.render(roles.get(i)));
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

    /**
     * Returns an OWLObjectProperty, OWLDataProperty, or OWLClass depending on
     * how the name was classified by the scanner.
     */
    private OWLObject nameToPropertyOrClass(DLESyntaxParser.NameContext ctx) {
        String text = ctx.getText();
        IRI iri = expandName(ctx);
        // Data is checked first: classifyProp/propagatePropertyTypes enforce mutual exclusivity,
        // but data classification is definitive when a name ends up in both sets.
        if (dataPropertyNames.contains(text))   return df.getOWLDataProperty(iri);
        if (objectPropertyNames.contains(text)) return df.getOWLObjectProperty(iri);
        // Datatypes are data ranges. Decided on the resolved IRI, which is the same test
        // the classifier and the writer use — it used to be a text match on the `xsd:`
        // prefix, so the XSD namespace under another prefix was missed and owl:real and
        // owl:rational were not datatypes here at all.
        if (namesADatatype(text, iri)) {
            return df.getOWLDatatype(iri);
        }
        // OWL's four built-in properties. They are properties by definition, and nothing
        // else here would work that out: the rdf:/rdfs: rule below does not cover `owl:`, so
        // `owl:topObjectProperty` fell through to a class — which is how `X ⊑
        // owl:topObjectProperty` came to read as a class subsumption with
        // `Declaration(Class(owl:topObjectProperty))` beside it.
        if (TOP_OBJECT_PROPERTY_IRI.equals(iri)
                || OWLRDFVocabulary.OWL_BOTTOM_OBJECT_PROPERTY.getIRI().equals(iri)) {
            return df.getOWLObjectProperty(iri);
        }
        if (TOP_DATA_PROPERTY_IRI.equals(iri)
                || OWLRDFVocabulary.OWL_BOTTOM_DATA_PROPERTY.getIRI().equals(iri)) {
            return df.getOWLDataProperty(iri);
        }
        // For remaining rdf:/rdfs: names, use case convention:
        //   lower-case local part → object property  (e.g. rdf:type, rdfs:subClassOf)
        //   upper-case local part → class            (e.g. rdfs:Resource, rdfs:Class)
        // rdf:nil is an instance, not a property — let it fall through to class.
        if (text.startsWith("rdf:") || text.startsWith("rdfs:")) {
            int colon = text.indexOf(':');
            String local = text.substring(colon + 1);
            if (!local.isEmpty() && Character.isLowerCase(local.charAt(0))
                    && !text.equals("rdf:nil")) {
                return df.getOWLObjectProperty(iri);
            }
            return df.getOWLClass(iri);
        }
        return df.getOWLClass(iri);
    }

    /**
     * Builds the object property expression for a propertyExpr.
     *
     * <p>Parentheses and doubled inverse markers are collapsed by
     * {@link PropertyExprs}, leaving either a named property or the inverse of
     * one — which is all {@code getOWLObjectInverseOf} accepts.
     */
    private OWLObjectPropertyExpression buildObjectProp(DLESyntaxParser.PropertyExprContext ctx) {
        OWLObjectProperty prop = df.getOWLObjectProperty(expandName(PropertyExprs.coreName(ctx)));
        return PropertyExprs.isInverse(ctx) ? df.getOWLObjectInverseOf(prop) : prop;
    }

    /** Returns the local text of the name in a propertyExpr (for type lookup). */
    private String propName(DLESyntaxParser.PropertyExprContext ctx) {
        return PropertyExprs.coreNameText(ctx);
    }

    private DLESyntaxParser.NameContext propCtxName(DLESyntaxParser.PropertyExprContext ctx) {
        return PropertyExprs.coreName(ctx);
    }

    /**
     * Consumes `X ⊑ owl:topObjectProperty`, `X ⊑ owl:topDataProperty` and — for a punned
     * name only — `X ⊑ ⊤`, recording a declaration instead of a subsumption.
     *
     * @return true when the statement was handled and should produce no axiom
     */
    private boolean consumeKindStatement(DLESyntaxParser.SubClassAxiomContext ctx) {
        String lhs = loneName(ctx.classExpr(0));
        if (lhs == null) return false;

        // The two property forms are no longer intercepted here. They parse as the
        // ordinary sub-property axioms they are, and DLEOntologyParser removes them once
        // parsing is done — see removeImplicitKindAxioms there for why that is the better
        // place. Their kind evidence does not depend on this method: EntityTypeScanner
        // records it, and explicitRole with it, in pass one.
        //
        // `X ⊑ ⊤` is still intercepted, and only in the pun case. It is NOT a tautology
        // worth removing in general — `ClassName ⊑ ⊤` is how a class is declared in DL and
        // appears throughout real documents — so it stays the ordinary subsumption it has
        // always been, and only the class side of a pun arrives as a declaration instead.
        //
        // Consuming it more widely — for any name whose local part is not capitalised, which
        // an earlier revision did — destroys a `SubClassOf(X, owl:Thing)` axiom the author
        // wrote, and makes writing non-idempotent, because the writer then re-adds the line
        // from a different source on the next pass.
        //
        // `X ⊑ ⊤` is consumed only when the document has also stated that X is a role —
        // that pair is what marks a pun, and the class side of a pun has to arrive as a
        // declaration rather than a subsumption.
        //
        // Everywhere else it stays the ordinary subsumption it has always been. Consuming
        // it more widely — for any name whose local part is not capitalised, which an
        // earlier revision did — destroys a `SubClassOf(X, owl:Thing)` axiom the author
        // wrote, and makes writing non-idempotent, because the writer then re-adds the
        // line from a different source on the next pass.
        // `T ⊑ rdfs:Literal` is the datatype counterpart of the two property markers, and
        // the only one of the three with nothing to produce: OWL has no datatype subsumption,
        // just DatatypeDefinition, which is an equivalence and would say something far
        // stronger. So the statement becomes the declaration it means, and — unlike the
        // property markers, which parse as real sub-property axioms and are removed
        // afterwards — there is nothing left to filter out. A round trip cannot gain an axiom
        // from it.
        String rhsName = loneName(ctx.classExpr(1));
        if (rhsName != null) {
            IRI rhsIri = expandNameText(rhsName);
            if (rhsIri != null
                    && EntityTypeScanner.RDFS_LITERAL_IRI.equals(rhsIri.toString())) {
                IRI iri = expandNameText(lhs);
                if (iri == null) return false;
                axioms.add(df.getOWLDeclarationAxiom(df.getOWLDatatype(iri)));
                statedKindIRIs.add(iri);
                return true;
            }
        }

        if (Parens.atomOf(ctx.classExpr(1)) instanceof DLESyntaxParser.TopAtomContext
                && explicitRoleNames.contains(lhs)) {
            IRI iri = expandNameText(lhs);
            if (iri == null) return false;
            axioms.add(df.getOWLDeclarationAxiom(df.getOWLClass(iri)));
            statedKindIRIs.add(iri);
            return true;
        }
        return false;
    }

    private static final IRI TOP_OBJECT_PROPERTY_IRI =
        OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI();
    private static final IRI TOP_DATA_PROPERTY_IRI =
        OWLRDFVocabulary.OWL_TOP_DATA_PROPERTY.getIRI();


    /** The text of a classExpr that is nothing but a single name, else null. */
    @Nullable
    private String loneName(DLESyntaxParser.ClassExprContext ctx) {
        DLESyntaxParser.AtomContext atom = Parens.atomOf(ctx);
        return atom instanceof DLESyntaxParser.NameAtomContext
            ? ((DLESyntaxParser.NameAtomContext) atom).name().getText()
            : null;
    }

    /**
     * Whether an IRI belongs to a punned name.
     *
     * <p>Resolved on first use rather than in the constructor: expanding a name needs the
     * prefix map, and that is filled while visiting. The grammar puts every {@code @prefix}
     * ahead of the first axiom, so the map is complete before any coercion can be asked for.
     */
    private boolean isPunned(IRI iri) {
        if (punnedIRIs == null) {
            punnedIRIs = new HashSet<>();
            for (String name : punnedNames) {
                IRI resolved = expandNameText(name);
                if (resolved != null) punnedIRIs.add(resolved);
            }
        }
        return punnedIRIs.contains(iri);
    }

    /** "the data property" / "the object property" / "the class", for a diagnostic. */
    private static String describeKind(OWLObject obj) {
        if (obj instanceof OWLDataPropertyExpression)   return "the data property";
        if (obj instanceof OWLObjectPropertyExpression) return "the object property";
        if (obj instanceof OWLClassExpression)          return "the class";
        return "";
    }

    private OWLClassExpression asClass(OWLObject obj) {
        if (obj instanceof OWLClassExpression) return (OWLClassExpression) obj;
        // A punned name reaching a class position is the pun being used, not an error.
        //
        // Names resolve to one kind, so a punned name arrives here as the property it also
        // is. The position is unambiguous — only a class can go here — so take the class
        // reading. Restricted to names with class evidence, so that a genuine modelling
        // mistake (a pure property as a restriction filler) still gets the diagnostic below.
        if (obj instanceof OWLEntity && isPunned(((OWLEntity) obj).getIRI())) {
            return df.getOWLClass(((OWLEntity) obj).getIRI());
        }
        // Reached when a well-formed expression puts something in a class
        // position that cannot be a class — most often a datatype, e.g. an
        // object-property restriction whose filler is xsd:integer. The scanner
        // catches the common cases with a specific message; this is the backstop,
        // and it must still read as a diagnostic rather than an internal error.
        // The advice has to match what was actually found. This sentence was hard-coded, so
        // a property expression in a class position was explained as though it were a
        // datatype — misinforming the reader about their own document, and the only guidance
        // offered for the shape.
        String advice = obj instanceof OWLPropertyExpression
            ? " A property expression cannot stand where a class is expected; OWL has no"
                + " axiom relating a property to a class."
            : " A datatype can only be the filler of a data property restriction.";
        throw new DLESemanticException(
            "expected a class expression here, but found " + describe(obj) + "." + advice,
            currentLine, 0);
    }

    /** A short, author-facing description of an OWL object for error messages. */
    private static String describe(OWLObject obj) {
        if (obj == null) return "nothing";
        if (obj instanceof OWLDatatype) return "the datatype " + obj;
        if (obj instanceof OWLDataRange) return "the data range " + obj;
        return String.valueOf(obj);
    }

    private OWLDataRange asDataRange(OWLObject obj) {
        if (obj instanceof OWLDataRange) return (OWLDataRange) obj;
        // ⊤ in data context → top data type
        if (obj instanceof OWLClass && ((OWLClass) obj).isOWLThing()) return df.getTopDatatype();
        // and ⊥ → the empty data range, which is how OWL spells it. Without this,
        // `⊤ ⊑ ∀d.⊥` — "d has no values", a real thing to state — was refused: ⊥ made d an
        // object property, contradicting whatever had said it was a data one. Both tops are
        // forgiving here or an author has to know which of the pair is.
        if (obj instanceof OWLClass && ((OWLClass) obj).isOWLNothing()) {
            return df.getOWLDataComplementOf(df.getTopDatatype());
        }
        // A named class whose IRI is an XSD/RDF datatype → treat as OWLDatatype
        if (obj instanceof OWLClass) {
            IRI iri = ((OWLClass) obj).getIRI();
            String ns = iri.getNamespace();
            if (ns.startsWith("http://www.w3.org/2001/XMLSchema#")
                    || ns.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
                return df.getOWLDatatype(iri);
            }
        }
        // A class where a datatype belongs — the mirror of asClass. Most often a
        // property used with a datatype filler in one axiom and a class in another.
        throw new DLESemanticException(
            "expected a datatype or data range here, but found " + describe(obj)
                + ". A class can only be the filler of an object property restriction.",
            currentLine, 0);
    }

    private boolean isOWLThing(OWLObject obj) {
        return obj instanceof OWLClass && ((OWLClass) obj).isOWLThing();
    }

    private OWLLiteral buildLiteral(DLESyntaxParser.OneOfElemContext ctx) {
        DLESyntaxParser.LiteralContext lit = ((DLESyntaxParser.LiteralElemContext) ctx).literal();
        if (lit instanceof DLESyntaxParser.StringLiteralContext) {
            return typedLiteral((DLESyntaxParser.StringLiteralContext) lit);
        }
        if (lit instanceof DLESyntaxParser.NumberLiteralContext) {
            String s = lit.getText();
            if (s.contains(".")) return df.getOWLLiteral(Double.parseDouble(s));
            return integerLiteral(s);
        }
        // BoolLiteral
        return df.getOWLLiteral(Boolean.parseBoolean(lit.getText()));
    }

    // ── Datatype restrictions ─────────────────────────────────────────────────

    /**
     * A narrowed datatype: {@code xsd:integer[≥1 ⊓ ≤40]}, {@code xsd:string[matches "…"]}.
     *
     * <p>One bracket after the datatype, holding facets in either spelling and in any
     * mixture. The two spellings used to be two shapes — the keyword facets lived in
     * {@code [xsd:string ⊓ [matches "…"]]}, with the datatype inside — so one concept had
     * two notations and an author had to know which facets belonged to which. That shape is
     * still read; it is no longer written.
     */
    @Override
    public OWLObject visitRestrictedDatatypeAtom(
            DLESyntaxParser.RestrictedDatatypeAtomContext ctx) {
        OWLDatatype base = df.getOWLDatatype(expandName(ctx.name()));
        List<OWLFacetRestriction> facets = ctx.facetItem().stream()
            .map(this::facetRestriction)
            .collect(Collectors.toList());
        return df.getOWLDatatypeRestriction(base, facets);
    }

    /** One facet, from whichever of the two spellings it was written in. */
    private OWLFacetRestriction facetRestriction(DLESyntaxParser.FacetItemContext item) {
        if (item instanceof DLESyntaxParser.ComparisonFacetContext) {
            DLESyntaxParser.ComparisonFacetContext f =
                (DLESyntaxParser.ComparisonFacetContext) item;
            OWLFacet facet = f.MIN() != null ? OWLFacet.MIN_INCLUSIVE
                          : f.MAX() != null ? OWLFacet.MAX_INCLUSIVE
                          : f.GT()  != null ? OWLFacet.MIN_EXCLUSIVE
                          :                   OWLFacet.MAX_EXCLUSIVE;
            String numText = f.NUMBER().getText();
            OWLLiteral value = numText.contains(".")
                ? df.getOWLLiteral(Double.parseDouble(numText))
                : integerLiteral(numText);
            return df.getOWLFacetRestriction(facet, value);
        }
        DLESyntaxParser.KeywordFacetContext f = (DLESyntaxParser.KeywordFacetContext) item;
        return df.getOWLFacetRestriction(facetFromName(f.name()), buildFacetLiteral(f.literal()));
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
            throw DLESemanticException.at(nameCtx, "unknown datatype facet <" + iri + ">");
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
            default:
                throw new DLESemanticException(
                    "unknown datatype facet '" + keyword + "'", currentLine, 0);
        }
    }

    private OWLLiteral buildFacetLiteral(DLESyntaxParser.LiteralContext lit) {
        // A facet value is a typed literal. OWL has no facet that compares against a
        // language, so a tag here cannot mean anything — and silently keeping it produced a
        // datatype restriction no reasoner will accept.
        // The STRING token, not the whole literal: the datatype is a sibling of it now, and
        // a typed facet value is legitimate — only a language is not.
        if (lit instanceof DLESyntaxParser.StringLiteralContext
                && languageTag(((DLESyntaxParser.StringLiteralContext) lit)
                    .STRING().getText()) != null) {
            throw new DLESemanticException(
                "a facet value cannot carry a language tag: " + lit.getText()
                    + ". Facets compare against a typed value, so drop the tag.",
                lit.start.getLine(), lit.start.getCharPositionInLine());
        }
        return literalOf(lit);
    }

    /**
     * An integer value, of any size.
     *
     * <p>{@code xsd:integer} is unbounded, so a document may legitimately carry a value no
     * {@code int} can hold — a millisecond timestamp, or an identifier held as data, reaches
     * ten digits. {@code Integer.parseInt} threw its own message through, so the user saw
     * {@code For input string: "3000000000"} with no line, no column and no file named, on
     * a value DLe had written itself.
     */
    private OWLLiteral integerLiteral(String text) {
        try {
            return df.getOWLLiteral(Integer.parseInt(text));
        } catch (NumberFormatException outsideInt) {
            return df.getOWLLiteral(text, df.getIntegerOWLDatatype());
        }
    }

    /** Builds a literal from any of the three spellings the grammar admits. */
    private OWLLiteral literalOf(DLESyntaxParser.LiteralContext lit) {
        if (lit instanceof DLESyntaxParser.StringLiteralContext) {
            return typedLiteral((DLESyntaxParser.StringLiteralContext) lit);
        }
        if (lit instanceof DLESyntaxParser.NumberLiteralContext) {
            String s = lit.getText();
            if (s.contains(".")) return df.getOWLLiteral(Double.parseDouble(s));
            return integerLiteral(s);
        }
        return df.getOWLLiteral(Boolean.parseBoolean(lit.getText()));
    }

    /**
     * A string literal, with a datatype or a language tag when it carries one.
     *
     * <p>Without a datatype every typed value became a plain string: twenty-four XSD
     * datatypes collapsed to {@code xsd:string}, and a user-declared one could not survive
     * at all. {@code xsd:string} itself is implicit and never written, but is accepted when
     * spelled out.
     */
    private OWLLiteral typedLiteral(DLESyntaxParser.StringLiteralContext ctx) {
        String tokenText = ctx.STRING().getText();
        if (ctx.name() == null) {
            return stringLiteral(tokenText);
        }
        if (languageTag(tokenText) != null) {
            throw new DLESemanticException(
                "a literal cannot carry both a language tag and a datatype: "
                    + ctx.getText() + ". A tagged string is rdf:langString already, so the"
                    + " datatype either repeats that or contradicts it; write one or the"
                    + " other.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        IRI datatype = expandName(ctx.name());
        return df.getOWLLiteral(unquote(tokenText), df.getOWLDatatype(datatype));
    }

    /**
     * An annotation value: a quoted string, with a language tag or a datatype.
     *
     * <p>The same rule as {@link #typedLiteral}, against the annotation grammar rather than
     * the literal one. Kept as its own method rather than folded into that one because the
     * two rules are separate in the grammar and the contexts do not share a type.
     */
    private OWLLiteral annotationLiteral(DLESyntaxParser.AnnotationStringContext ctx) {
        String tokenText = ctx.STRING().getText();
        if (ctx.name() == null) {
            return stringLiteral(tokenText);
        }
        if (languageTag(tokenText) != null) {
            throw new DLESemanticException(
                "an annotation value cannot carry both a language tag and a datatype: "
                    + ctx.getText() + ". A tagged string is rdf:langString already, so the"
                    + " datatype either repeats that or contradicts it; write one or the"
                    + " other.",
                ctx.start.getLine(), ctx.start.getCharPositionInLine());
        }
        return df.getOWLLiteral(unquote(tokenText), df.getOWLDatatype(expandName(ctx.name())));
    }

    private OWLLiteral stringLiteral(String tokenText) {
        String tag = languageTag(tokenText);
        return tag == null ? df.getOWLLiteral(unquote(tokenText))
                           : df.getOWLLiteral(unquote(tokenText), tag);
    }
}
