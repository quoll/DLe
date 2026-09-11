package org.semanticweb.owlapi.dlesyntax;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.List;

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

    /**
     * Problems from the last parse that did not stop it.
     *
     * <p>Cleared when a parse begins, not when one succeeds, so a failed parse does not
     * leave the previous document's warnings behind. Only a caller holding the parser can
     * read them, which is how {@code owltx} surfaces them; through OWL API's ServiceLoader
     * the instance is not visible.
     */
    private final List<String> warnings = new java.util.ArrayList<>();

    private static final long serialVersionUID = 1L;

    /** Creates a new instance of this parser. */
    public DLEOntologyParser() {}

    @Override
    public OWLDocumentFormat parse(OWLOntologyDocumentSource source,
                                   OWLOntology ontology,
                                   OWLOntologyLoaderConfiguration configuration) {
        warnings.clear();
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

            // Apply the ontology ID. A document that declares no @ontology is left
            // anonymous rather than given a made-up IRI.
            //
            // It used to be handed a fixed default, which was harmless while imports were
            // never followed. Now that they are, two documents that both omit @ontology —
            // and most do — collided on that one IRI, and the manager refused the second
            // with "Ontology already exists". Naming it after its own file would avoid the
            // collision but stamp a machine-specific location into every such document,
            // which is exactly the problem being avoided on the import side.
            //
            // Anonymous is also what the writer already assumed: it suppresses @ontology
            // when the IRI is the default, so output is unchanged either way.
            IRI ontIRI = visitor.getOntologyIRI();
            IRI verIRI = visitor.getVersionIRI();
            // A version IRI cannot be held without an ontology IRI — OWL API rejects the
            // pair — so a document declaring @version and no @ontology would have had its
            // version silently dropped by the anonymity above. The default IRI is kept for
            // that one case, because there is something to lose.
            //
            // Two such documents in one closure do still collide, but only if they declare
            // the *same* version, and then the collision is right: identical ID means
            // identical ontology under OWL's identity rules, and two documents claiming to
            // be version 1.0 of the unnamed ontology are claiming to be the same thing.
            // Different versions coexist.
            if (ontIRI == null && verIRI != null) {
                ontIRI = DLESyntaxAxiomVisitor.DLE_DEFAULT_ONTOLOGY_IRI;
            }
            if (ontIRI != null) {
                ontology.getOWLOntologyManager().applyChange(new SetOntologyID(ontology,
                    new OWLOntologyID(java.util.Optional.of(ontIRI),
                                      java.util.Optional.ofNullable(verIRI))));
            }

            // Apply import declarations, resolving relative references against this document
            for (String ref : visitor.getImportRefs()) {
                IRI importIRI = resolveImport(source.getDocumentIRI(), ref);
                OWLOntologyManager manager = ontology.getOWLOntologyManager();
                OWLImportsDeclaration declaration =
                    manager.getOWLDataFactory().getOWLImportsDeclaration(importIRI);
                manager.applyChange(new AddImport(ontology, declaration));
                // Recording the declaration does not fetch anything — imports were declared
                // and then never followed, so the closure of a document with an @import was
                // always just the document itself. Asking the manager to load it is what a
                // parser is expected to do, and it honours the caller's configuration for a
                // missing import rather than deciding here.
                loadImport(manager, declaration, configuration);
            }

            DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
            visitor.getPrefixes().forEach(format::setPrefix);
            return format;

        } catch (OWLOntologyInputSourceException | IOException e) {
            throw new OWLParserException(e);
        }
    }

    /** Problems from the last parse that did not stop it; see {@link #warnings}. */
    public List<String> getWarnings() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(warnings));
    }

    @Override
    public OWLDocumentFormatFactory getSupportedFormat() {
        return new org.semanticweb.owlapi.formats.DLESyntaxDocumentFormatFactory();
    }

    /**
     * Resolves an import reference against the document that declared it.
     *
     * <p>A relative reference — {@code "ardoqvocab.dle"}, the file beside this one — cannot
     * be carried as an IRI on its own. Under RFC 3986 a scheme must be followed by a slash
     * for the path to be hierarchical, so {@code file:ardoqvocab.dle} is an <em>opaque</em>
     * URI with no path at all: {@code new File(uri)} on it throws "URI is not hierarchical",
     * and nothing can open it. The reference has to become absolute, and the only sensible
     * base is the location of the document being parsed.
     *
     * <p>An absolute reference is returned untouched, so {@code @import <http://…>} and
     * {@code @import "http://…"} behave identically and neither changes meaning.
     *
     * <p>Where no usable base exists — a document parsed from a string or a stream, whose
     * document IRI is opaque — a relative reference is left as it stands rather than being
     * resolved against something arbitrary like the working directory. OWL API then reports
     * it, which is more use than silently loading the wrong file.
     */
    static IRI resolveImport(@Nullable IRI documentIRI, String ref) {
        java.net.URI reference = asUri(ref);
        if (reference == null) return IRI.create(ref);
        if (reference.isAbsolute()) return IRI.create(reference.toString());
        if (documentIRI == null) return IRI.create(ref);
        try {
            java.net.URI base = new java.net.URI(documentIRI.toString());
            if (!base.isAbsolute() || base.isOpaque()) return IRI.create(ref);
            return IRI.create(base.resolve(reference).toString());
        } catch (java.net.URISyntaxException e) {
            return IRI.create(ref);
        }
    }

    /**
     * Asks the manager to load an import, honouring the caller's missing-import strategy.
     *
     * <p>{@code makeLoadImportRequest} reports a missing document by throwing
     * {@code OWLOntologyCreationException}, which the manager itself catches and routes
     * through that strategy. But a reference it cannot find a factory for at all — an
     * unknown scheme, or a path it cannot make sense of — comes back as
     * {@code OWLOntologyFactoryNotFoundException}, a <em>RuntimeException</em>. That escapes
     * the manager's own handling, so {@code SILENT} was bypassed and one unreadable import
     * took the whole document down.
     */
    private void loadImport(OWLOntologyManager manager, OWLImportsDeclaration declaration,
                            OWLOntologyLoaderConfiguration configuration) {
        try {
            manager.makeLoadImportRequest(declaration, configuration);
        } catch (org.semanticweb.owlapi.model.OWLRuntimeException e) {
            if (configuration.getMissingImportHandlingStrategy()
                    == MissingImportHandlingStrategy.THROW_EXCEPTION) {
                throw e;
            }
            // Recorded rather than discarded. The manager's own missing-import listeners
            // never fire for this path — that is the whole reason it is caught here — so
            // swallowing it silently would leave an unreadable import with nothing at all
            // to show for it.
            warnings.add("could not load import <" + declaration.getIRI() + ">: "
                + e.getMessage());
        }
    }

    /**
     * Reads a reference as a URI, escaping it as a path if it is not already one.
     *
     * <p>A file name is the point of the quoted form, and a file name may contain a space —
     * which is illegal in a URI, so parsing it fails and the reference used to be passed
     * through unresolved. Escaping it as a path turns {@code my vocab.dle} into
     * {@code my%20vocab.dle}, which resolves and opens the file it names.
     *
     * <p>Tried as a plain URI first, because escaping an absolute reference as a path would
     * mangle it: {@code http://x/y} would become the relative path
     * {@code http:%2F%2Fx%2Fy}.
     *
     * @return the URI, or null if the reference cannot be made into one at all
     */
    @Nullable
    private static java.net.URI asUri(String ref) {
        try {
            return new java.net.URI(ref);
        } catch (java.net.URISyntaxException notAUri) {
            try {
                return new java.net.URI(null, null, ref, null);
            } catch (java.net.URISyntaxException notAPathEither) {
                return null;
            }
        }
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
