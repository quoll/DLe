package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One spelling per axiom, for functionality and the cardinalities it is confused with.
 *
 * <p>DLe can say functionality two ways — {@code Func(r)} and the cardinality {@code ≤1 r.⊤}
 * — and OWL has two axioms, {@code FunctionalObjectProperty(r)} and
 * {@code SubClassOf(⊤ ObjectMaxCardinality(1 r))}. The reader used to map both spellings to
 * the first axiom, so the second could not survive a round trip: five axioms went in and four
 * came out. The inverse case was worse, because inverse-functionality was written with the
 * cardinality form and so depended on exactly the recognition that caused the collision.
 *
 * <p>The keyword form now belongs to the dedicated axioms and the cardinality syntax to the
 * cardinality axioms.
 *
 * <p>The bare cardinality statement had a separate fault. It abbreviates {@code ⊤ ⊑ …}, but
 * the reader discarded the symbol, the number and the filler and built functionality from the
 * property alone, so {@code ≤1 r.C}, {@code ≤3 r.⊤} and {@code ≥5 r.C} all came out
 * functional — the last of them asserting what its own document rules out, since a domain is
 * non-empty and nothing with five successors has at most one.
 */
class FunctionalSpellingTest {

    private static final String NS = "http://example.org/f#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));
    private final OWLObjectProperty s = df.getOWLObjectProperty(IRI.create(NS + "s"));
    private final OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
    private final OWLClass c = df.getOWLClass(IRI.create(NS + "C"));

    private OWLOntology read(String document) throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager().createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            o.getOWLOntologyManager().getOntologyLoaderConfiguration());
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLOntology ontology(OWLAxiom... axioms) throws Exception {
        OWLOntology o = manager.createOntology();
        for (OWLAxiom axiom : axioms) o.getOWLOntologyManager().addAxiom(o, axiom);
        return o;
    }

    /** Every distinct OWL axiom in this family survives a round trip as itself. */
    @Test
    void eachAxiomKeepsItsIdentity() throws Exception {
        List<OWLAxiom> input = List.of(
            df.getOWLFunctionalObjectPropertyAxiom(r),
            df.getOWLFunctionalDataPropertyAxiom(d),
            df.getOWLInverseFunctionalObjectPropertyAxiom(s),
            df.getOWLSubClassOfAxiom(df.getOWLThing(),
                df.getOWLObjectMaxCardinality(1, r, df.getOWLThing())),
            df.getOWLSubClassOfAxiom(c,
                df.getOWLObjectMaxCardinality(1, s, df.getOWLThing())));

        OWLOntology o = ontology(input.toArray(new OWLAxiom[0]));
        String written = write(o);
        OWLOntology back = read(written);
        for (OWLAxiom axiom : input) {
            assertTrue(back.containsAxiom(axiom),
                () -> axiom + " must survive:\n" + written + back.getLogicalAxioms());
        }
    }

    /** The keyword form is the dedicated axiom, in all three of its shapes. */
    @Test
    void theKeywordFormNamesTheDedicatedAxiom() throws Exception {
        assertTrue(read(PREFIX + "A ⊑ ∃r.B\nFunc(r)\n")
                .containsAxiom(df.getOWLFunctionalObjectPropertyAxiom(r)));
        assertTrue(read(PREFIX + "A ⊑ ∃r.B\nFunctional(r)\n")
                .containsAxiom(df.getOWLFunctionalObjectPropertyAxiom(r)),
            "the long spelling is the same token");
        assertTrue(read(PREFIX + "d ⊑ owl:topDataProperty\nFunc(d)\n")
                .containsAxiom(df.getOWLFunctionalDataPropertyAxiom(d)));
        assertTrue(read(PREFIX + "A ⊑ ∃r.B\nFunc(r⁻)\n")
                .containsAxiom(df.getOWLInverseFunctionalObjectPropertyAxiom(r)),
            "an inverse argument is OWL's inverse-functionality");
    }

    /**
     * A bare cardinality statement is the cardinality it states, not functionality.
     *
     * <p>Only the first row was ever read correctly, and it is read correctly still — as the
     * at-most-one it says, which is what the abbreviation means.
     */
    @Test
    void aBareCardinalityIsTheAxiomItStates() throws Exception {
        OWLClassExpression thing = df.getOWLThing();
        assertEquals(df.getOWLSubClassOfAxiom(thing, df.getOWLObjectMaxCardinality(1, r, thing)),
            only(read(PREFIX + "A ⊑ ∃r.B\n≤1 r.⊤\n")));
        assertEquals(df.getOWLSubClassOfAxiom(thing, df.getOWLObjectMaxCardinality(1, r, c)),
            only(read(PREFIX + "A ⊑ ∃r.B\nC ⊑ ⊤\n≤1 r.C\n")));
        assertEquals(df.getOWLSubClassOfAxiom(thing, df.getOWLObjectMaxCardinality(3, r, thing)),
            only(read(PREFIX + "A ⊑ ∃r.B\n≤3 r.⊤\n")));
        assertEquals(df.getOWLSubClassOfAxiom(thing, df.getOWLObjectMinCardinality(5, r, c)),
            only(read(PREFIX + "A ⊑ ∃r.B\nC ⊑ ⊤\n≥5 r.C\n")));
        assertEquals(df.getOWLSubClassOfAxiom(thing, df.getOWLObjectExactCardinality(1, r, thing)),
            only(read(PREFIX + "A ⊑ ∃r.B\n=1 r.⊤\n")));
    }

    /** None of those is a functionality axiom, whatever it entails. */
    @Test
    void aBareCardinalityIsNeverAFunctionalityAxiom() throws Exception {
        for (String statement : new String[] {"≤1 r.⊤", "≤1 r.C", "≤3 r.⊤", "≥5 r.C", "=1 r.⊤"}) {
            OWLOntology o = read(PREFIX + "A ⊑ ∃r.B\nC ⊑ ⊤\n" + statement + "\n");
            assertTrue(o.getAxioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).isEmpty()
                    && o.getAxioms(AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY).isEmpty(),
                () -> statement + " states a cardinality: " + o.getLogicalAxioms());
        }
    }

    /** The abbreviation for a data property reaches the data cardinality. */
    @Test
    void aBareCardinalityOnADataPropertyIsADataCardinality() throws Exception {
        OWLOntology o = read(PREFIX + "d ⊑ owl:topDataProperty\n≤1 d.⊤\n");
        assertEquals(df.getOWLSubClassOfAxiom(df.getOWLThing(),
                df.getOWLDataMaxCardinality(1, d, df.getTopDatatype())),
            only(o));
    }

    /** The single subsumption the document produced, for a one-statement comparison. */
    private OWLAxiom only(OWLOntology o) {
        List<OWLAxiom> found = o.getAxioms(AxiomType.SUBCLASS_OF).stream()
            .filter(ax -> ax.getSubClass().isOWLThing())
            .map(ax -> (OWLAxiom) ax)
            .collect(java.util.stream.Collectors.toList());
        assertEquals(1, found.size(), () -> "expected one ⊤ subsumption: " + o.getLogicalAxioms());
        return found.get(0);
    }
}
