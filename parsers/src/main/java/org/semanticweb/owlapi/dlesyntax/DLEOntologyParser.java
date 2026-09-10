package org.semanticweb.owlapi.dlesyntax;

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

    /** Warnings from the last parse; see {@link #getWarnings()}. */
    private final List<String> warnings = new java.util.ArrayList<>();

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
                scanner.getExplicitRoleNames(),
                scanner.getPunnedNames(),
                tokens);
            visitor.visit(tree);

            ontology.getOWLOntologyManager()
                .addAxioms(ontology, new java.util.HashSet<>(visitor.getAxioms()));

            DualDeclarationResolver.resolve(ontology, visitor.getStatedKindIRIs());
            DefaultLabelAdder.addDefaultLabels(ontology);

            // Apply ontology ID; fall back to the default IRI when none is declared.
            IRI ontIRI = visitor.getOntologyIRI();
            if (ontIRI == null) ontIRI = DLESyntaxAxiomVisitor.DLE_DEFAULT_ONTOLOGY_IRI;
            IRI verIRI = visitor.getVersionIRI();
            OWLOntologyID id = new OWLOntologyID(
                java.util.Optional.of(ontIRI),
                java.util.Optional.ofNullable(verIRI));
            ontology.getOWLOntologyManager().applyChange(new SetOntologyID(ontology, id));

            // Apply import declarations
            for (IRI importIRI : visitor.getImports()) {
                ontology.getOWLOntologyManager().applyChange(
                    new AddImport(ontology,
                        ontology.getOWLOntologyManager().getOWLDataFactory()
                            .getOWLImportsDeclaration(importIRI)));
            }

            warnings.clear();
            warnings.addAll(visitor.getWarnings());

            DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
            visitor.getPrefixes().forEach(format::setPrefix);
            return format;

        } catch (OWLOntologyInputSourceException | IOException e) {
            throw new OWLParserException(e);
        }
    }

    /**
     * Problems from the last parse that did not stop it.
     *
     * <p>Only reachable by a caller that holds the parser, which is how {@code owltx} uses
     * it. Through OWL API's ServiceLoader the instance is not visible, so the same messages
     * also go to slf4j.
     */
    public List<String> getWarnings() {
        return java.util.Collections.unmodifiableList(warnings);
    }

    @Override
    public OWLDocumentFormatFactory getSupportedFormat() {
        return new org.semanticweb.owlapi.formats.DLESyntaxDocumentFormatFactory();
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
            if (offendingSymbol instanceof Token && ":".equals(((Token) offendingSymbol).getText())) {
                return ". A leading ':' names the default namespace only when the local part"
                    + " begins with a digit, as in ':762705008' — a digit-initial name has no"
                    + " other spelling. Write any other name in the default namespace bare,"
                    + " without the colon";
            }
            return "";
        }
    }
}
