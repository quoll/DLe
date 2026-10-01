package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * An annotation value may carry a datatype, in every spelling that takes a value.
 *
 * <p>The writer quotes every annotation value and appends {@code ^^<datatype>} whenever it is
 * not {@code xsd:string}. The grammar's annotation rules took a bare {@code STRING}, so the
 * writer produced files its own reader refused with {@code extraneous input '^^'} — 71 of 98
 * literal shapes, across all five spellings. Any ontology with a dated or numeric annotation
 * hit it, which made it the widest-reaching defect in the writer.
 *
 * <p>All five forms share one grammar rule, so they cannot drift apart again.
 */
class AnnotationLiteralTest {

    private static final String NS = "http://example.org/n#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * The document without its generated header.
     *
     * <p>The header quotes the syntax it documents, so a {@code contains} check against the
     * whole document can pass on the explanation rather than on the statement.
     */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    /**
     * Each shorthand, and {@code @ann}, with a typed value.
     *
     * <p>One case per spelling because each was its own grammar alternative, and four of the
     * five were written by hand. The property fixes which spelling the writer chooses.
     */
    @ParameterizedTest(name = "{0} carries {2}")
    @CsvSource({
        "@label A,       http://www.w3.org/2000/01/rdf-schema#label,        date,    2024-01-02",
        "@doc A,         http://www.w3.org/2000/01/rdf-schema#comment,      integer, 7",
        "@storage A,     http://www.w3.org/2000/01/rdf-schema#seeAlso,      token,   abc",
        "@db A,          http://www.w3.org/2000/01/rdf-schema#isDefinedBy,  NCName,  abc",
        "@ann A note,    http://example.org/n#note,                         float,   1.5",
    })
    void aTypedAnnotationValueSurvivesARoundTrip(String spelling, String property,
                                                 String datatype, String value)
            throws Exception {
        OWLAnnotationProperty prop = df.getOWLAnnotationProperty(IRI.create(property));
        OWLLiteral literal = df.getOWLLiteral(value, df.getOWLDatatype(IRI.create(XSD + datatype)));
        OWLAxiom axiom = df.getOWLAnnotationAssertionAxiom(
            prop, IRI.create(NS + "A"), literal);
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, axiom);
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A"))));

        String written = statementsOnly(write(o));
        String expected = spelling + " \"" + value + "\"^^xsd:" + datatype;
        assertTrue(written.contains(expected),
            () -> "expected to find\n  " + expected + "\nin the statements:\n"
                + statementsOnly(written));

        OWLOntology back = read(written);
        assertTrue(back.containsAxiom(axiom),
            () -> "the annotation did not come back as itself. written:\n" + written
                + "\nback: " + back.axioms(AxiomType.ANNOTATION_ASSERTION).map(Object::toString)
                    .reduce("", (a, b) -> a + "\n  " + b));
    }

    /** A value with no datatype is still written plain — xsd:string stays implicit. */
    @Test
    void aPlainAnnotationValueGainsNoDatatype() throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
            IRI.create(NS + "A"), df.getOWLLiteral("plain")));
        // The statements, not the document. The generated header documents `^^` itself, so
        // this assertion began failing the moment the header gained that section — which is
        // the hazard every `contains` check against a whole DLe document carries.
        String body = statementsOnly(write(o));
        assertFalse(body.contains("^^"),
            () -> "a plain string needs no datatype written:\n" + body);
    }

    /** A language tag still works, and is still exclusive of a datatype. */
    @Test
    void aTaggedAnnotationValueIsUnaffected() throws Exception {
        OWLOntology o = manager.createOntology();
        OWLAxiom axiom = df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
            IRI.create(NS + "A"), df.getOWLLiteral("Neoplasm", "en"));
        manager.addAxiom(o, axiom);
        assertTrue(read(write(o)).containsAxiom(axiom), "a tagged annotation value must survive");
    }

    /**
     * A tag and a datatype together are refused, as they are for an ordinary literal.
     *
     * <p>Asserted on the message rather than the exception type: the annotation rule has its
     * own site, and a shared needle would let one site stand in for the other.
     */
    @Test
    void aTaggedAndTypedAnnotationValueIsRefused() {
        String document = "@prefix : <" + NS + ">\n"
            + "@label A \"x\"@en^^xsd:token\n";
        DLESemanticException e =
            assertThrows(DLESemanticException.class, () -> read(document));
        assertTrue(e.getMessage().contains("an annotation value cannot carry both"),
            () -> "expected the annotation-specific refusal, got: " + e.getMessage());
    }

    /** An IRI-valued annotation still takes the bare-name alternative. */
    @Test
    void anIriAnnotationValueStillWorks() throws Exception {
        OWLOntology o = manager.createOntology();
        OWLAxiom axiom = df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(IRI.create(NS + "note")),
            IRI.create(NS + "A"), IRI.create(NS + "Elsewhere"));
        manager.addAxiom(o, axiom);
        assertTrue(read(write(o)).containsAxiom(axiom),
            "an IRI-valued annotation must still round-trip");
    }
}
