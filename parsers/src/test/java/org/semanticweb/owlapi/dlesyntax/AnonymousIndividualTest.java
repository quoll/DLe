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
 * Anonymous individuals, spelled {@code _:x} as every RDF syntax spells them.
 *
 * <p>The writer emitted {@code _:genid2147483648 : A} and the reader answered "unknown
 * prefix '_:'", so any ontology with a blank node produced a document it could not read
 * back. No grammar change was needed for it: {@code _} is a {@code NameStart}, so
 * {@code _:x} already lexed as an ordinary prefixed name, and the reader only had to stop
 * resolving that prefix and build an anonymous individual instead.
 *
 * <p>The label is kept as written, which makes a DLe round trip stable. It is not stable
 * coming from RDF, where the label is generated on load — but that is true of every syntax,
 * and is the reason the label carries no meaning.
 */
class AnonymousIndividualTest {

    private static final String NS = "http://example.org/b#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
    private final OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
    private final OWLNamedIndividual bob = df.getOWLNamedIndividual(IRI.create(NS + "bob"));

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

    private static String bodyOf(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private OWLAnonymousIndividual anon(String label) {
        return df.getOWLAnonymousIndividual("_:" + label);
    }

    /** Writes, reads, and asserts the axioms are unchanged. */
    private String roundTrip(OWLOntology o) throws Exception {
        String written = write(o);
        String body = bodyOf(written);
        OWLOntology back = assertDoesNotThrow(() -> read(written),
            () -> "must reload:\n" + body);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "and be unchanged:\n" + body);
        return body;
    }

    /** Every position an anonymous individual may occupy, so long as something is named. */
    @Test
    void anAnonymousIndividualSurvivesInEveryPosition() throws Exception {
        roundTrip(ontology(df.getOWLClassAssertionAxiom(a, anon("x"))));
        roundTrip(ontology(df.getOWLObjectPropertyAssertionAxiom(r, anon("x"), bob)));
        roundTrip(ontology(df.getOWLObjectPropertyAssertionAxiom(r, bob, anon("x"))));
        roundTrip(ontology(df.getOWLObjectPropertyAssertionAxiom(r, anon("x"), anon("y"))));
        roundTrip(ontology(
            df.getOWLNegativeObjectPropertyAssertionAxiom(r, anon("x"), bob)));
        roundTrip(ontology(
            df.getOWLDataPropertyAssertionAxiom(d, anon("x"), df.getOWLLiteral("v"))));
        roundTrip(ontology(df.getOWLSameIndividualAxiom(anon("x"), bob)));
        roundTrip(ontology(df.getOWLDifferentIndividualsAxiom(anon("x"), bob)));
        roundTrip(ontology(df.getOWLSubClassOfAxiom(a,
            df.getOWLObjectOneOf(anon("x"), bob))));
        roundTrip(ontology(df.getOWLSubClassOfAxiom(a,
            df.getOWLObjectHasValue(r, anon("x")))));
    }

    /** The label is written as it stands, so a DLe round trip does not rename it. */
    @Test
    void theLabelIsStableAcrossARoundTrip() throws Exception {
        String body = roundTrip(ontology(df.getOWLClassAssertionAxiom(a, anon("mine"))));
        assertTrue(body.contains("_:mine"),
            () -> "the label given is the label written:\n" + body);
        assertEquals(body, bodyOf(write(read(write(
            ontology(df.getOWLClassAssertionAxiom(a, anon("mine"))))))),
            "and a second pass does not rename it");
    }

    /** Read from DLe, it is anonymous rather than a name in some `_:` namespace. */
    @Test
    void itIsReadAsAnonymousNotAsAName() throws Exception {
        OWLOntology o = read(PREFIX + "_:x : A\n");
        OWLClassAssertionAxiom axiom =
            o.getAxioms(AxiomType.CLASS_ASSERTION).iterator().next();
        assertTrue(axiom.getIndividual().isAnonymous(),
            () -> "must be anonymous: " + axiom);
        assertFalse(o.individualsInSignature()
                .anyMatch(i -> i.getIRI().toString().contains("_:")),
            () -> "and no named individual may be invented for it: "
                + o.getLogicalAxioms());
    }

    /** A named individual is unaffected by any of this. */
    @Test
    void namedIndividualsAreUnaffected() throws Exception {
        String body = roundTrip(ontology(df.getOWLClassAssertionAxiom(a, bob)));
        assertTrue(body.contains("bob : A"), () -> body);
        assertFalse(body.contains("_:"), () -> body);
    }

    /**
     * An underscore is an ordinary name character, so a name may still start with one.
     *
     * <p>Only the {@code _:} pair is reserved; {@code _foo} is a name like any other.
     */
    @Test
    void anUnderscoreNameIsStillAName() throws Exception {
        OWLOntology o = read(PREFIX + "_foo : A\n");
        assertFalse(o.getAxioms(AxiomType.CLASS_ASSERTION).iterator().next()
            .getIndividual().isAnonymous(), "_foo is a name, not a blank node");
        roundTrip(ontology(df.getOWLClassAssertionAxiom(a,
            df.getOWLNamedIndividual(IRI.create(NS + "_foo")))));
    }

    /**
     * A blank node works in an annotation's subject and in its value.
     *
     * <p>The logical positions were fixed when `_:` stopped being resolved as a prefix, but
     * the annotation subject was still read as an IRI unconditionally — so
     * {@code @ann _:genid… note "s"} came back as "unknown prefix '_:'". The writer emits it,
     * so any ontology with a blank node in an annotation produced a document it could not
     * read back.
     *
     * <p>Asserted on the shape rather than on equality: the label is regenerated on load, as
     * it is in every syntax, so {@code _:x15} comes back under a different name. What has to
     * survive is that the subject, or the value, is anonymous at all.
     */
    @Test
    void aBlankNodeWorksInAnAnnotation() throws Exception {
        OWLAnnotationProperty ap = df.getOWLAnnotationProperty(IRI.create(NS + "note"));
        OWLAnonymousIndividual node = df.getOWLAnonymousIndividual("_:x15");

        OWLOntology subjectSide = read(write(ontology(
            df.getOWLAnnotationAssertionAxiom(ap, node, df.getOWLLiteral("s")))));
        assertTrue(subjectSide.axioms(AxiomType.ANNOTATION_ASSERTION)
                .anyMatch(ax -> ap.equals(ax.getProperty())
                    && ax.getSubject() instanceof OWLAnonymousIndividual
                    && df.getOWLLiteral("s").equals(ax.getValue())),
            () -> "an anonymous subject must survive: "
                + subjectSide.axioms(AxiomType.ANNOTATION_ASSERTION)
                    .map(Object::toString).collect(java.util.stream.Collectors.toList()));

        OWLOntology valueSide = read(write(ontology(
            df.getOWLAnnotationAssertionAxiom(ap, IRI.create(NS + "C"), node))));
        assertTrue(valueSide.axioms(AxiomType.ANNOTATION_ASSERTION)
                .anyMatch(ax -> ap.equals(ax.getProperty())
                    && IRI.create(NS + "C").equals(ax.getSubject())
                    && ax.getValue() instanceof OWLAnonymousIndividual),
            () -> "and so must an anonymous value: "
                + valueSide.axioms(AxiomType.ANNOTATION_ASSERTION)
                    .map(Object::toString).collect(java.util.stream.Collectors.toList()));
    }
}
