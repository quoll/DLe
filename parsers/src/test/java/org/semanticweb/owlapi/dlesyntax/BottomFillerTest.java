package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ⊥} is as forgiving a filler as {@code ⊤}.
 *
 * <p>DLe reads {@code ⊤} in a filler position as whichever top the property's kind calls
 * for — {@code owl:Thing} for an object property, {@code rdfs:Literal} for a data one —
 * because {@code ∃d.⊤} is a common and readable way to say "has some value". {@code ⊥} was
 * not in that arrangement: it forced the object reading, so {@code ⊤ ⊑ ∀d.⊥} — "d has no
 * values", which is a real thing to state — was refused with a kind conflict against the
 * very statement that said {@code d} was a data property.
 *
 * <p>The two have to behave alike, or an author has to remember which one of the pair is
 * forgiving. In the data universe {@code ⊥} is the empty data range, which OWL spells
 * {@code DataComplementOf(rdfs:Literal)} and DLe writes {@code ¬rdfs:Literal}.
 */
class BottomFillerTest {

    private static final String NS = "http://example.org/b#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n"
        + "C ⊑ ⊤\nd ⊑ owl:topDataProperty\nr ⊑ owl:topObjectProperty\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLDataProperty d = df.getOWLDataProperty(IRI.create(NS + "d"));
    private final OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(NS + "r"));

    private OWLOntology read(String document) throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager().createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            o.getOWLOntologyManager().getOntologyLoaderConfiguration());
        return o;
    }

    /** In the data universe, {@code ⊥} is the empty data range. */
    @Test
    void bottomIsTheEmptyDataRangeForADataProperty() throws Exception {
        OWLDataRange empty = df.getOWLDataComplementOf(df.getTopDatatype());
        assertTrue(read(PREFIX + "⊤ ⊑ ∀d.⊥\n")
                .containsAxiom(df.getOWLDataPropertyRangeAxiom(d, empty)),
            "a range of ⊥ says d has no values");
        assertEquals(read(PREFIX + "⊤ ⊑ ∀d.¬rdfs:Literal\n").getLogicalAxioms(),
            read(PREFIX + "⊤ ⊑ ∀d.⊥\n").getLogicalAxioms(),
            "and is the same axiom as the spelled-out form");
    }

    /** And in the object universe it stays owl:Nothing. */
    @Test
    void bottomIsOwlNothingForAnObjectProperty() throws Exception {
        assertTrue(read(PREFIX + "⊤ ⊑ ∀r.⊥\n")
                .containsAxiom(df.getOWLObjectPropertyRangeAxiom(r, df.getOWLNothing())),
            "the object reading is unchanged");
    }

    /** The filler pins no kind, so it never contradicts what the document says. */
    @Test
    void bottomIsNoEvidenceOfKind() throws Exception {
        for (String statement : new String[] {"⊤ ⊑ ∀d.⊥", "C ⊑ ∃d.⊥", "C ⊑ ∀d.⊥"}) {
            OWLOntology o = assertDoesNotThrow(() -> read(PREFIX + statement + "\n"),
                () -> statement + " must not contradict `d ⊑ owl:topDataProperty`");
            assertTrue(o.containsDataPropertyInSignature(d.getIRI()),
                () -> "d stays a data property: " + o.getLogicalAxioms());
        }
    }
}
