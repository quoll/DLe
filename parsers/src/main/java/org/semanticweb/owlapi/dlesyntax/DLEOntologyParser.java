package org.semanticweb.owlapi.dlesyntax;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.AbstractOWLParser;
import org.semanticweb.owlapi.io.DocumentSources;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyInputSourceException;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormatFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.SetOntologyID;

/**
 * Parses a DLE Syntax document and populates an {@link OWLOntology}.
 *
 * <p>The parsing is a two-pass process:
 * <ol>
 *   <li>{@link EntityTypeScanner} — scans the parse tree to classify names as
 *       object properties, data properties, or classes.</li>
 *   <li>{@link DLESyntaxAxiomVisitor} — converts the parse tree to OWLAPI
 *       axioms using the classification from the first pass.</li>
 * </ol>
 */
public class DLEOntologyParser extends AbstractOWLParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DLEOntologyParser.class);

    /**
     * Where warnings from the parse in progress accumulate, shared by every parser on the
     * call stack.
     *
     * <p>It has to be shared. An imported document is parsed by a parser OWL API creates
     * through its ServiceLoader, which no caller can reach — so a per-instance list reported
     * a failure at the top level and lost the identical failure one level down, which is
     * exactly the class of silence this reporting exists to remove.
     *
     * <p>Thread-local rather than static, so two parses on different threads cannot see each
     * other's warnings, and the outermost parse is the one that owns and clears it.
     */
    private static final ThreadLocal<List<String>> ACTIVE_WARNINGS = new ThreadLocal<>();

    /** Warnings from the last parse this instance began; see {@link #getWarnings()}. */
    private volatile List<String> warnings = java.util.Collections.emptyList();

    private static final long serialVersionUID = 1L;

    /** Creates a new instance of this parser. */
    public DLEOntologyParser() {}

    @Override
    public OWLDocumentFormat parse(OWLOntologyDocumentSource source,
                                   OWLOntology ontology,
                                   OWLOntologyLoaderConfiguration configuration) {
        List<String> outer = ACTIVE_WARNINGS.get();
        List<String> sink = outer != null ? outer : new java.util.ArrayList<>();
        if (outer == null) ACTIVE_WARNINGS.set(sink);
        try {
            Reader reader = DocumentSources.wrapInputAsReader(source, configuration);
            var chars  = CharStreams.fromReader(reader);
            var lexer  = new DLESyntaxLexer(chars);
            var tokens = new CommonTokenStream(lexer);
            var parser = new DLESyntaxParser(tokens);

            // Replace ANTLR's default ConsoleErrorListener with one that throws,
            // so that non-DLE input causes OWLAPI to try the next parser instead
            // of treating an empty parse as a successful load.
            var errorListener = new ThrowingErrorListener();
            lexer.removeErrorListeners();
            lexer.addErrorListener(errorListener);
            parser.removeErrorListeners();
            parser.addErrorListener(errorListener);

            DLESyntaxParser.OntologyContext tree = parser.ontology();

            // Pass 1 — entity type classification
            EntityTypeScanner scanner = new EntityTypeScanner();
            scanner.visit(tree);
            scanner.propagatePropertyTypes();
            // Reject what parses but cannot be expressed in OWL, while the parse
            // tree is still around to report a location.
            scanner.validate();

            // Pass 2 — axiom construction
            // Fill the token stream so hidden comment tokens are available for retrieval.
            tokens.fill();
            DLESyntaxAxiomVisitor visitor = new DLESyntaxAxiomVisitor(
                ontology.getOWLOntologyManager().getOWLDataFactory(),
                scanner.getObjectPropertyNames(),
                scanner.getDataPropertyNames(),
                scanner.getPredicateNames(),
                tokens);
            visitor.visit(tree);

            ontology.getOWLOntologyManager()
                .addAxioms(ontology, new java.util.HashSet<>(visitor.getAxioms()));

            DualDeclarationResolver.resolve(ontology);
            DefaultLabelAdder.addDefaultLabels(ontology);

            // Apply the ontology ID. A document that declares no @ontology is anonymous —
            // it does not name itself, and nothing should claim it did.
            //
            // It used to be handed a fixed sentinel IRI instead, the same one for every such
            // document. That was invisible while imports were never followed; once they are,
            // two documents that both omit @ontology collide on it, and most DLe documents
            // omit it. Worse, the sentinel was this project's own published ontology IRI, so
            // a document carrying it could not import the DLe vocabulary.
            IRI ontIRI = visitor.getOntologyIRI();
            IRI verIRI = visitor.getVersionIRI();
            if (ontIRI == null && verIRI != null) {
                // OWL has no such ontology: a version IRI names a version *of* a named
                // ontology, and OWL API rejects the pair outright. Saying so beats inventing
                // an ontology IRI the author never wrote.
                throw new DLESemanticException(
                    "@version requires @ontology: a version identifies a version of a named"
                        + " ontology, so a document declaring a version must name itself",
                    -1, 0);
            }
            if (ontIRI != null) {
                ontology.getOWLOntologyManager().applyChange(new SetOntologyID(ontology,
                    new OWLOntologyID(java.util.Optional.of(ontIRI),
                                      java.util.Optional.ofNullable(verIRI))));
            }

            // Apply import declarations.
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            for (String ref : visitor.getIriImportRefs()) {
                declareAndLoad(manager, ontology, IRI.create(ref), configuration);
            }
            for (String ref : visitor.getQuotedImportRefs()) {
                declareAndLoad(manager, ontology,
                    resolveQuotedImport(source.getDocumentIRI(), ref), configuration);
            }

            DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
            visitor.getPrefixes().forEach(format::setPrefix);
            return format;

        } catch (OWLOntologyInputSourceException | IOException e) {
            throw new OWLParserException(e);
        } finally {
            // The outermost parse owns the sink: publish what accumulated, including when
            // this parse failed, so nothing from a previous document survives into the next.
            if (outer == null) {
                warnings = List.copyOf(sink);
                ACTIVE_WARNINGS.remove();
            }
        }
    }

    /** Problems from the last parse that did not stop it; see {@link #ACTIVE_WARNINGS}. */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public OWLDocumentFormatFactory getSupportedFormat() {
        return new org.semanticweb.owlapi.formats.DLESyntaxDocumentFormatFactory();
    }

    /**
     * Schemes OWL API can actually retrieve.
     *
     * <p>Deliberately short. A quoted reference carrying any other scheme is far more likely
     * to be a file whose name happens to contain a colon than an ontology someone can fetch:
     * {@code urn:} and {@code mailto:} say nothing about where to look, and there is no
     * retrieval mechanism for {@code classpath:} or {@code gopher:} — accepting those would
     * only guarantee a reported failure later. Anything unretrievable is therefore read as a
     * path, and {@code @import <iri>} remains the way to name an IRI of any scheme, for the
     * benefit of a caller with an IRI mapper or a catalogue.
     */
    private static final Set<String> RETRIEVABLE_SCHEMES = Set.of("http", "https", "file");

    /** A Windows path: a drive letter, a colon, then a separator. */
    private static final java.util.regex.Pattern WINDOWS_PATH =
        java.util.regex.Pattern.compile("^[A-Za-z]:[\\\\/].*");

    /**
     * Interprets a quoted import reference.
     *
     * <p>An IRI if it parses as one and carries a scheme we can retrieve; a file path
     * otherwise, including whenever it carries no scheme at all. A path is taken
     * <em>literally</em> — no percent-decoding — so a file really named {@code a%20b.dle},
     * or one containing {@code #} or {@code ?}, names itself rather than something else.
     * Reading it literally means percent-<em>encoding</em> it to build the IRI, which is
     * what makes the write side able to hand back the same spelling.
     */
    static IRI resolveQuotedImport(@Nullable IRI documentIRI, String ref) {
        java.net.URI parsed = tryUri(ref);
        if (parsed != null && parsed.getScheme() != null
                && RETRIEVABLE_SCHEMES.contains(
                    parsed.getScheme().toLowerCase(java.util.Locale.ROOT))) {
            return IRI.create(parsed.toString());
        }
        return resolveFilePath(documentIRI, ref);
    }

    /** Turns a file path into an absolute IRI, relative to the declaring document. */
    private static IRI resolveFilePath(@Nullable IRI documentIRI, String path) {
        if (WINDOWS_PATH.matcher(path).matches()) {
            try {
                return IRI.create(
                    new java.net.URI("file", "", "/" + path.replace('\\', '/'), null).toString());
            } catch (java.net.URISyntaxException notAFileUri) {
                // fall through and treat it as an ordinary relative path
            }
        }
        java.net.URI relative = asPath(path);
        if (relative == null) return IRI.create(path);   // nothing legal to build; OWLAPI reports it
        if (documentIRI == null) return IRI.create(relative.toString());
        java.net.URI base = tryUri(documentIRI.toString());
        if (base == null || !base.isAbsolute() || base.isOpaque()) {
            // A string or stream source has no location to resolve against. Leaving the
            // reference relative is more use than resolving it against something arbitrary.
            return IRI.create(relative.toString());
        }
        return IRI.create(base.resolve(relative).toString());
    }

    /**
     * Percent-encodes a file path into a relative URI reference.
     *
     * <p>A path whose first segment contains a colon gets {@code ./} in front: Java does not
     * escape a colon in a path, so {@code a:b.dle} would otherwise come back out as an
     * absolute URI with scheme {@code a}. Every other character the constructor escapes for
     * us, and decoding reverses it exactly — which is the property the writer relies on.
     */
    @Nullable
    private static java.net.URI asPath(String path) {
        String safe = firstSegmentHasColon(path) ? "./" + path : path;
        try {
            return new java.net.URI(null, null, safe, null);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Whether the part before the first slash contains a colon. */
    static boolean firstSegmentHasColon(String path) {
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        return first.indexOf(':') >= 0;
    }

    @Nullable
    private static java.net.URI tryUri(String text) {
        try {
            return new java.net.URI(text);
        } catch (java.net.URISyntaxException notAUri) {
            return null;
        }
    }

    /** Adds a warning to the parse in progress, wherever on the stack it started. */
    private static void warn(String message) {
        List<String> sink = ACTIVE_WARNINGS.get();
        if (sink != null) sink.add(message);
        LOGGER.warn("{}", message);
    }

    /** Records an import declaration and asks the manager to follow it. */
    private void declareAndLoad(OWLOntologyManager manager, OWLOntology ontology, IRI importIRI,
                                OWLOntologyLoaderConfiguration configuration) {
        OWLImportsDeclaration declaration =
            manager.getOWLDataFactory().getOWLImportsDeclaration(importIRI);
        manager.applyChange(new AddImport(ontology, declaration));
        loadImport(manager, declaration, configuration);
    }

    /**
     * Asks the manager to load an import, honouring the caller's missing-import strategy.
     *
     * <p>The manager reports a missing document by throwing {@code OWLOntologyCreationException},
     * which it catches itself and routes through the strategy. But a reference it can find no
     * factory for, or a fault inside the imported document's own parse, arrives as a
     * <em>RuntimeException</em> — outside that handling entirely, so {@code SILENT} was
     * bypassed and one unreadable import took the whole document down.
     *
     * <p>So a runtime failure is caught and routed through the strategy by hand. Under
     * anything but {@code SILENT} it is rethrown as {@code UnloadableImportException}, which
     * is the type the strategy documents and which carries the declaration that failed —
     * rethrowing the original left a caller's {@code catch (UnloadableImportException)}
     * unreached.
     */
    private void loadImport(OWLOntologyManager manager, OWLImportsDeclaration declaration,
                            OWLOntologyLoaderConfiguration configuration) {
        try {
            manager.makeLoadImportRequest(declaration, configuration);
        } catch (UnloadableImportException alreadyRouted) {
            throw alreadyRouted;
        } catch (RuntimeException e) {
            if (configuration.getMissingImportHandlingStrategy()
                    != MissingImportHandlingStrategy.SILENT) {
                throw new UnloadableImportException(
                    new OWLOntologyCreationException(describe(e), e), declaration);
            }
            warn("could not load import <" + declaration.getIRI() + ">: " + describe(e));
        }
    }

    /** A message for an exception that may not have one. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }

    /** Converts any ANTLR syntax error into an OWLParserException. */
    private static class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new OWLParserException("DLE syntax error at " + line + ":" + charPositionInLine
                + " — " + msg + hintFor(offendingSymbol));
        }

        /**
         * A leading colon is a name only for a digit-initial local part, so {@code :Dog}
         * fails where {@code :762705008} parses. ANTLR reports it as an unexpected ':' and
         * lists the token names, which does not explain the asymmetry.
         */
        private String hintFor(Object offendingSymbol) {
            if (!(offendingSymbol instanceof Token)) return "";
            String text = ((Token) offendingSymbol).getText();

            // A bare number where a name belongs. This is the error a document written by
            // an older version produces — it emitted the local part with no prefix — and it
            // is the one worth explaining, because the fix is not in the reader.
            if (!text.isEmpty() && text.charAt(0) >= '0' && text.charAt(0) <= '9') {
                return ". A name cannot begin with a digit unless it is prefixed: write"
                    + " :" + text + " for the default namespace, or use a declared prefix."
                    + " A document written before that form existed will have the bare"
                    + " spelling here";
            }
            // A lone colon where a name belongs. Only a digit may follow it, so this is
            // most often `:Something` written by hand.
            if (":".equals(text)) {
                return ". A leading ':' names the default namespace only when the local part"
                    + " begins with a digit — a digit-initial name has no other spelling."
                    + " Write any other name in the default namespace bare, without the"
                    + " colon";
            }
            return "";
        }
    }
}
