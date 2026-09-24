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
 * A domain is written the way its universe allows.
 *
 * <p>{@code ⊤} is the top <em>concept</em>, and OWL 2 keeps the object and data universes
 * disjoint: an individual is never a literal. So {@code ∃d.⊤ ⊑ C} puts a concept where a
 * data range belongs. Read strictly it says {@code d} is an object property, which is the
 * opposite of what a data property domain means.
 *
 * <p>The textbook writes the data case {@code (≥1 d) ⊑ C}, and the reason is exactly this:
 * an unqualified minimum names no filler, so it never has to say which universe the filler
 * is in. That is the form the writer now emits. The object case keeps {@code ∃r.⊤ ⊑ C},
 * where {@code ⊤} is correct.
 *
 * <p>All of the spellings read, for both kinds, so nothing already written stops loading.
 */
class DomainSpellingTest {

    private static final String NS = "http://example.org/dm#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n"
        + "C ⊑ ⊤\nd ⊑ owl:topDataProperty\nr ⊑ owl:topObjectProperty\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
    private final OWLClass c = df.getOWLClass(IRI.create(NS + "C"));

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
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The textbook spelling is read as a domain, for both kinds of property. */
    @Test
    void theUnqualifiedMinimumIsADomain() throws Exception {
        assertTrue(read(PREFIX + "(≥1 d) ⊑ C\n")
                .containsAxiom(df.getOWLDataPropertyDomainAxiom(d, c)),
            "the parenthesised form the textbook writes");
        assertTrue(read(PREFIX + "≥1 d ⊑ C\n")
                .containsAxiom(df.getOWLDataPropertyDomainAxiom(d, c)),
            "and without the parentheses, which are only grouping");
        assertTrue(read(PREFIX + "(≥1 r) ⊑ C\n")
                .containsAxiom(df.getOWLObjectPropertyDomainAxiom(r, c)),
            "the object case takes it too");
    }

    /** The existential spellings still read, so nothing already written breaks. */
    @Test
    void theExistentialSpellingsStillRead() throws Exception {
        assertTrue(read(PREFIX + "∃r.⊤ ⊑ C\n")
                .containsAxiom(df.getOWLObjectPropertyDomainAxiom(r, c)));
        assertTrue(read(PREFIX + "∃d.rdfs:Literal ⊑ C\n")
                .containsAxiom(df.getOWLDataPropertyDomainAxiom(d, c)),
            "the precise data spelling, whose filler is the top data range");
        assertTrue(read(PREFIX + "∃d.⊤ ⊑ C\n")
                .containsAxiom(df.getOWLDataPropertyDomainAxiom(d, c)),
            "and the form DLe used to write, which every existing document holds");
    }

    /** Only a minimum of one is a domain; anything else is the subsumption it is. */
    @Test
    void aLargerMinimumIsNotADomain() throws Exception {
        OWLOntology o = read(PREFIX + "(≥2 d) ⊑ C\n");
        assertTrue(o.getAxioms(AxiomType.DATA_PROPERTY_DOMAIN).isEmpty(),
            () -> "at least two is not a domain: " + o.getLogicalAxioms());
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLDataMinCardinality(2, d), c)),
            () -> "it is the subsumption it states: " + o.getLogicalAxioms());
    }

    /** Each kind is written in the form its universe allows. */
    @Test
    void eachKindIsWrittenInItsOwnForm() throws Exception {
        String data = write(df.getOWLDataPropertyDomainAxiom(d, c));
        assertTrue(data.contains("(≥1 d) ⊑ C"),
            () -> "a data domain names no filler:\n" + data);
        assertFalse(data.contains("∃d.⊤"),
            () -> "and never puts the top concept in a data range position:\n" + data);

        String object = write(df.getOWLObjectPropertyDomainAxiom(r, c));
        assertTrue(object.contains("∃r.⊤ ⊑ C"),
            () -> "an object domain keeps the existential, where ⊤ is correct:\n" + object);
    }

    /** And both round-trip as the axioms they are. */
    @Test
    void bothKindsRoundTrip() throws Exception {
        OWLAxiom dataDomain = df.getOWLDataPropertyDomainAxiom(d, c);
        OWLAxiom objectDomain = df.getOWLObjectPropertyDomainAxiom(r, c);
        String written = write(dataDomain, objectDomain);
        OWLOntology back = read(written);
        assertTrue(back.containsAxiom(dataDomain), () -> "data domain:\n" + written);
        assertTrue(back.containsAxiom(objectDomain), () -> "object domain:\n" + written);
    }
}
