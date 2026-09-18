package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The header directives take a quoted IRI as well as an angle-bracket one.
 *
 * <p>{@code @import} has always taken both — the quoted form exists because a relative path
 * beside this document cannot be written as an IRI — and an author who writes one writes the
 * others. Two working documents did exactly that and were refused:
 *
 * <pre>
 * @ontology "https://example.com/ontologies/s2e"
 * @prefix s2e: "https://example.com/ontologies/s2e#"
 * </pre>
 *
 * <p>with {@code extraneous input '"https://…"' expecting …} followed by a list of the tokens
 * the parser wanted instead — which says nothing about what was wrong or how to fix it.
 *
 * <p>Nothing about the meaning changes: a namespace and an identity are absolute IRIs in
 * either spelling, and {@code @ontology} and {@code @version} still require an absolute one.
 * Unlike an import there is no relative reading to fall back on.
 */
class QuotedHeaderIriTest {

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    @Test
    void aQuotedOntologyIriNamesTheDocument() throws Exception {
        OWLOntology o = read("@ontology \"https://example.com/o\"\n"
            + "@prefix : <http://example.org/a#>\nA ⊑ B\n");
        assertEquals(IRI.create("https://example.com/o"),
            o.getOntologyID().getOntologyIRI().orElse(null),
            "the quoted form must name the ontology");
    }

    @Test
    void aQuotedVersionIriIsAccepted() throws Exception {
        OWLOntology o = read("@ontology \"https://example.com/o\"\n"
            + "@version \"https://example.com/o/1.0\"\n"
            + "@prefix : <http://example.org/a#>\nA ⊑ B\n");
        assertEquals(IRI.create("https://example.com/o/1.0"),
            o.getOntologyID().getVersionIRI().orElse(null),
            "the quoted form must name the version");
    }

    @Test
    void aQuotedPrefixNamespaceResolvesNames() throws Exception {
        OWLOntology o = read("@prefix : \"http://example.org/a#\"\n"
            + "@prefix ex: \"http://example.org/e#\"\n"
            + "A ⊑ ex:B\n");
        assertTrue(o.containsClassInSignature(IRI.create("http://example.org/a#A")),
            () -> "the default namespace must come from the quoted form: "
                + o.getLogicalAxioms());
        assertTrue(o.containsClassInSignature(IRI.create("http://example.org/e#B")),
            () -> "and so must a named one: " + o.getLogicalAxioms());
    }

    /** The two spellings must mean exactly the same thing. */
    @Test
    void theTwoSpellingsAgree() throws Exception {
        OWLOntology quoted = read("@ontology \"https://example.com/o\"\n"
            + "@prefix : \"http://example.org/a#\"\nA ⊑ B\n");
        OWLOntology bracketed = read("@ontology <https://example.com/o>\n"
            + "@prefix : <http://example.org/a#>\nA ⊑ B\n");
        assertEquals(bracketed.getOntologyID(), quoted.getOntologyID(),
            "the identity must not depend on the spelling");
        assertEquals(bracketed.getLogicalAxioms(), quoted.getLogicalAxioms(),
            "nor must the axioms");
    }

    /** An identity still has to be absolute — there is no relative reading for one. */
    @Test
    void aQuotedRelativeIdentityIsStillRefused() {
        DLESemanticException e = assertThrows(DLESemanticException.class,
            () -> read("@ontology \"not-absolute\"\n@prefix : <http://example.org/a#>\n"));
        assertTrue(e.getMessage().contains("absolute"),
            () -> "an identity must be absolute in either spelling: " + e.getMessage());
    }

    /** And it names a document, so it carries no language tag. */
    @Test
    void aTaggedIdentityIsRefused() {
        DLESemanticException e = assertThrows(DLESemanticException.class,
            () -> read("@ontology \"https://example.com/o\"@en\n"
                + "@prefix : <http://example.org/a#>\n"));
        assertTrue(e.getMessage().contains("language tag"),
            () -> "expected the tag to be refused: " + e.getMessage());
    }
}
