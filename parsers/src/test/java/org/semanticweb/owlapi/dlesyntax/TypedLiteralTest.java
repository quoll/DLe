package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Typed literals, as Turtle spells them: {@code "2024-01-01"^^xsd:date}.
 *
 * <p>There was no syntax for a datatype, so every typed value became a plain string. Two
 * dozen XSD datatypes collapsed to {@code xsd:string} in silence, a user-declared datatype
 * could not survive at all, and the doubles that have no bare spelling — {@code NaN},
 * {@code INF}, an exponent — lost their type on the way through.
 *
 * <p>{@code xsd:string} is implicit and never written: a plain string already means it, and
 * emitting it would change every existing document to no purpose. It is accepted when
 * spelled out, because a generator may produce it.
 *
 * <p>A datatype and a language tag cannot both appear. A tagged string is
 * {@code rdf:langString} by definition, so a datatype beside the tag either repeats it or
 * contradicts it.
 */
class TypedLiteralTest {

    private static final String NS = "http://example.org/t#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology withValue(OWLLiteral value) throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(
            df.getOWLDataProperty(IRI.create(NS + "d")),
            df.getOWLNamedIndividual(IRI.create(NS + "a")), value));
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

    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private OWLDatatype xsd(String local) {
        return df.getOWLDatatype(IRI.create(XSD + local));
    }

    /**
     * Every datatype that used to collapse now survives.
     *
     * <p>Named individually rather than sampled, because the defect was uniform: any
     * datatype outside the handful with a bare spelling became {@code xsd:string}.
     */
    @Test
    void everyTypedValueRoundTrips() throws Exception {
        String[] datatypes = {
            "date", "dateTime", "time", "decimal", "long", "short", "byte", "int",
            "nonNegativeInteger", "positiveInteger", "unsignedInt", "anyURI", "token",
            "NCName", "hexBinary", "base64Binary", "normalizedString", "language", "Name",
            "NMTOKEN", "duration", "gYear",
        };
        for (String local : datatypes) {
            OWLLiteral value = df.getOWLLiteral("v", xsd(local));
            OWLOntology o = withValue(value);
            String written = write(o);
            assertTrue(bodyOf(written).contains("\"v\"^^xsd:" + local),
                () -> local + " must be written out:\n" + bodyOf(written));
            assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
                () -> local + " must come back unchanged:\n" + bodyOf(written));
        }
    }

    /** The OWL-namespace datatypes, which have no XSD equivalent. */
    @Test
    void theOwlDatatypesRoundTrip() throws Exception {
        for (String iri : new String[] {"http://www.w3.org/2002/07/owl#rational",
                                        "http://www.w3.org/2002/07/owl#real"}) {
            OWLOntology o = withValue(df.getOWLLiteral("1/3",
                df.getOWLDatatype(IRI.create(iri))));
            String written = write(o);
            assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
                () -> iri + ":\n" + bodyOf(written));
        }
    }

    /** A datatype the document declares itself, which no built-in list could know. */
    @Test
    void aUserDeclaredDatatypeRoundTrips() throws Exception {
        OWLOntology o = withValue(df.getOWLLiteral("abc",
            df.getOWLDatatype(IRI.create(NS + "Code"))));
        String written = write(o);
        assertTrue(bodyOf(written).contains("^^Code"),
            () -> "the datatype must be named:\n" + bodyOf(written));
        assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
            () -> bodyOf(written));
    }

    /** The doubles with no bare spelling keep their type now, not just their text. */
    @Test
    void theSpecialDoublesKeepTheirType() throws Exception {
        for (String special : new String[] {"NaN", "INF", "-INF", "1.0E30"}) {
            OWLOntology o = withValue(df.getOWLLiteral(special, xsd("double")));
            String written = write(o);
            assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
                () -> special + ":\n" + bodyOf(written));
        }
    }

    // ── xsd:string is implicit ──────────────────────────────────────────────

    /** A plain string is written plain, because that is what it means. */
    @Test
    void aPlainStringGainsNoDatatype() throws Exception {
        String body = bodyOf(write(withValue(df.getOWLLiteral("hello"))));
        assertTrue(body.contains("(a,\"hello\"):d"), () -> body);
        assertFalse(body.contains("^^"),
            () -> "xsd:string is implicit and must not be written:\n" + body);
    }

    /** And an explicitly typed string is written plain too, being the same literal. */
    @Test
    void anExplicitXsdStringIsWrittenPlain() throws Exception {
        OWLOntology o = withValue(df.getOWLLiteral("hello", xsd("string")));
        String body = bodyOf(write(o));
        assertFalse(body.contains("^^"), () -> body);
        assertEquals(o.getLogicalAxioms(), read(write(o)).getLogicalAxioms(), () -> body);
    }

    /** But it is accepted when written out, since a generator may produce it. */
    @Test
    void anExplicitXsdStringIsAccepted() throws Exception {
        OWLOntology o = read(PREFIX + "(a,\"hello\"^^xsd:string):d\n");
        assertEquals(df.getOWLLiteral("hello"),
            o.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION).iterator().next().getObject(),
            () -> "the same literal as a bare string: " + o.getLogicalAxioms());
    }

    // ── A tag and a datatype are exclusive ──────────────────────────────────

    /** Both at once is refused, rather than one of them being dropped. */
    @Test
    void aTagAndADatatypeTogetherAreRefused() {
        Throwable t = assertThrows(Throwable.class,
            () -> read(PREFIX + "(a,\"x\"@fr^^xsd:date):d\n"));
        assertTrue(String.valueOf(t.getMessage())
                .contains("cannot carry both a language tag and a datatype"),
            () -> "got: " + t.getMessage());
    }

    /** A tag alone still works, and is still not confused for a datatype. */
    @Test
    void aTagAloneIsUnaffected() throws Exception {
        OWLOntology o = withValue(df.getOWLLiteral("bonjour", "fr"));
        String body = bodyOf(write(o));
        assertTrue(body.contains("\"bonjour\"@fr"), () -> body);
        assertFalse(body.contains("^^"),
            () -> "a tagged literal is rdf:langString and says so with the tag:\n" + body);
        assertEquals(o.getLogicalAxioms(), read(write(o)).getLogicalAxioms(), () -> body);
    }

    /**
     * A facet value may be typed, and a language is still refused there.
     *
     * <p>The refusal used to look at everything after the closing quote, so once {@code ^^}
     * existed it reported a language tag of {@code ^xsd:date} and rejected a legitimate
     * typed bound.
     */
    @Test
    void aFacetValueMayBeTyped() throws Exception {
        OWLOntology o = read(PREFIX
            + "A ⊑ ∃d.[xsd:date ⊓ [min \"2024-01-01\"^^xsd:date]]\n");
        assertEquals(1, o.getAxioms(AxiomType.SUBCLASS_OF).size(),
            () -> o.getLogicalAxioms().toString());
        // The bound's own datatype, which is what this test is named for and never checked:
        // throwing it away in buildFacetLiteral failed one test in the whole suite, and that
        // one was in another file. A count cannot see a retyped literal.
        assertTrue(o.axioms(AxiomType.SUBCLASS_OF)
                .map(OWLSubClassOfAxiom::getSuperClass)
                .filter(ce -> ce instanceof OWLDataSomeValuesFrom)
                .map(ce -> ((OWLDataSomeValuesFrom) ce).getFiller())
                .filter(r -> r instanceof OWLDatatypeRestriction)
                .flatMap(r -> ((OWLDatatypeRestriction) r).facetRestrictions())
                .anyMatch(fr -> (XSD + "date")
                    .equals(fr.getFacetValue().getDatatype().getIRI().toString())),
            () -> "the bound must keep xsd:date: " + o.getLogicalAxioms());

        Throwable t = assertThrows(Throwable.class, () -> read(PREFIX
            + "A ⊑ ∃d.[xsd:string ⊓ [matches \"[A-Z]{3}\"@en]]\n"));
        assertTrue(String.valueOf(t.getMessage()).contains("a facet value cannot carry a language tag"),
            () -> "got: " + t.getMessage());
    }

    /** Numbers and booleans stay bare: they already say what they are. */
    @Test
    void numbersAndBooleansAreStillBare() throws Exception {
        assertTrue(bodyOf(write(withValue(df.getOWLLiteral(7)))).contains("(a,7):d"));
        assertTrue(bodyOf(write(withValue(df.getOWLLiteral(1.5d)))).contains("(a,1.5):d"));
        assertTrue(bodyOf(write(withValue(df.getOWLLiteral(true)))).contains("(a,true):d"));
        for (OWLLiteral value : new OWLLiteral[] {df.getOWLLiteral(7),
                df.getOWLLiteral(1.5d), df.getOWLLiteral(true)}) {
            OWLOntology o = withValue(value);
            assertEquals(o.getLogicalAxioms(), read(write(o)).getLogicalAxioms(),
                () -> String.valueOf(value));
        }
    }

    /**
     * An ordinary {@code xsd:float} keeps its type.
     *
     * <p>{@code renderLiteral} restated the bare-spelling rule instead of asking the method
     * that knows it, and included {@code xsd:float} in the restatement. A bare decimal comes
     * back {@code xsd:double}, so every float whose canonical form is an ordinary number —
     * 0, 5, -5, 0.1, 1.5, 123.456, -0.0 — was silently retyped. Only the spellings that must
     * be quoted anyway (NaN, INF, an exponent) escaped, which is why the existing test for
     * "the special doubles" passed throughout.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "float {0} keeps its type")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "0", "5", "-5", "0.1", "0.5", "1.5", "123.456", "-0.0"
    })
    void anOrdinaryFloatKeepsItsType(String spelling) throws Exception {
        OWLLiteral value =
            df.getOWLLiteral(spelling, df.getOWLDatatype(IRI.create(XSD + "float")));
        OWLOntology back = read(write(withValue(value)));
        OWLLiteral got = back.axioms(AxiomType.DATA_PROPERTY_ASSERTION)
            .map(OWLDataPropertyAssertionAxiom::getObject).findFirst().orElseThrow();
        assertEquals(XSD + "float", got.getDatatype().getIRI().toString(),
            () -> "xsd:float " + spelling + " came back as "
                + got.getDatatype().getIRI().getRemainder().orElse("?"));
    }

    /**
     * A {@code xsd:double} with no decimal point keeps its type too.
     *
     * <p>The same restatement let this through bare, and a bare digit string reads back as
     * {@code xsd:integer}. The guarded rule asks whether the spelling reconstructs the
     * datatype, which for a dotless double it does not.
     */
    @Test
    void aDoubleWithNoPointKeepsItsType() throws Exception {
        OWLLiteral value = df.getOWLLiteral("5", df.getOWLDatatype(IRI.create(XSD + "double")));
        String written = bodyOf(write(withValue(value)));
        OWLOntology back = read(write(withValue(value)));
        OWLLiteral got = back.axioms(AxiomType.DATA_PROPERTY_ASSERTION)
            .map(OWLDataPropertyAssertionAxiom::getObject).findFirst().orElseThrow();
        assertEquals(XSD + "double", got.getDatatype().getIRI().toString(),
            () -> "a dotless xsd:double came back as "
                + got.getDatatype().getIRI().getRemainder().orElse("?") + ", written as: "
                + written);
    }

    /**
     * An enumeration of numerically equal values in different datatypes keeps every member.
     *
     * <p>Retyping collapsed members together: a five-member {@code DataOneOf} mixing float
     * and double came back with four, because the two had become the same literal. A count
     * is the whole point here — the loss is invisible in any single member.
     */
    @Test
    void aMixedNumericEnumerationKeepsEveryMember() throws Exception {
        OWLDataRange oneOf = df.getOWLDataOneOf(
            df.getOWLLiteral("1", df.getOWLDatatype(IRI.create(XSD + "decimal"))),
            df.getOWLLiteral("1.0", df.getOWLDatatype(IRI.create(XSD + "double"))),
            df.getOWLLiteral("1.0", df.getOWLDatatype(IRI.create(XSD + "float"))),
            df.getOWLLiteral("1", df.getOWLDatatype(IRI.create(XSD + "integer"))),
            df.getOWLLiteral("1"));
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLDataPropertyRangeAxiom(
            df.getOWLDataProperty(IRI.create(NS + "d")), oneOf));
        OWLOntology back = read(write(o));
        long members = back.axioms(AxiomType.DATA_PROPERTY_RANGE)
            .map(OWLDataPropertyRangeAxiom::getRange)
            .filter(r -> r instanceof OWLDataOneOf)
            .flatMap(r -> ((OWLDataOneOf) r).values())
            .count();
        assertEquals(5, members,
            "every member of the enumeration must survive as a distinct literal");
    }

    /**
     * A typed or tagged {@code rdf:value} keeps what it carries.
     *
     * <p>The {@code ≝} form writes the literal's text and nothing else — there is no room
     * after it for {@code @en} or {@code ^^xsd:token} — so a tagged or typed
     * {@code rdf:value} was silently reduced to a plain string. It now goes through the
     * general {@code @ann} form, which carries both.
     */
    @Test
    void aTypedPredicateBodyKeepsItsDatatype() throws Exception {
        OWLOntology o = manager.createOntology();
        IRI rdfValue = IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");
        for (OWLLiteral value : new OWLLiteral[] {
                df.getOWLLiteral("u,v \u2192 u > v",
                    df.getOWLDatatype(IRI.create(XSD + "token"))),
                df.getOWLLiteral("u,v \u2192 u > v", "en")}) {
            OWLAxiom axiom = df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(rdfValue), IRI.create(NS + "p"), value);
            OWLOntology one = manager.createOntology();
            manager.addAxiom(one, axiom);
            String written = write(one);
            assertTrue(read(written).containsAxiom(axiom),
                () -> "the predicate body must keep what it carries: " + value
                    + "\n" + written);
        }
    }

    /** A plain predicate definition still uses the compact form. */
    @Test
    void aPlainPredicateBodyStillUsesTheCompactForm() throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(
                IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#value")),
            IRI.create(NS + "p"), df.getOWLLiteral("u,v \u2192 u > v")));
        String body = bodyOf(write(o));
        assertTrue(body.contains("\u225d"),
            () -> "a plain body is still written with \u225d:\n" + body);
    }
}
