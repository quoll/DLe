package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writing the same ontology twice must produce the same bytes.
 *
 * <p>It did not. The base storer fetches an entity's axioms itself and writes them in
 * whatever order the ontology's indexes hand them over, which is not the same order twice:
 * five runs over one document produced five different files, from four subsumptions on a
 * single subject upward. Axiom hash codes and the signature order are both stable, so the
 * variation is in the axiom index alone — nothing a caller can influence.
 *
 * <p>The axioms of a block are therefore collected as the base class offers them and
 * written when the block ends, in OWL API's own axiom order.
 */
class StableOrderTest {

    private static final String NS = "http://example.org/t#";

    /** An ontology with several axioms on one subject, which is what provokes it. */
    private OWLOntology manySubsumptions(int n) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        for (int i = 1; i <= n; i++) {
            manager.addAxiom(o, df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "A")),
                df.getOWLClass(IRI.create(NS + "C" + i))));
        }
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void writingTheSameOntologyTwiceGivesTheSameBytes() throws Exception {
        OWLOntology o = manySubsumptions(12);
        String first = write(o);
        for (int i = 0; i < 8; i++) {
            assertEquals(first, write(o), "pass " + i + " differed");
        }
    }

    /**
     * Two ontologies built the same way must write the same. Rebuilding is what a round
     * trip does, and it is where the index order was free to differ.
     */
    @Test
    void twoIdenticallyBuiltOntologiesWriteTheSame() throws Exception {
        Set<String> outputs = new LinkedHashSet<>();
        for (int i = 0; i < 8; i++) {
            outputs.add(write(manySubsumptions(12)));
        }
        assertEquals(1, outputs.size(),
            () -> "same content, " + outputs.size() + " different files");
    }

    /** Four is where it started; check a range either side of it. */
    @Test
    void theOrderIsStableAtEverySize() throws Exception {
        for (int n : new int[] {1, 2, 3, 4, 6, 10, 25}) {
            Set<String> outputs = new LinkedHashSet<>();
            for (int i = 0; i < 5; i++) outputs.add(write(manySubsumptions(n)));
            int size = n;
            assertEquals(1, outputs.size(), () -> size + " axioms on one subject varied");
        }
    }

    /** Every axiom still gets written — ordering them must not drop any. */
    @Test
    void nothingIsLostByHoldingTheAxiomsBack() throws Exception {
        String written = write(manySubsumptions(12));
        for (int i = 1; i <= 12; i++) {
            String line = "A ⊑ C" + i;
            assertTrue(written.contains(line), () -> "missing " + line + " in\n" + written);
        }
    }

    /** And a mixture of axiom kinds on one entity survives intact and in a fixed order. */
    @Test
    void aMixtureOfAxiomKindsIsStable() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI a = IRI.create(NS + "A");
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(a),
            df.getOWLClass(IRI.create(NS + "B"))));
        manager.addAxiom(o, df.getOWLEquivalentClassesAxiom(df.getOWLClass(a),
            df.getOWLClass(IRI.create(NS + "C"))));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(a),
            df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(IRI.create(NS + "r")),
                df.getOWLClass(IRI.create(NS + "D")))));
        manager.addAxiom(o, df.getOWLDisjointClassesAxiom(df.getOWLClass(a),
            df.getOWLClass(IRI.create(NS + "E"))));
        String first = write(o);
        for (int i = 0; i < 5; i++) assertEquals(first, write(o));
        assertTrue(first.contains("A ⊑ B"), () -> first);
        assertTrue(first.contains("A ≡ C"), () -> first);
    }
}
