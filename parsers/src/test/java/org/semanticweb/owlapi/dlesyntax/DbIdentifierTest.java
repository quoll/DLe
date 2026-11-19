package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @db} identifies a resource by a key or by another resource.
 *
 * <p>The annotation says two things: that the resource is in the database at
 * all, and what it is queried by there. When the store was a property graph the
 * second was always a string — {@code functionalFit} is the column
 * {@code functional_fit}. Against an RDF store it is a resource:
 * {@code rtype:38c3b2cd-…}. Writing that IRI as a string and having whatever
 * reads the document take it apart again is a conversion neither end needs, and
 * {@code rdfs:isDefinedBy} ranges over {@code rdfs:Resource}, so it need not
 * happen: the identifier may be a literal or an IRI.
 *
 * <p>The bare form follows from that. {@code @db A} says A is in the database
 * under its own identifier, and its own identifier is A — the resource, not
 * its local name spelled as a string, which is what it used to record.
 */
class DbIdentifierTest {

    private static final String NS = "http://example.org/db#";
    private static final String MM = "http://example.org/mm/";
    private static final String PREFIX = "@prefix mm: <" + MM + ">\n@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private final OWLAnnotationProperty definedBy =
        df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_IS_DEFINED_BY.getIRI());

    private OWLOntology read(String document) throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager().createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            o.getOWLOntologyManager().getOntologyLoaderConfiguration());
        return o;
    }

    private String rewrite(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(document), o, m.getOntologyLoaderConfiguration());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private OWLAnnotationAssertionAxiom assertion(String local, OWLAnnotationValue value) {
        return df.getOWLAnnotationAssertionAxiom(definedBy, IRI.create(NS + local), value);
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The form a property-graph store needs: a key that is not a name. */
    @Test
    void aStringIsALiteralIdentifier() throws Exception {
        assertTrue(read(PREFIX + "@db functionalFit \"functional_fit\"\nfunctionalFit ⊑ owl:topDataProperty\n")
                .containsAxiom(assertion("functionalFit", df.getOWLLiteral("functional_fit"))));
    }

    /** The form an RDF store needs: the resource it is queried by. */
    @Test
    void aPrefixedNameIsAnIriIdentifier() throws Exception {
        assertTrue(read(PREFIX + "@db functionalFit mm:ftype\nfunctionalFit ⊑ owl:topDataProperty\n")
                .containsAxiom(assertion("functionalFit", IRI.create(MM + "ftype"))),
            "a name identifies a resource, not a string that looks like one");
    }

    /** A bare name resolves through the default prefix like any other name. */
    @Test
    void aBareNameIsAnIriToo() throws Exception {
        assertTrue(read(PREFIX + "@db A B\nA ⊑ ⊤\nB ⊑ ⊤\n")
                .containsAxiom(assertion("A", IRI.create(NS + "B"))));
    }

    /** No identifier means the subject itself. */
    @Test
    void theBareFormNamesTheSubject() throws Exception {
        assertTrue(read(PREFIX + "@db A\nA ⊑ ⊤\n")
                .containsAxiom(assertion("A", IRI.create(NS + "A"))),
            "@db A should record the resource A, not the string \"A\"");
    }

    /**
     * The optional identifier must not swallow the statement after it.
     *
     * <p>Whitespace is not significant and the value may now be a name, so
     * {@code @db A} followed by {@code B ⊑ ⊤} could in principle read as
     * {@code @db A B} with a dangling {@code ⊑ ⊤}.
     */
    @Test
    void anAbsentIdentifierDoesNotConsumeTheNextStatement() throws Exception {
        OWLOntology o = read(PREFIX + "@db A\nB ⊑ ⊤\n");
        assertTrue(o.containsAxiom(assertion("A", IRI.create(NS + "A"))));
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "B")), df.getOWLThing())),
            "B ⊑ ⊤ is its own statement");
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    @Test
    void anIriIdentifierIsWrittenAsAName() throws Exception {
        String body = statementsOnly(rewrite(
            PREFIX + "@db functionalFit mm:ftype\nfunctionalFit ⊑ owl:topDataProperty\n"));
        assertTrue(body.contains("@db functionalFit mm:ftype"),
            () -> "an IRI identifier used to fall through to @ann:\n" + body);
    }

    @Test
    void theSubjectAsItsOwnIdentifierIsWrittenBare() throws Exception {
        String body = statementsOnly(rewrite(PREFIX + "@db A\nA ⊑ ⊤\n"));
        assertTrue(body.contains("@db A\n"),
            () -> "naming the subject again says nothing:\n" + body);
    }

    /** Each form has to mean the same thing after a trip through the writer. */
    @Test
    void everyFormSurvivesARoundTrip() throws Exception {
        for (String document : new String[] {
            PREFIX + "@db A\nA ⊑ ⊤\n",
            PREFIX + "@db A mm:ftype\nA ⊑ ⊤\n",
            PREFIX + "@db A \"functional_fit\"\nA ⊑ ⊤\n",
            // the literal that equals the name: once the bare form meant the IRI,
            // suppressing this one would hand back a different axiom
            PREFIX + "@db A \"A\"\nA ⊑ ⊤\n",
        }) {
            OWLOntology first = read(document);
            OWLOntology second = read(rewrite(document));
            for (OWLAxiom axiom : first.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                assertTrue(second.containsAxiom(axiom),
                    () -> "lost " + axiom + " from:\n" + document);
            }
        }
    }
}
