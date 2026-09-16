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
 * A name outside the document's own namespace keeps the namespace it came from.
 *
 * <p>Writing a name needs a prefix covering its IRI. When none did, the renderer wrote the
 * bare local part and the namespace was gone — and a bare name reads back into the default
 * namespace, so the entity quietly became a different entity. It was silent in both
 * directions: nothing in the output showed a namespace had been dropped, and the document
 * reloaded without complaint.
 *
 * <p>Any ontology naming anything outside its own namespace hit this, which is most
 * ontologies that import or align with another.
 */
class ForeignNamespaceTest {

    private static final String NS = "http://example.org/o#";
    private static final String OTHER = "http://other.example.com/vocab#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

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

    private OWLOntology subClassOf(String superClassIri) throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "C")),
            df.getOWLClass(IRI.create(superClassIri))));
        return o;
    }

    /**
     * The superclass comes back the entity it was, not one in the local namespace.
     *
     * <p>This is the assertion that matters: comparing the axioms, not merely checking that
     * the document reloads. It reloaded perfectly before, saying something else.
     */
    @Test
    void aForeignNameKeepsItsNamespace() throws Exception {
        OWLOntology o = subClassOf(OTHER + "Person");
        String written = write(o);
        assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
            () -> "the superclass must still be <" + OTHER + "Person>:\n" + bodyOf(written));
        assertFalse(read(written).containsClassInSignature(IRI.create(NS + "Person")),
            () -> "and no class may appear in the local namespace:\n" + bodyOf(written));
    }

    /** A declaration is written for the namespace, since that is what carries it. */
    @Test
    void theNamespaceIsDeclared() throws Exception {
        String body = bodyOf(write(subClassOf(OTHER + "Person")));
        assertTrue(body.contains("<" + OTHER + ">"),
            () -> "the namespace must be declared to be usable:\n" + body);
    }

    /** Namespaces with no hash, and hierarchical ones, are covered too. */
    @Test
    void everyShapeOfNamespaceIsCovered() throws Exception {
        for (String iri : new String[] {
                OTHER + "Person",                        // hash
                "http://third.example.com/path/Thing",   // slash
                "urn:example:Widget",                    // urn, no separator but the last colon
                "http://fourth.example.com/v2#a-b"}) {   // a hyphen in the local part
            OWLOntology o = subClassOf(iri);
            String written = write(o);
            assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
                () -> iri + " must survive:\n" + bodyOf(written));
        }
    }

    /** Every position that writes a name, not just the ones a class appears in. */
    @Test
    void everyNamePositionKeepsItsNamespace() throws Exception {
        OWLOntology o = manager.createOntology();
        OWLNamedIndividual a = df.getOWLNamedIndividual(IRI.create(NS + "a"));
        manager.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(
            df.getOWLObjectProperty(IRI.create(OTHER + "knows")), a,
            df.getOWLNamedIndividual(IRI.create(OTHER + "b"))));
        manager.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(
            df.getOWLDataProperty(IRI.create(OTHER + "age")), a, df.getOWLLiteral(7)));
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(
            df.getOWLClass(IRI.create(OTHER + "Agent")), a));
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(IRI.create(OTHER + "note")),
            IRI.create(NS + "C"), df.getOWLLiteral("x")));

        String written = write(o);
        OWLOntology back = read(written);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "every foreign name must survive:\n" + bodyOf(written));
        assertTrue(back.annotationPropertiesInSignature()
                .anyMatch(p -> p.getIRI().equals(IRI.create(OTHER + "note"))),
            () -> "including an annotation property:\n" + bodyOf(written));
    }

    /**
     * An IRI-valued annotation names something the signature never mentions.
     *
     * <p>A custom property, because the {@code rdfs:seeAlso} and {@code rdfs:comment}
     * shorthands cannot hold an IRI value at all — a separate defect, and not about
     * namespaces: they fail the same way on a name in the document's own namespace.
     */
    @Test
    void anIriAnnotationValueKeepsItsNamespace() throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
            df.getOWLAnnotationProperty(IRI.create(NS + "note")),
            IRI.create(NS + "C"), IRI.create(OTHER + "Elsewhere")));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "C")),
            df.getOWLClass(IRI.create(NS + "D"))));

        String written = write(o);
        assertTrue(read(written).axioms(AxiomType.ANNOTATION_ASSERTION)
                .anyMatch(ax -> IRI.create(OTHER + "Elsewhere").equals(ax.getValue())),
            () -> "the value must keep its namespace:\n" + bodyOf(written));
    }

    /** A minted name must not collide with one the document already uses. */
    @Test
    void aMintedNameDoesNotCollide() throws Exception {
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create("http://mine.example.com/a#A")),
            df.getOWLClass(IRI.create(OTHER + "Person"))));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        format.setPrefix("ns1:", "http://mine.example.com/a#");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(o.getLogicalAxioms(), read(written).getLogicalAxioms(),
            () -> "ns1: is taken, so the new namespace needs a different name:\n"
                + bodyOf(written));
    }

    /** The same document must always produce the same names. */
    @Test
    void mintedNamesAreStable() throws Exception {
        OWLOntology o = manager.createOntology();
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        for (String ns : new String[] {"http://z.example.com/v#", "http://a.example.com/v#",
                "http://m.example.com/v#"}) {
            manager.addAxiom(o, df.getOWLSubClassOfAxiom(c,
                df.getOWLClass(IRI.create(ns + "T"))));
        }
        String first = bodyOf(write(o));
        assertEquals(first, bodyOf(write(o)), "two writes of one ontology must agree");
        assertEquals(first, bodyOf(write(read(first))),
            () -> "and a round trip must not renumber them:\n" + first);
    }

    /** A name in the document's own namespace is still written bare. */
    @Test
    void aLocalNameIsUnaffected() throws Exception {
        String body = bodyOf(write(subClassOf(NS + "Person")));
        assertTrue(body.contains("C ⊑ Person"),
            () -> "a local name needs no prefix:\n" + body);
        assertFalse(body.contains("ns1:"),
            () -> "and nothing should be minted for it:\n" + body);
    }
}
