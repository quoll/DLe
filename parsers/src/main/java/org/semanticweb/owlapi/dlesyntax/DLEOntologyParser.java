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

    private static final long serialVersionUID = 1L;

    /** Creates a new instance of this parser. */
    public DLEOntologyParser() {}

    @Override
    public OWLDocumentFormat parse(OWLOntologyDocumentSource source,
                                   OWLOntology ontology,
                                   OWLOntologyLoaderConfiguration configuration) {
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

            // Apply ontology ID; fall back to the default IRI when none is declared.
            IRI ontIRI = visitor.getOntologyIRI();
            if (ontIRI == null) ontIRI = DLESyntaxAxiomVisitor.DLE_DEFAULT_ONTOLOGY_IRI;
            IRI verIRI = visitor.getVersionIRI();
            OWLOntologyID id = new OWLOntologyID(
                java.util.Optional.of(ontIRI),
                java.util.Optional.ofNullable(verIRI));
            ontology.getOWLOntologyManager().applyChange(new SetOntologyID(ontology, id));

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
                manager.makeLoadImportRequest(declaration, configuration);
            }

            DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
            visitor.getPrefixes().forEach(format::setPrefix);
            return format;

        } catch (OWLOntologyInputSourceException | IOException e) {
            throw new OWLParserException(e);
        }
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
        java.net.URI reference;
        try {
            reference = new java.net.URI(ref);
        } catch (java.net.URISyntaxException e) {
            return IRI.create(ref);   // not a URI at all; let OWLAPI report it
        }
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
