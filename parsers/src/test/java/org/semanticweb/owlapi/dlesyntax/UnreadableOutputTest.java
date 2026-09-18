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
     * <p>Quoting alone left the value a plain string, which was the typed-literal gap; now
     * that {@code ^^} exists the datatype goes with it and these round-trip exactly.
     */
    @Test
    void aDoubleWithNoBareSpellingIsQuoted() throws Exception {
        for (String special : new String[] {"NaN", "INF", "-INF", "1.0E30", "1.0E-10"}) {
            OWLOntology o = ontology(value(df.getOWLLiteral(special, OWL2Datatype.XSD_DOUBLE)));
            String written = write(o);
            String body = statementsOnly(written);
            assertTrue(body.contains("(a,\"" + special + "\"^^xsd:double):d"),
                () -> special + " must be quoted and typed:\n" + body);

            OWLOntology back = assertDoesNotThrow(() -> read(written),
                () -> special + " must still reload:\n" + body);
            assertTrue(back.containsDataPropertyInSignature(IRI.create(NS + "d")),
                () -> "d must stay a data property: " + back.getLogicalAxioms());
            assertFalse(back.containsIndividualInSignature(IRI.create(NS + special)),
                () -> "and no individual may be invented from the value: "
                    + back.getLogicalAxioms());
            assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
                () -> special + " must come back the literal it was:\n" + body);
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
     * A bound takes the compact form only when the reader can rebuild its datatype.
     *
     * <p>The compact bracket holds a bare {@code NUMBER}, which carries no datatype, so the
     * reader types it from the spelling: digits give {@code xsd:integer}, a decimal point
     * gives {@code xsd:double}. Those two keep the compact form. {@code xsd:int} and
     * {@code xsd:decimal} would be silently retyped by it, so they take the keyword form
     * where the datatype can be written out.
     *
     * <p>Whichever branch is taken, the axiom has to come back unchanged — which is the
     * assertion that actually matters here, and the one the compact form used to fail.
     */
    @Test
    void aBoundKeepsItsDatatypeWhicheverFormIsUsed() throws Exception {
        OWL2Datatype[] compact = {OWL2Datatype.XSD_INTEGER, OWL2Datatype.XSD_DOUBLE};
        OWL2Datatype[] spelledOut = {OWL2Datatype.XSD_INT, OWL2Datatype.XSD_DECIMAL};

        for (OWL2Datatype type : compact) {
            OWLLiteral one = df.getOWLLiteral("1", type);
            OWLOntology o = ontology(restricted(df.getOWLDatatype(type.getIRI()),
                OWLFacet.MIN_INCLUSIVE, one));
            String body = roundTrip(o);
            // The literal's own lexical form, because OWL API normalises some of them:
            // "1"^^xsd:double becomes 1.0 before the writer ever sees it.
            assertTrue(body.contains("[≥" + one.getLiteral() + "]"),
                () -> type + " is rebuilt from its spelling, so it stays compact:\n" + body);
        }
        for (OWL2Datatype type : spelledOut) {
            OWLOntology o = ontology(restricted(df.getOWLDatatype(type.getIRI()),
                OWLFacet.MIN_INCLUSIVE, df.getOWLLiteral("1", type)));
            String body = roundTrip(o);
            assertTrue(body.contains("^^"),
                () -> type + " cannot be rebuilt from a bare number, so it must be"
                    + " written out:\n" + body);
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

    /**
     * A shorthand that only takes a string is not used for an IRI value.
     *
     * <p>{@code @storage} and {@code @doc} take a {@code STRING} in the grammar, but every
     * {@code rdfs:seeAlso} and {@code rdfs:comment} went through them regardless of what the
     * value was — and an IRI value is the commonest use of {@code seeAlso}, pointing at
     * another resource. {@code rdfs:isDefinedBy} has always had this guard.
     */
    @Test
    void anIriValuedShorthandFallsBackToTheGeneralForm() throws Exception {
        String[] properties = {
            "http://www.w3.org/2000/01/rdf-schema#seeAlso",
            "http://www.w3.org/2000/01/rdf-schema#comment",
            "http://www.w3.org/2000/01/rdf-schema#isDefinedBy",
        };
        for (String property : properties) {
            OWLOntology o = ontology(
                df.getOWLAnnotationAssertionAxiom(
                    df.getOWLAnnotationProperty(IRI.create(property)),
                    IRI.create(NS + "C"), IRI.create(NS + "Elsewhere")),
                df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "C")),
                    df.getOWLClass(IRI.create(NS + "D"))));

            String written = write(o);
            String body = statementsOnly(written);
            OWLOntology back = assertDoesNotThrow(() -> read(written),
                () -> property + " with an IRI value must reload:\n" + body);
            assertTrue(back.axioms(AxiomType.ANNOTATION_ASSERTION)
                    .anyMatch(ax -> IRI.create(NS + "Elsewhere").equals(ax.getValue())),
                () -> "and keep the IRI value:\n" + body);
        }
    }

    /** A literal value still uses the shorthand, which is the point of having one. */
    @Test
    void aLiteralValuedShorthandIsUnaffected() throws Exception {
        OWLOntology o = ontology(
            df.getOWLAnnotationAssertionAxiom(df.getRDFSSeeAlso(),
                IRI.create(NS + "C"), df.getOWLLiteral("a note")),
            df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "C")),
                df.getOWLClass(IRI.create(NS + "D"))));
        String body = statementsOnly(write(o));
        assertTrue(body.contains("@storage C \"a note\""),
            () -> "a string value keeps the shorthand:\n" + body);
        roundTrip(o);
    }

    /**
     * A mixed enumeration is refused with something a person can act on.
     *
     * <p>{@code {b, "x"}} is neither an {@code ObjectOneOf} nor a {@code DataOneOf}, and
     * asking for one handed the user a raw {@code ClassCastException} naming two ANTLR
     * context classes, with no line, no column and nothing to do about it: every element was
     * cast to a literal the moment any one of them was.
     */
    @Test
    void aMixedEnumerationIsRefusedClearly() {
        for (String expression : new String[] {
                "A ⊑ {b, \"x\"}", "A ⊑ {\"x\", b}", "A ≡ {b, \"x\", c}"}) {
            Throwable t = assertThrows(Throwable.class,
                () -> read("@prefix : <" + NS + ">\n" + expression + "\n"),
                () -> "expected a refusal for " + expression);
            String message = String.valueOf(t.getMessage());
            assertTrue(message.contains("mixes the individual"),
                () -> expression + " must say what is wrong, got: " + message);
            assertFalse(message.contains("ClassCastException") || message.contains("Context"),
                () -> expression + " must not leak the parser's internals: " + message);
        }
    }

    /**
     * An enumeration of individuals is written as one set, not a union of singletons.
     *
     * <p>The inherited renderer wrote {@code ObjectOneOf(:b :c)} as
     * <code>{b} &sqcup; {c}</code>. That is a fair reading of the semantics and a different
     * axiom: it came back {@code ObjectUnionOf(ObjectOneOf(:b) ObjectOneOf(:c))}, and since
     * each pass wrapped the operands again the text grew a parenthesis level at a time. The
     * class-assertion count never changed, so the regression job could not see any of it.
     */
    @Test
    void anEnumerationOfIndividualsIsWrittenAsOneSet() throws Exception {
        OWLIndividual[] members = {
            df.getOWLNamedIndividual(IRI.create(NS + "b")),
            df.getOWLNamedIndividual(IRI.create(NS + "c")),
        };
        OWLOntology o = ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")), df.getOWLObjectOneOf(members)));

        String body = roundTrip(o);
        assertTrue(body.contains("{b,c}"),
            () -> "one set, not a union of singletons:\n" + body);
        assertFalse(body.contains("⊔"),
            () -> "there is no union in this axiom:\n" + body);
        // Idempotent, which the parenthesis growth was not.
        assertEquals(body, statementsOnly(write(read(write(o)))),
            () -> "and stable on a second pass:\n" + body);
    }

    /** Including when it is nested inside something else. */
    @Test
    void aNestedEnumerationIsAlsoOneSet() throws Exception {
        OWLOntology o = ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "C")),
            df.getOWLObjectUnionOf(
                df.getOWLObjectOneOf(df.getOWLNamedIndividual(IRI.create(NS + "d")),
                                     df.getOWLNamedIndividual(IRI.create(NS + "e"))),
                df.getOWLClass(IRI.create(NS + "F")))));
        assertTrue(roundTrip(o).contains("{d,e}"));
    }

    /** A singleton is still a singleton, and still an enumeration. */
    @Test
    void aSingletonEnumerationSurvives() throws Exception {
        OWLOntology o = ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")),
            df.getOWLObjectOneOf(df.getOWLNamedIndividual(IRI.create(NS + "b")))));
        assertTrue(roundTrip(o).contains("{b}"));
    }

    /** A uniform enumeration of either kind still works. */
    @Test
    void aUniformEnumerationIsUnaffected() throws Exception {
        OWLOntology individuals = read("@prefix : <" + NS + ">\nA ⊑ {b, c}\n");
        assertEquals(1, individuals.getAxioms(AxiomType.SUBCLASS_OF).size(),
            () -> individuals.getLogicalAxioms().toString());

        // The range idiom, which the writer turns into a DataPropertyRange rather than
        // leaving as a subsumption of owl:Thing.
        OWLOntology values = read("@prefix : <" + NS + ">\n⊤ ⊑ ∀d.{\"x\", \"y\"}\n");
        assertEquals(1, values.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
            () -> values.getLogicalAxioms().toString());
        assertTrue(values.getAxioms(AxiomType.DATA_PROPERTY_RANGE).iterator().next()
                .getRange() instanceof OWLDataOneOf,
            () -> "the enumeration must survive as a DataOneOf: "
                + values.getLogicalAxioms());
    }

    /**
     * A disjoint union is written as the two things it says.
     *
     * <p>DL has no notation for it, and the inherited renderer reached for {@code =},
     * producing {@code A=B ⊔ C} — where {@code =} is the cardinality and identity operator,
     * and the line is not a statement at all. The document would not load.
     *
     * <p>Both halves do have notation, so both are written. That is two axioms where there
     * was one, which is the price of a construct DL does not have; the alternative would be
     * inventing a symbol for it.
     */
    @Test
    void aDisjointUnionIsWrittenAsItsTwoHalves() throws Exception {
        OWLClass whole = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass first = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass second = df.getOWLClass(IRI.create(NS + "C"));
        OWLOntology o = ontology(df.getOWLDisjointUnionAxiom(whole,
            java.util.Arrays.asList(first, second)));

        String written = write(o);
        String body = statementsOnly(written);
        assertTrue(body.contains("A ≡ B ⊔ C"), () -> "the union half:\n" + body);
        assertTrue(body.contains("B ⊑ ¬C"), () -> "and the disjointness half:\n" + body);

        OWLOntology back = assertDoesNotThrow(() -> read(written),
            () -> "the document must load:\n" + body);
        assertTrue(back.containsAxiom(df.getOWLEquivalentClassesAxiom(whole,
            df.getOWLObjectUnionOf(first, second))),
            () -> "the union must come back: " + back.getLogicalAxioms());
        assertTrue(back.containsAxiom(df.getOWLDisjointClassesAxiom(first, second)),
            () -> "and the disjointness: " + back.getLogicalAxioms());
    }

    /** With three or more members, every pair is written. */
    @Test
    void aWiderDisjointUnionWritesEveryPair() throws Exception {
        OWLOntology o = ontology(df.getOWLDisjointUnionAxiom(
            df.getOWLClass(IRI.create(NS + "A")),
            java.util.Arrays.asList(df.getOWLClass(IRI.create(NS + "B")),
                df.getOWLClass(IRI.create(NS + "C")),
                df.getOWLClass(IRI.create(NS + "D")))));
        String body = statementsOnly(write(o));
        for (String pair : new String[] {"B ⊑ ¬C", "B ⊑ ¬D", "C ⊑ ¬D"}) {
            assertTrue(body.contains(pair), () -> pair + " missing from:\n" + body);
        }
        assertDoesNotThrow(() -> read(write(o)), () -> body);
    }

    /**
     * A complemented data range is read as one, not as a class.
     *
     * <p>{@code ¬} is as happy over a data range as over a class, and the writer emits it
     * for one — {@code DataComplementOf(xsd:string)} goes out as {@code ¬xsd:string}. The
     * reader built the object form regardless and then failed {@code asClass}, so the
     * document the writer had just produced came back as "expected a class expression".
     *
     * <p>The classifier had to learn the same thing: it stopped at the {@code ¬} and called
     * the property an object property, which then contradicted the document's own range
     * statement.
     */
    @Test
    void aComplementedDataRangeRoundTrips() throws Exception {
        OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
        OWLDatatype string = df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI());
        OWLDataRange[] ranges = {
            df.getOWLDataComplementOf(string),
            df.getOWLDataComplementOf(df.getOWLDataComplementOf(string)),
            df.getOWLDataComplementOf(df.getOWLDataOneOf(df.getOWLLiteral("x"))),
            df.getOWLDataUnionOf(df.getOWLDataComplementOf(string),
                df.getOWLDatatype(OWL2Datatype.XSD_INTEGER.getIRI())),
        };
        for (OWLDataRange range : ranges) {
            OWLOntology o = ontology(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "A")), df.getOWLDataSomeValuesFrom(d, range)));
            roundTrip(o);
        }
        // And in a range axiom, where the property's kind comes from this filler alone.
        roundTrip(ontology(df.getOWLDataPropertyRangeAxiom(d,
            df.getOWLDataComplementOf(string))));
    }

    /**
     * A complement over a connective keeps its brackets, so it keeps its meaning.
     *
     * <p>The class side has always bracketed this; the data side did not. So
     * {@code \u00ac(xsd:integer \u2294 xsd:string)} was written {@code \u00acxsd:integer \u2294
     * xsd:string} and read back as {@code (\u00acxsd:integer) \u2294 xsd:string} — a different
     * data range, silently. The existing complement test above missed it because every
     * operand it uses is a single datatype, a {@code DataOneOf} or a restriction, and those
     * three need no brackets.
     *
     * <p>Both connectives, and the complement on either side of one, in the two positions
     * where the same range reaches a different writer path.
     */
    @Test
    void aComplementOverAConnectiveKeepsItsBrackets() throws Exception {
        OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
        OWLDatatype string = df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI());
        OWLDatatype integer = df.getOWLDatatype(OWL2Datatype.XSD_INTEGER.getIRI());
        OWLDataRange[] ranges = {
            df.getOWLDataComplementOf(df.getOWLDataUnionOf(integer, string)),
            df.getOWLDataComplementOf(df.getOWLDataIntersectionOf(integer, string)),
            df.getOWLDataUnionOf(
                df.getOWLDataComplementOf(df.getOWLDataIntersectionOf(integer, string)),
                string),
            df.getOWLDataIntersectionOf(
                df.getOWLDataComplementOf(df.getOWLDataUnionOf(integer, string)),
                string),
        };
        for (OWLDataRange range : ranges) {
            roundTrip(ontology(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "A")),
                df.getOWLDataSomeValuesFrom(d, range))));
            roundTrip(ontology(df.getOWLDataPropertyRangeAxiom(d, range)));
        }
    }

    /** The object complement is untouched by that. */
    @Test
    void theObjectComplementIsUnaffected() throws Exception {
        roundTrip(ontology(df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")),
            df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(IRI.create(NS + "r")),
                df.getOWLObjectComplementOf(df.getOWLClass(IRI.create(NS + "B")))))));
    }

    /**
     * A datatype definition is written, as the equivalence it is.
     *
     * <p>Nothing was written for one at all, so the axiom and the datatype both vanished.
     * Two things had to change: DL has no separate notation, so {@code ≡} is the spelling —
     * a data range on one side makes it unambiguous, since a class equivalence cannot have
     * one — and the storer had nowhere to put it, because it walks entity blocks and a
     * datatype gets none.
     */
    @Test
    void aDatatypeDefinitionRoundTrips() throws Exception {
        OWLDatatype code = df.getOWLDatatype(IRI.create(NS + "Code"));
        OWLOntology o = ontology(df.getOWLDatatypeDefinitionAxiom(code,
            df.getOWLDatatypeRestriction(
                df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI()),
                df.getOWLFacetRestriction(OWLFacet.MIN_LENGTH, df.getOWLLiteral(3)))));

        String body = roundTrip(o);
        assertTrue(body.contains("Code ≡ "),
            () -> "written as an equivalence:\n" + body);
        assertTrue(read(write(o)).datatypesInSignature()
                .anyMatch(d -> d.getIRI().equals(code.getIRI())),
            () -> "and Code must come back a datatype:\n" + body);
    }

    /**
     * An axiom that belongs to no entity block is still written.
     *
     * <p>The storer walks entities — classes, properties, individuals — and writes each
     * axiom under one of them, so an axiom mentioning none of them had nowhere to go and was
     * dropped in silence. A {@code DifferentIndividuals} over two blank nodes is the
     * clearest case: nothing in it is named.
     */
    @Test
    void anAxiomWithNoEntityBlockIsStillWritten() throws Exception {
        OWLOntology o = ontology(df.getOWLDifferentIndividualsAxiom(
            df.getOWLAnonymousIndividual("_:x"), df.getOWLAnonymousIndividual("_:y")));
        String body = roundTrip(o);
        assertTrue(body.contains("≠"), () -> "the statement must appear:\n" + body);
    }

    /**
     * And that pass must not write the forms the writer deliberately declines.
     *
     * <p>A one-property disjointness renders to nothing on purpose, because {@code Disj(p)}
     * is not a form the grammar has. The orphan pass sees an axiom with no block and could
     * undo that decision; it skips anything that renders empty.
     */
    @Test
    void theOrphanPassRespectsADeclinedForm() throws Exception {
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLOntology o = ontology(df.getOWLDisjointObjectPropertiesAxiom(p, p));
        String body = statementsOnly(write(o));
        assertFalse(body.contains("Disj("),
            () -> "a one-property disjointness has no spelling:\n" + body);
        assertDoesNotThrow(() -> read(write(o)), () -> body);
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

    /**
     * An entity the document only declares is still written.
     *
     * <p>Such an entity appears in no other axiom, so nothing the reader does can recover its
     * kind, and every rule that decides whether to state a kind is about correcting a reading
     * rather than supplying one. With no statement, nothing about it was written at all and
     * the declaration was lost: of the seven declaration-only shapes only a data property and
     * a capitalised object property survived, and those two by accident of another rule.
     *
     * <p>Three shapes are still lost, and cannot be fixed here: a named individual, an
     * annotation property and a datatype have no DLe form that states their kind. They are
     * left out of this test deliberately rather than silently.
     */
    @Test
    void aDeclarationOnlyEntityIsStillWritten() throws Exception {
        OWLEntity[] declared = {
            df.getOWLObjectProperty(IRI.create(NS + "Upper")),
            df.getOWLObjectProperty(IRI.create(NS + "r")),
            df.getOWLDataProperty(IRI.create(NS + "d")),
            df.getOWLClass(IRI.create(NS + "Solo")),
        };
        for (OWLEntity entity : declared) {
            OWLOntology o = ontology(df.getOWLDeclarationAxiom(entity));
            manager.addAxiom(o, df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "A")), df.getOWLClass(IRI.create(NS + "B"))));
            String written = write(o);
            OWLOntology back = read(written);
            assertTrue(back.containsEntityInSignature(entity),
                () -> entity.getEntityType() + " " + entity.getIRI().getRemainder().orElse("?")
                    + " was declared and then written nowhere:\n" + written);
        }
    }

    /**
     * An annotation property's domain and range are written, being non-logical axioms.
     *
     * <p>{@code writeAxiomsWithNoBlock} filtered {@code logicalAxioms()}, and these are not
     * logical axioms, so this pass never saw them and the renderer's working visit methods for
     * them were never reached. A document whose only content was an
     * {@code AnnotationPropertyDomain} came out empty, at exit 0.
     */
    @Test
    void anAnnotationPropertysDomainAndRangeAreWritten() throws Exception {
        OWLAnnotationProperty ap = df.getOWLAnnotationProperty(IRI.create(NS + "ap"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLAxiom[] axioms = {
            df.getOWLAnnotationPropertyDomainAxiom(ap, c.getIRI()),
            df.getOWLAnnotationPropertyRangeAxiom(ap, c.getIRI()),
        };
        for (OWLAxiom axiom : axioms) {
            OWLOntology o = ontology(axiom);
            manager.addAxiom(o, df.getOWLDeclarationAxiom(c));
            String written = write(o);
            assertTrue(read(written).containsAxiom(axiom),
                () -> axiom.getAxiomType() + " did not survive:\n" + written);
        }
    }

    /**
     * An alias between two datatypes the document defines keeps its direction.
     *
     * <p>The reader took the defined name from the *right*-hand operand, which was invisible
     * while one side was always a built-in: the guard that rejects a built-in as the defined
     * name meant the correct side won by elimination. With a datatype on both sides nothing
     * eliminated anything, so `T2 \u2261 T` was read as {@code DatatypeDefinition(:T :T2)},
     * which writes as `T \u2261 T2`, which reads as {@code DatatypeDefinition(:T2 :T)}. The
     * document alternated between two forms forever and never reached a fixed point.
     *
     * <p>Idempotency is the assertion that matters here, so it is made explicitly rather than
     * through {@code roundTrip}: the axiom sets of the two forms differ, so a single round
     * trip would have caught this, but only a second pass shows it never settles.
     */
    @Test
    void anAliasBetweenTwoDefinedDatatypesKeepsItsDirection() throws Exception {
        OWLDatatype t = df.getOWLDatatype(IRI.create(NS + "T"));
        OWLDatatype t2 = df.getOWLDatatype(IRI.create(NS + "T2"));
        OWLAxiom base = df.getOWLDatatypeDefinitionAxiom(t,
            df.getOWLDatatypeRestriction(
                df.getOWLDatatype(OWL2Datatype.XSD_INTEGER.getIRI()),
                df.getOWLFacetRestriction(org.semanticweb.owlapi.vocab.OWLFacet.MIN_INCLUSIVE,
                    df.getOWLLiteral(1))));
        OWLAxiom alias = df.getOWLDatatypeDefinitionAxiom(t2, t);
        OWLOntology o = ontology(base);
        manager.addAxiom(o, alias);

        String first = write(o);
        assertTrue(read(first).containsAxiom(alias),
            () -> "the alias must keep the direction it was written in:\n" + first);

        String second = write(read(first));
        String third = write(read(second));
        assertEquals(second, third,
            () -> "the document must settle, not alternate:\nsecond:\n" + second
                + "\nthird:\n" + third);
    }

    /**
     * A datatype that is only declared keeps its kind, and everything ranged on it with it.
     *
     * <p>The writer decides from the axiom type and the reader from whether the filler looks
     * like a data range, which for a name the document neither defines nor takes from a
     * built-in namespace it does not. So {@code DataPropertyRange(:d :T)} was written
     * {@code ⊤ ⊑ ∀d.T} and read back as an {@code ObjectPropertyRange} with {@code T} a
     * class: both kinds lost at once, silently, and the same for every shape that can hold a
     * data range. Inside a connective it was worse — the reader refused its own output.
     *
     * <p>Fixed by writing the kind: {@code T ⊑ rdfs:Literal}, the datatype counterpart of
     * {@code C ⊑ ⊤} and {@code r ⊑ owl:topObjectProperty}.
     */
    @Test
    void aDeclarationOnlyDatatypeKeepsItsKind() throws Exception {
        OWLDatatype t = df.getOWLDatatype(IRI.create(NS + "T"));
        OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLDatatype string = df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI());
        OWLAxiom[] axioms = {
            df.getOWLDataPropertyRangeAxiom(d, t),
            df.getOWLSubClassOfAxiom(a, df.getOWLDataSomeValuesFrom(d, t)),
            df.getOWLSubClassOfAxiom(a, df.getOWLDataAllValuesFrom(d, t)),
            df.getOWLSubClassOfAxiom(a, df.getOWLDataMinCardinality(2, d, t)),
            // The loud one: inside a connective the reader refused the writer's own output.
            df.getOWLSubClassOfAxiom(a,
                df.getOWLDataSomeValuesFrom(d, df.getOWLDataUnionOf(t, string))),
        };
        for (OWLAxiom axiom : axioms) {
            OWLOntology o = ontology(axiom);
            manager.addAxiom(o, df.getOWLDeclarationAxiom(t));
            String written = write(o);
            OWLOntology back = read(written);
            assertTrue(back.containsAxiom(axiom),
                () -> axiom + "\ndid not survive. written:\n" + written
                    + "\nback: " + back.getLogicalAxioms());
            assertTrue(back.containsDatatypeInSignature(t.getIRI()),
                () -> "T must still be a datatype:\n" + written);
            assertFalse(back.containsClassInSignature(t.getIRI()),
                () -> "and never a class:\n" + written);
        }
    }

    /** A datatype the document defines needs no marker: its definition already says so. */
    @Test
    void aDefinedDatatypeNeedsNoMarker() throws Exception {
        OWLDatatype t = df.getOWLDatatype(IRI.create(NS + "T"));
        OWLOntology o = ontology(df.getOWLDatatypeDefinitionAxiom(t,
            df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI())));
        String written = write(o);
        assertFalse(statementsOnly(written).contains("rdfs:Literal"),
            () -> "the definition is enough on its own:\n" + statementsOnly(written));
    }
}
