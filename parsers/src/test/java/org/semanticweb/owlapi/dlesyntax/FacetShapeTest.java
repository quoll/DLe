package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWLFacet;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A narrowed datatype has one shape.
 *
 * <p>It had two. Ordered bounds on numbers went in a bracket after the datatype —
 * {@code xsd:integer[≥1 ⊓ ≤40]} — and every other facet went in a different shape with the
 * datatype inside and a bracket apiece: {@code [xsd:string ⊓ [matches "…"]]}. One concept,
 * two notations, and an author had to know which facets belonged to which, because
 * {@code xsd:string[matches "…"]} — the obvious guess — was a syntax error. It also cost a
 * line of the generated header to show both.
 *
 * <p>Now the datatype is always outside and one bracket holds every facet, with the
 * *spelling* of each chosen facet by facet rather than for the restriction as a whole: an
 * operator where a bare number can carry it, the facet's name otherwise. So a mixture is
 * expressible, which it was not.
 *
 * <p>The old shape is still read, because documents exist that use it. It is never written.
 */
class FacetShapeTest {

    private static final String NS = "http://example.org/fs#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));

    private OWLOntology read(String document) throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager().createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            o.getOWLOntologyManager().getOntologyLoaderConfiguration());
        return o;
    }

    private String write(OWLAxiom... axioms) throws Exception {
        OWLOntology o = manager.createOntology();
        for (OWLAxiom axiom : axioms) o.getOWLOntologyManager().addAxiom(o, axiom);
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        StringBuilder body = new StringBuilder();
        for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n", -1)) {
            if (!line.trim().startsWith("#")) body.append(line).append('\n');
        }
        return body.toString();
    }

    /** A range axiom whose range is the given datatype narrowed by the given facets. */
    private OWLAxiom ranged(OWLDatatype base, OWLFacetRestriction... facets) {
        return df.getOWLDataPropertyRangeAxiom(d, df.getOWLDatatypeRestriction(base, List.of(facets)));
    }

    private OWLFacetRestriction facet(OWLFacet f, OWLLiteral v) {
        return df.getOWLFacetRestriction(f, v);
    }

    /** Both spellings read, in the one bracket after the datatype. */
    @Test
    void oneBracketHoldsEitherSpelling() throws Exception {
        OWLDatatype integer = df.getIntegerOWLDatatype();
        assertTrue(read(PREFIX + "⊤ ⊑ ∀d.xsd:integer[≥1 ⊓ ≤40]\n").containsAxiom(
                ranged(integer, facet(OWLFacet.MIN_INCLUSIVE, df.getOWLLiteral(1)),
                                facet(OWLFacet.MAX_INCLUSIVE, df.getOWLLiteral(40)))),
            "operators");
        assertTrue(read(PREFIX + "⊤ ⊑ ∀d.xsd:string[minLength 3]\n").containsAxiom(
                ranged(df.getStringOWLDatatype(),
                       facet(OWLFacet.MIN_LENGTH, df.getOWLLiteral(3)))),
            "a keyword, which the old compact bracket refused");
        assertTrue(read(PREFIX + "⊤ ⊑ ∀d.xsd:string[matches \"[A-Z]{3}\"]\n").containsAxiom(
                ranged(df.getStringOWLDatatype(),
                       facet(OWLFacet.PATTERN, df.getOWLLiteral("[A-Z]{3}")))),
            "a keyword with a quoted value");
    }

    /** And they mix, which neither of the two old shapes allowed. */
    @Test
    void theTwoSpellingsMixInOneBracket() throws Exception {
        assertTrue(read(PREFIX + "⊤ ⊑ ∀d.xsd:integer[≥1 ⊓ totalDigits 3]\n").containsAxiom(
                ranged(df.getIntegerOWLDatatype(),
                       facet(OWLFacet.MIN_INCLUSIVE, df.getOWLLiteral(1)),
                       facet(OWLFacet.TOTAL_DIGITS, df.getOWLLiteral(3)))),
            "an operator and a keyword together");
    }

    /** The old shape still reads, and means the same thing. */
    @Test
    void theOldShapeStillReads() throws Exception {
        assertEquals(
            read(PREFIX + "⊤ ⊑ ∀d.xsd:string[matches \"x\"]\n").getLogicalAxioms(),
            read(PREFIX + "⊤ ⊑ ∀d.[xsd:string ⊓ [matches \"x\"]]\n").getLogicalAxioms(),
            "the bracketed shape is the same axiom");
        assertEquals(
            read(PREFIX + "⊤ ⊑ ∀d.xsd:string[minLength 3 ⊓ maxLength 8]\n").getLogicalAxioms(),
            read(PREFIX + "⊤ ⊑ ∀d.[xsd:string ⊓ [minLength 3] ⊓ [maxLength 8]]\n").getLogicalAxioms(),
            "including with several facets");
    }

    /** It is never written, whichever facets the restriction carries. */
    @Test
    void onlyTheOneShapeIsWritten() throws Exception {
        List<OWLAxiom> shapes = List.of(
            ranged(df.getIntegerOWLDatatype(), facet(OWLFacet.MIN_INCLUSIVE, df.getOWLLiteral(1))),
            ranged(df.getStringOWLDatatype(), facet(OWLFacet.PATTERN, df.getOWLLiteral("x"))),
            ranged(df.getStringOWLDatatype(), facet(OWLFacet.MIN_LENGTH, df.getOWLLiteral(3))),
            ranged(df.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#decimal")),
                   facet(OWLFacet.TOTAL_DIGITS, df.getOWLLiteral(4))));
        for (OWLAxiom axiom : shapes) {
            String written = write(axiom);
            assertFalse(written.contains("[xsd:"),
                () -> "the datatype belongs outside the bracket:\n" + written);
            assertTrue(written.matches("(?s).*∀d\\.xsd:[a-z]+\\[.*"),
                () -> "datatype then one bracket:\n" + written);
            assertTrue(read(written).containsAxiom(axiom),
                () -> "and it must come back:\n" + written);
        }
    }
}
