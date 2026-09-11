package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writing the same ontology twice must produce the same bytes.
 *
 * <p>It did not. The base storer fetches an entity's axioms itself and writes them in
 * whatever order the ontology's indexes hand them over, which is not the same order twice.
 * The threshold is sharp: three axioms on one subject are stable, four are not — twelve
 * runs over a four-axiom document produced eight different files. Axiom hash codes and the
 * signature order are both stable, so the variation is in the axiom index alone, and
 * nothing a caller can influence.
 *
 * <p>The axioms of a block are therefore collected as the base class offers them and
 * written, sorted, when the entity's own section ends.
 *
 * <p>Note which of these tests can actually fail. Writing the <em>same</em> ontology
 * instance twice cannot: one instance has one index with one iteration order, so it was
 * always reproducible and proves nothing. What varies is rebuilding equal content, which
 * is what a round trip does, so every test here rebuilds.
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

    /** Parses DLe text, so a round trip can be written and compared. */
    private OWLOntology read(String text) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(
            new StreamDocumentSource(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))),
            o, manager.getOntologyLoaderConfiguration());
        return o;
    }

    /** Writes DLe text from a source document, ignoring the explanatory header. */
    private String statementsOf(String dle) throws Exception {
        String[] lines = write(read(dle)).split("\n", -1);
        StringBuilder body = new StringBuilder();
        boolean header = true;
        for (String line : lines) {
            if (header && !line.isEmpty() && line.charAt(0) != '#') header = false;
            if (!header) body.append(line).append('\n');
        }
        return body.toString();
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

    /**
     * Writing what was just read gives the same bytes, repeatedly.
     *
     * <p>This is the property a user sees: converting a document twice, or converting it
     * and converting the result, should not produce a diff. Each pass parses into a fresh
     * ontology, which is exactly the rebuild that was unstable.
     */
    @Test
    void aRoundTripIsIdempotent() throws Exception {
        StringBuilder doc = new StringBuilder("@prefix : <" + NS + ">\n");
        for (int i = 1; i <= 12; i++) doc.append("A ⊑ C").append(i).append('\n');

        String once = statementsOf(doc.toString());
        for (int i = 0; i < 6; i++) {
            assertEquals(once, statementsOf(doc.toString()), "pass " + i + " differed");
            assertEquals(once, statementsOf(once), "re-reading its own output differed");
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

    /** A mixture of axiom kinds on one entity survives intact and in a fixed order. */
    @Test
    void aMixtureOfAxiomKindsIsStable() throws Exception {
        Set<String> outputs = new LinkedHashSet<>();
        for (int i = 0; i < 6; i++) outputs.add(write(mixedBlock()));
        assertEquals(1, outputs.size(),
            () -> "same content, " + outputs.size() + " different files");
        String only = outputs.iterator().next();
        assertTrue(only.contains("A ⊑ B"), () -> only);
        assertTrue(only.contains("A ≡ C"), () -> only);
    }

    private OWLOntology mixedBlock() throws Exception {
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
        return o;
    }

    // ── What must not be reordered ──────────────────────────────────────────

    /**
     * The annotation lines a block opens with stay ahead of its logical axioms.
     *
     * <p>Holding was first switched on at the top of the block, which caught the
     * {@code @label}/{@code @doc}/{@code @ann} lines this class emits itself and sorted them
     * in among the subsumptions — so an entity's label appeared after the axioms that use
     * it. Only the axioms the base class offers belong in the ordering.
     */
    @Test
    void annotationsStayAheadOfTheLogicalAxioms() throws Exception {
        String body = statementsOf("@prefix : <" + NS + ">\n"
            + "@label r \"A role\"\n"
            + "@doc r \"What it is for.\"\n"
            + "r ⊑ s\n"
            + "∃r.⊤ ⊑ Thing1\n");
        List<String> order = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("@label r") || line.startsWith("@doc r")) order.add("annotation");
            else if (line.startsWith("r ⊑") || line.startsWith("∃r.")) order.add("axiom");
        }
        assertEquals(List.of("annotation", "annotation", "axiom", "axiom"), order,
            () -> "annotations must precede the logical axioms:\n" + body);
    }

    /**
     * The usage section stays after the entity's own axioms.
     *
     * <p>Flushing at the end of the whole block swept the usage axioms into the same sort,
     * so a statement about some other subject could land in the middle of this entity's.
     * The base class emits usages already sorted, in a section of their own; the hold ends
     * where that section begins.
     */
    @Test
    void theUsageSectionStaysAfterTheEntitysOwnAxioms() throws Exception {
        // Zebra ⊑ ∃r.Aaa is a usage of r. It sorts before r's own domain axiom by text,
        // since 'Z' precedes '∃', and r is given no statement opening with its own name —
        // one of those would be ranked first and mask the reordering.
        String body = statementsOf("@prefix : <" + NS + ">\n"
            + "∃r.⊤ ⊑ Aaa\n"
            + "Zebra ⊑ ∃r.Aaa\n");
        int ownAxiom = body.indexOf("∃r.⊤ ⊑ Aaa");
        int usage = body.indexOf("Zebra ⊑ ∃r.Aaa");
        assertTrue(ownAxiom >= 0 && usage >= 0, () -> body);
        assertTrue(ownAxiom < usage,
            () -> "a usage axiom must not be sorted in among the entity's own:\n" + body);
    }

    /**
     * An entity whose IRI has no short form is still written.
     *
     * <p>The ordering asks the renderer for the entity's own name to decide what heads the
     * block. An IRI with no declared prefix and no remainder has no short form and throws,
     * which turned a sort preference into a failed save — for a document that writes
     * perfectly well, since such an entity's only axiom here renders to nothing.
     */
    @Test
    void anEntityWhoseIriHasNoShortFormStillWrites() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLDeclarationAxiom(
            df.getOWLClass(IRI.create("urn:isbn:123"))));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")), df.getOWLClass(IRI.create(NS + "B"))));

        String written = assertDoesNotThrow(() -> write(o));
        assertTrue(written.contains("A ⊑ B"), () -> written);
    }

    /**
     * An entity's own defining statement heads its block, ahead of its qualifiers.
     *
     * <p>{@code Func(r)} sorts before {@code r ⊑ s} by text, because an upper-case letter
     * sorts before a lower-case one. Leading with the qualifier reads worse, so a statement
     * opening with the entity's own name is ranked first.
     */
    @Test
    void theEntitysOwnStatementHeadsItsBlock() throws Exception {
        String body = statementsOf("@prefix : <" + NS + ">\n"
            + "r ⊑ s\n"
            + "Func(r)\n"
            + "Trans(r)\n");
        int own = body.indexOf("r ⊑ s");
        int func = body.indexOf("Func(r)");
        assertTrue(own >= 0 && func >= 0, () -> body);
        assertTrue(own < func, () -> "the subsumption must lead the block:\n" + body);
    }
}
