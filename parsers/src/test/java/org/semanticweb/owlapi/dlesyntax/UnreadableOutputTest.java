package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLFacet;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What DLe writes, DLe must be able to read.
 *
 * <p>Every case here is an ontology the writer turned into a document that either would not
 * reload or came back meaning something else. They share one cause: a value or a form was
 * written on the strength of its OWL type without checking that the grammar has a spelling
 * for it. Testing "it parses" would have caught half of them; the other half need the axioms
 * compared before and after, because the document loaded perfectly and said something else.
 */
class UnreadableOutputTest {

    private static final String NS = "http://example.org/u#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology ontology(OWLAxiom... axioms) throws Exception {
        OWLOntology o = manager.createOntology();
        for (OWLAxiom axiom : axioms) manager.addAxiom(o, axiom);
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Writes, reads back, and returns the document so a failure can show it. */
    private String roundTrip(OWLOntology o) throws Exception {
        String written = write(o);
        String body = statementsOnly(written);
        OWLOntology back = assertDoesNotThrow(() -> read(written),
            () -> "the written document must reload:\n" + body);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "and must mean the same thing:\n" + body);
        return body;
    }

    private OWLDataPropertyAssertionAxiom value(OWLLiteral literal) {
        return df.getOWLDataPropertyAssertionAxiom(
            df.getOWLDataProperty(IRI.create(NS + "d")),
            df.getOWLNamedIndividual(IRI.create(NS + "a")), literal);
    }

    // ── Numbers that cannot be spelled as NUMBER ────────────────────────────

    /**
     * An integer too large for an {@code int} keeps its value.
     *
     * <p>{@code xsd:integer} is unbounded and DLe wrote the value out in full, but the reader
     * called {@code Integer.parseInt} on the way back in, so DLe could not read its own
     * output — with a bare {@code For input string: "3000000000"} and no line or column.
     * Millisecond timestamps and identifiers held as data both reach this size.
     */
    @Test
    void anIntegerLargerThanAnIntSurvives() throws Exception {
        for (String big : new String[] {
                "3000000000", "-3000000000", "99999999999999999999", "2147483648"}) {
            OWLLiteral literal = df.getOWLLiteral(big, OWL2Datatype.XSD_INTEGER);
            String body = roundTrip(ontology(value(literal)));
            assertTrue(body.contains("(a," + big + "):d"),
                () -> big + " is a NUMBER, so it stays bare:\n" + body);
        }
    }

    /**
     * A double DLe has no bare spelling for is quoted, not written bare.
     *
     * <p>{@code NaN} is the one that mattered. It was written {@code (a,NaN):d}, which is a
     * perfectly good document saying something else entirely — an <em>object</em> property
     * assertion against an invented individual {@code :NaN}. That is the same corruption as
     * an unquoted string, from the same cause, and it survived the fix for the string case
     * because the numeric branch was never guarded.
     *
     * <p>Quoting loses the datatype, which is the separate typed-literal gap (#24). It does
     * not lose the property's kind, the individual, or the document.
     */
    @Test
    void aDoubleWithNoBareSpellingIsQuoted() throws Exception {
        for (String special : new String[] {"NaN", "INF", "-INF", "1.0E30", "1.0E-10"}) {
            OWLOntology o = ontology(value(df.getOWLLiteral(special, OWL2Datatype.XSD_DOUBLE)));
            String written = write(o);
            String body = statementsOnly(written);
            assertTrue(body.contains("(a,\"" + special + "\"):d"),
                () -> special + " must be quoted:\n" + body);

            OWLOntology back = assertDoesNotThrow(() -> read(written),
                () -> special + " must still reload:\n" + body);
            assertTrue(back.containsDataPropertyInSignature(IRI.create(NS + "d")),
                () -> "d must stay a data property: " + back.getLogicalAxioms());
            assertFalse(back.containsIndividualInSignature(IRI.create(NS + special)),
                () -> "and no individual may be invented from the value: "
                    + back.getLogicalAxioms());
        }
    }

    /** The ordinary numbers must still be written bare, or this fix has cost more than it. */
    @Test
    void ordinaryNumbersAreStillBare() throws Exception {
        assertTrue(roundTrip(ontology(value(df.getOWLLiteral(7)))).contains("(a,7):d"));
        assertTrue(roundTrip(ontology(value(df.getOWLLiteral(-3)))).contains("(a,-3):d"));
        assertTrue(roundTrip(ontology(value(df.getOWLLiteral(1.5d)))).contains("(a,1.5):d"));
        assertTrue(roundTrip(ontology(value(df.getOWLLiteral(true)))).contains("(a,true):d"));
    }

    // ── Facets ──────────────────────────────────────────────────────────────

    private OWLAxiom restricted(OWLDatatype base, OWLFacet facet, OWLLiteral facetValue) {
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "A")),
            df.getOWLDataSomeValuesFrom(df.getOWLDataProperty(IRI.create(NS + "d")),
                df.getOWLDatatypeRestriction(base,
                    df.getOWLFacetRestriction(facet, facetValue))));
    }

    /**
     * A digit-count facet takes the keyword form, because it has no compact symbol.
     *
     * <p>It was routed down the compact path anyway, where the symbol lookup fell through to
     * the facet's short form and the value was appended with nothing between them:
     * {@code [totalDigits5]}, which is not a token the grammar has.
     */
    @Test
    void aDigitCountFacetIsWrittenInTheKeywordForm() throws Exception {
        for (OWLFacet facet : new OWLFacet[] {OWLFacet.TOTAL_DIGITS, OWLFacet.FRACTION_DIGITS}) {
            OWLOntology o = ontology(restricted(df.getOWLDatatype(OWL2Datatype.XSD_DECIMAL.getIRI()),
                facet, df.getOWLLiteral(5)));
            String body = roundTrip(o);
            assertTrue(body.contains("[" + facet.getShortForm() + " 5]"),
                () -> "the keyword and its value need a space between them:\n" + body);
        }
    }

    /**
     * An ordered bound whose value is not a number takes the keyword form too.
     *
     * <p>The compact bracket carries a {@code NUMBER}, and the branch was chosen by the
     * facet's name alone, so a date bound was written {@code [≥2024-01-01]} — which is
     * not one. Date ranges are the commonest real use of these facets.
     */
    @Test
    void anOrderedBoundOnANonNumericValueIsWrittenInTheKeywordForm() throws Exception {
        // xsd:date is not in OWL 2's datatype map and so not in OWL2Datatype, which is
        // part of the point: an ordered bound is not restricted to the numeric types.
        OWLDatatype date = df.getOWLDatatype(
            IRI.create("http://www.w3.org/2001/XMLSchema#date"));
        OWLOntology o = ontology(restricted(date, OWLFacet.MIN_INCLUSIVE,
            df.getOWLLiteral("2024-01-01", date)));
        String body = statementsOnly(write(o));
        assertFalse(body.contains("≥2024"),
            () -> "a date cannot go in the compact bracket:\n" + body);
        assertDoesNotThrow(() -> read(write(o)), () -> "and it must reload:\n" + body);
    }

    /**
     * A numeric bound still takes the compact form, whatever its datatype is called.
     *
     * <p>The guard is on the spelling, not the datatype: {@code xsd:int} and
     * {@code xsd:decimal} answer {@code false} to {@code isInteger()} and {@code isDouble()},
     * so testing the datatype here would have quoted their values inside the compact
     * bracket — {@code [≥"1"]} — and broken the documents this fix was meant to save.
     */
    @Test
    void aNumericBoundIsStillCompact() throws Exception {
        for (OWL2Datatype type : new OWL2Datatype[] {OWL2Datatype.XSD_INTEGER,
                OWL2Datatype.XSD_INT, OWL2Datatype.XSD_DECIMAL, OWL2Datatype.XSD_DOUBLE}) {
            OWLLiteral one = df.getOWLLiteral("1", type);
            OWLOntology o = ontology(restricted(df.getOWLDatatype(type.getIRI()),
                OWLFacet.MIN_INCLUSIVE, one));
            String body = statementsOnly(write(o));
            // The literal's own lexical form, because OWL API normalises some of them:
            // "1"^^xsd:double becomes 1.0 before the writer ever sees it.
            assertTrue(body.contains("[≥" + one.getLiteral() + "]"),
                () -> type + " must stay compact and unquoted:\n" + body);
            assertDoesNotThrow(() -> read(write(o)), () -> body);
        }
    }

    // ── A disjointness of one property ──────────────────────────────────────

    /**
     * A degenerate disjointness is not written, because {@code Disj(p)} is not a form.
     *
     * <p>OWL API accepts {@code DisjointObjectProperties(:p :p)} and collapses the pair, so a
     * one-property axiom arrives from every non-DLe input. The reader was taught to refuse
     * the two DLe spellings that build one, but the writer went on emitting it, and the whole
     * document then failed to load — on a syntax error pointing at the next statement.
     */
    @Test
    void aDisjointnessOfOnePropertyIsNotWritten() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLOntology o = ontology(
            df.getOWLDisjointObjectPropertiesAxiom(p, p),
            df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "A")),
                df.getOWLObjectSomeValuesFrom(p, df.getOWLClass(IRI.create(NS + "B")))));

        String body = statementsOnly(write(o));
        assertFalse(body.contains("Disj("),
            () -> "a one-property disjointness says nothing and has no spelling:\n" + body);
        assertDoesNotThrow(() -> read(write(o)),
            () -> "and the rest of the document must still load:\n" + body);
    }

    /** A real disjointness is still written, or the guard has swallowed the feature. */
    @Test
    void arealDisjointnessIsStillWritten() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create(NS + "q"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLOntology o = ontology(
            df.getOWLDisjointObjectPropertiesAxiom(p, q),
            df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(p, b)),
            df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(q, b)));
        assertTrue(roundTrip(o).contains("Disj(p, q)"));
    }

    // ── Kind evidence ───────────────────────────────────────────────────────

    /**
     * An unqualified data cardinality does not settle the kind, so a statement is written.
     *
     * <p>{@code ≥2 d} has no filler, so it is spelled exactly like the object form and
     * says only "this is a role". It was treated as data evidence, no kind statement was
     * written, and the property came back an object property — silently, and stably. The
     * interaction is contagious: a {@code Func(d)} beside it flipped as well.
     */
    @Test
    void anUnqualifiedDataCardinalityDoesNotSettleTheKind() throws Exception {
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
        OWLClassExpression[] restrictions = {
            df.getOWLDataMinCardinality(2, d),
            df.getOWLDataMaxCardinality(3, d),
            df.getOWLDataExactCardinality(2, d),
        };
        for (OWLClassExpression restriction : restrictions) {
            OWLOntology o = ontology(df.getOWLSubClassOfAxiom(a, restriction));
            String body = roundTrip(o);
            assertTrue(body.contains("d ⊑ owl:topDataProperty"),
                () -> restriction + " needs the kind said out loud:\n" + body);
        }
        // And with a characteristic beside it, which used to flip too.
        OWLOntology both = ontology(
            df.getOWLSubClassOfAxiom(a, df.getOWLDataMinCardinality(2, d)),
            df.getOWLFunctionalDataPropertyAxiom(d));
        roundTrip(both);
    }

    /** A qualified one does settle it, and must not gain a redundant statement. */
    @Test
    void aQualifiedDataCardinalityStillSettlesTheKind() throws Exception {
        OWLOntology o = ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")),
            df.getOWLDataMinCardinality(2, df.getOWLDataProperty(IRI.create(NS + "d")),
                df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI()))));
        String body = roundTrip(o);
        assertFalse(body.contains("owl:topDataProperty"),
            () -> "the datatype already says which kind it is:\n" + body);
    }

    /** An object cardinality is untouched by any of this. */
    @Test
    void anObjectCardinalityIsUnaffected() throws Exception {
        roundTrip(ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")),
            df.getOWLObjectMinCardinality(2, df.getOWLObjectProperty(IRI.create(NS + "r"))))));
    }

    // ── The @db suppression ─────────────────────────────────────────────────

    /**
     * A tagged {@code @db} value is not suppressed as redundant.
     *
     * <p>{@code @label} had exactly this bug and exactly this fix in the same commit; the
     * identical suppression a few lines below it was left comparing lexical forms. A tagged
     * literal is a different literal, and the reader regenerates an untagged one.
     */
    @Test
    void aTaggedIsDefinedByValueSurvives() throws Exception {
        IRI subject = IRI.create(NS + "Thing1");
        OWLOntology o = ontology(
            df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(IRI.create(
                    "http://www.w3.org/2000/01/rdf-schema#isDefinedBy")),
                subject, df.getOWLLiteral("Thing1", "en")),
            df.getOWLSubClassOfAxiom(df.getOWLClass(subject),
                df.getOWLClass(IRI.create(NS + "B"))));

        String body = statementsOnly(write(o));
        assertTrue(body.contains("\"Thing1\"@en"),
            () -> "the tag must reach the document:\n" + body);
        assertEquals(o.getAxioms(AxiomType.ANNOTATION_ASSERTION),
            read(write(o)).getAxioms(AxiomType.ANNOTATION_ASSERTION).stream()
                .filter(ax -> !ax.getProperty().isLabel())
                .collect(java.util.stream.Collectors.toSet()),
            () -> "and must come back unchanged:\n" + body);
    }

    /** The untagged one is still suppressed, since the reader puts it back. */
    @Test
    void anUntaggedIsDefinedByValueIsStillSuppressed() throws Exception {
        IRI subject = IRI.create(NS + "Thing1");
        OWLOntology o = ontology(
            df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(IRI.create(
                    "http://www.w3.org/2000/01/rdf-schema#isDefinedBy")),
                subject, df.getOWLLiteral("Thing1")),
            df.getOWLSubClassOfAxiom(df.getOWLClass(subject),
                df.getOWLClass(IRI.create(NS + "B"))));

        String body = statementsOnly(write(o));
        assertTrue(body.contains("@db Thing1\n"),
            () -> "a redundant value is left out:\n" + body);
    }
}
