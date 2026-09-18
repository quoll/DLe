package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The annotation property is a kind, not just a way to fail.
 *
 * <p>It had findings but no name set, and the sets are what the visitor is handed — so it
 * could produce an error and never a classification, and an object *default* beat it.
 * {@code Func(x)} beside {@code @ann C x "v"} declared x as a functional object property and
 * an annotation property together, which OWL 2 DL forbids, at exit 0. {@code Func} pins
 * neither role kind, because functionality has both forms, so it records the weakest tier
 * there is and never conflicted with anything.
 *
 * <p>Three positions name one: {@code @ann}'s property, and the subjects of {@code domain}
 * and {@code range}, which in DLe are the annotation-property forms. The kind then travels a
 * subsumption edge as the other two kinds do, which is what lets {@code ap ⊑ bp} — written
 * identically to an object sub-property axiom — be read as the annotation axiom it is.
 */
class AnnotationPropertyKindTest {

    private static final String NS = "http://example.org/n#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();

    private OWLOntology read(String document) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            m.getOntologyLoaderConfiguration());
        return o;
    }

    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * A name used as an annotation property cannot also be used as a role.
     *
     * <p>All three IRI sets are disjoint in OWL 2 DL, so this is a contradiction in the
     * document rather than something to resolve by preferring one reading.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "Func(x)\n@ann C x \"v\"",
        "x domain C\nFunc(x)",
        "Disj(x, z)\n@ann C x \"v\"",
        "x range C\nA ⊑ ∃x.B",
    })
    void anAnnotationPropertyIsNotAlsoARole(String body) {
        DLESemanticException e = assertThrows(DLESemanticException.class,
            () -> read(PREFIX + body + "\n"),
            () -> "one IRI cannot be two kinds of property:\n" + body);
        // Two rules can answer, and which one does is right either way. Where the role use
        // is real evidence — a restriction — the generic two-evidence clash reports it. Where
        // it is only a default, as `Func` is, the annotation rule does; that is the case that
        // used to pass silently. Both name the annotation kind and the kind it clashes with.
        assertTrue(e.getMessage().contains("annotation property"),
            () -> "the message must name the annotation kind: " + e.getMessage());
        assertTrue(e.getMessage().contains("object property")
                || e.getMessage().contains("data property"),
            () -> "and the kind it clashes with: " + e.getMessage());
    }

    /**
     * {@code ap ⊑ bp} is the annotation subsumption when the document says what `ap` is.
     *
     * <p>Established on either side, and by any of the three positions: the kind travels the
     * subsumption edge the way object and data kinds do.
     */
    @ParameterizedTest(name = "established by {0}")
    @ValueSource(strings = {
        "ap domain C",
        "ap range C",
        "@ann C ap \"v\"",
        "bp domain C",
    })
    void anAnnotationSubsumptionIsReadAsOne(String establishing) throws Exception {
        OWLOntology o = read(PREFIX + establishing + "\nap ⊑ bp\n");
        assertTrue(o.containsAxiom(df.getOWLSubAnnotationPropertyOfAxiom(
                df.getOWLAnnotationProperty(IRI.create(NS + "ap")),
                df.getOWLAnnotationProperty(IRI.create(NS + "bp")))),
            () -> "expected the annotation subsumption: " + o.getLogicalAxioms()
                + " " + o.axioms(AxiomType.SUB_ANNOTATION_PROPERTY_OF)
                    .map(Object::toString).collect(java.util.stream.Collectors.toList()));
        assertEquals(0, o.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).size(),
            () -> "and not the object one: " + o.getLogicalAxioms());
    }

    /** With nothing to establish it, the pair is an ordinary object subsumption. */
    @Test
    void anUnestablishedPairIsStillAnObjectSubsumption() throws Exception {
        OWLOntology o = read(PREFIX + "ap ⊑ bp\n");
        assertEquals(1, o.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).size(),
            () -> "the case convention still decides when nothing else does: "
                + o.getLogicalAxioms());
    }

    /**
     * The writer emits it only when the reader will get it back.
     *
     * <p>Where the document establishes the kind, the axiom survives. Where it does not, the
     * axiom is dropped rather than written as something else: losing an axiom is better than
     * changing one, and `ap ⊑ bp` alone reads as an object sub-property axiom, which puns the
     * name across two kinds.
     */
    @Test
    void theWriterEmitsItOnlyWhenItCanBeReadBack() throws Exception {
        OWLAnnotationProperty ap = df.getOWLAnnotationProperty(IRI.create(NS + "ap"));
        OWLAnnotationProperty bp = df.getOWLAnnotationProperty(IRI.create(NS + "bp"));
        OWLAxiom subsumption = df.getOWLSubAnnotationPropertyOfAxiom(ap, bp);

        OWLOntology established = manager.createOntology();
        manager.addAxiom(established, subsumption);
        manager.addAxiom(established,
            df.getOWLAnnotationPropertyDomainAxiom(ap, IRI.create(NS + "C")));
        String establishedText = write(established);
        assertTrue(read(establishedText).containsAxiom(subsumption),
            () -> "with a domain to establish the kind it must survive:\n"
                + establishedText);

        OWLOntology bare = manager.createOntology();
        manager.addAxiom(bare, subsumption);
        manager.addAxiom(bare, df.getOWLDeclarationAxiom(ap));
        manager.addAxiom(bare, df.getOWLDeclarationAxiom(bp));
        String bareText = write(bare);
        OWLOntology back = read(bareText);
        assertEquals(0, back.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).size(),
            () -> "with nothing to establish it, it must not come back as an object"
                + " subsumption:\n" + bareText);
    }
}
