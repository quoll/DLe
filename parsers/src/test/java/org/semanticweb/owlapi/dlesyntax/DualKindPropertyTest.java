package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An IRI that is both an object and a data property is reported, not quietly halved.
 *
 * <p>DLe has one kind statement per name, and a name can carry only one, so the two lines
 * would contradict each other — the reader refuses that pair outright. Writing just one is no
 * better: which one got written depended on which entity the base class happened to ask
 * about, and the document then failed to re-read. So nothing is written, the reader
 * classifies the name from use, and it settles on the data property: the object property's
 * axioms are simply absent on the way back in.
 *
 * <p>OWL 2 DL forbids this punning, so no well-formed ontology reaches here. But "nothing
 * well-formed arrives" is not a reason to lose half a document in silence, which is what
 * happened — there was no way for the writer to say anything at all. It now reports it, and
 * the command prints it.
 *
 * <p>The warning channel is deliberate rather than logging: the library logs through SLF4J
 * and the binding is {@code slf4j-nop}, so a {@code LOGGER.warn} inside it reaches nobody.
 */
class DualKindPropertyTest {

    private static final String NS = "http://example.org/k#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** An ontology where one IRI is used as both kinds of property. */
    private OWLOntology dualKinded() throws Exception {
        OWLOntology o = manager.createOntology();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLObjectSomeValuesFrom(
            df.getOWLObjectProperty(IRI.create(NS + "z")), df.getOWLThing())));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLDataHasValue(
            df.getOWLDataProperty(IRI.create(NS + "z")), df.getOWLLiteral("x"))));
        return o;
    }

    @Test
    void theWriterReportsWhatItCannotSay() throws Exception {
        DLESyntaxStorerBase.takeWarnings();       // start from a clean sink
        write(dualKinded());
        List<String> warnings = DLESyntaxStorerBase.takeWarnings();
        assertEquals(1, warnings.size(),
            () -> "one warning, from one of the two passes over the same IRI: " + warnings);
        assertTrue(warnings.get(0).contains(NS + "z"),
            () -> "it must name the IRI: " + warnings.get(0));
        assertTrue(warnings.get(0).contains("object property")
                && warnings.get(0).contains("data property"),
            () -> "and both kinds: " + warnings.get(0));
    }

    /** Draining the sink is what clears it, so a second document starts clean. */
    @Test
    void theSinkIsDrainedByTheCaller() throws Exception {
        DLESyntaxStorerBase.takeWarnings();
        write(dualKinded());
        assertEquals(1, DLESyntaxStorerBase.takeWarnings().size(), "drained once");
        assertEquals(0, DLESyntaxStorerBase.takeWarnings().size(), "and empty after that");
    }

    /** An ordinary document produces no warnings at all. */
    @Test
    void anOrdinaryDocumentIsSilent() throws Exception {
        DLESyntaxStorerBase.takeWarnings();
        OWLOntology o = manager.createOntology();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")), df.getOWLClass(IRI.create(NS + "B"))));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "A")), df.getOWLObjectSomeValuesFrom(
                df.getOWLObjectProperty(IRI.create(NS + "r")),
                df.getOWLClass(IRI.create(NS + "B")))));
        write(o);
        assertEquals(List.of(), DLESyntaxStorerBase.takeWarnings(),
            "nothing here is unrepresentable");
    }
}
