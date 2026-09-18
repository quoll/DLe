package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLFacet;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every entity keeps its kind through a round trip.
 *
 * <p>DLe names entities without saying what they are, so the reader infers: {@code A ⊑ ∃r.B}
 * does not state that {@code r} is an object property rather than a data property. The
 * writer therefore has to decide, for each entity, whether the rendering will carry enough
 * for the reader to work the kind out — and if not, to say it outright with a kind statement.
 *
 * <p>That makes the two sides two implementations of one rule over different inputs: the
 * reader decides from parse-tree positions, the writer from OWL axiom types. They have to
 * agree, and until this test nothing checked that they did. Every entry in the writer's
 * exclusion list — {@code Func(p)}, {@code Disj(p, q)}, the domain idiom, {@code key(p)},
 * an unqualified cardinality — is a place where they disagreed, and each was found by a
 * separate bug report rather than by construction.
 *
 * <p>The invariant, stated once:
 *
 * <blockquote>For every axiom and every entity in it, reading back what the writer produced
 * must give that entity the same kind.</blockquote>
 *
 * <p>This is checked over the axiom shapes below rather than by generating them, because a
 * generator would have to know which combinations are legal OWL and that knowledge is the
 * interesting part. Adding a shape is one line.
 *
 * <p>See {@code docs/inference-design.md}. This test is stage one of the plan there: it
 * exists before the refactor so the refactor can be checked against it.
 */
class KindPreservationTest {

    private static final String NS = "http://example.org/k#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static final OWLDataFactory DF = OWLManager.getOWLDataFactory();

    // Entities used by the fixtures. Named by convention — upper-case classes, lower-case
    // properties — so that the case guess and the evidence agree unless a fixture is
    // deliberately testing what happens when they do not.
    private static final OWLClass A = DF.getOWLClass(IRI.create(NS + "A"));
    private static final OWLClass B = DF.getOWLClass(IRI.create(NS + "B"));
    private static final OWLClass C = DF.getOWLClass(IRI.create(NS + "C"));
    private static final OWLObjectProperty R = DF.getOWLObjectProperty(IRI.create(NS + "r"));
    private static final OWLObjectProperty S = DF.getOWLObjectProperty(IRI.create(NS + "s"));
    private static final OWLObjectProperty T = DF.getOWLObjectProperty(IRI.create(NS + "t"));
    private static final OWLDataProperty D = DF.getOWLDataProperty(IRI.create(NS + "d"));
    private static final OWLDataProperty E = DF.getOWLDataProperty(IRI.create(NS + "e"));
    private static final OWLNamedIndividual AA =
        DF.getOWLNamedIndividual(IRI.create(NS + "aa"));
    private static final OWLNamedIndividual BB =
        DF.getOWLNamedIndividual(IRI.create(NS + "bb"));
    private static final OWLAnnotationProperty NOTE =
        DF.getOWLAnnotationProperty(IRI.create(NS + "note"));
    private static final OWLDatatype STRING = DF.getOWLDatatype(IRI.create(XSD + "string"));
    private static final OWLDatatype INTEGER = DF.getOWLDatatype(IRI.create(XSD + "integer"));

    /** The kinds an IRI may have. An IRI may have more than one — that is a pun. */
    private enum Kind { CLASS, OBJECT_PROPERTY, DATA_PROPERTY, DATATYPE,
                        ANNOTATION_PROPERTY, INDIVIDUAL }

    /** One axiom shape, named for the failure message. */
    private static Arguments shape(String name, OWLAxiom... axioms) {
        return Arguments.of(name, Arrays.asList(axioms));
    }

    /**
     * Every axiom shape DLe claims to write.
     *
     * <p>Each fixture stands alone: it must carry enough for the reader on its own, because
     * that is what the writer promises. Where a shape cannot settle a kind by itself — the
     * domain idiom, say — the fixture is still listed on its own, since the writer is
     * supposed to notice and say the kind out loud.
     */
    private static Stream<Arguments> everyAxiomShape() {
        return Stream.of(
            // ── classes ──
            shape("SubClassOf", DF.getOWLSubClassOfAxiom(A, B)),
            shape("SubClassOf/restriction",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(R, B))),
            shape("EquivalentClasses", DF.getOWLEquivalentClassesAxiom(A, B)),
            shape("EquivalentClasses/n-ary", DF.getOWLEquivalentClassesAxiom(A, B, C)),
            shape("DisjointClasses", DF.getOWLDisjointClassesAxiom(A, B)),
            shape("DisjointClasses/n-ary", DF.getOWLDisjointClassesAxiom(A, B, C)),
            shape("DisjointUnion",
                DF.getOWLDisjointUnionAxiom(A, Arrays.asList(B, C))),

            // ── object properties ──
            shape("SubObjectPropertyOf", DF.getOWLSubObjectPropertyOfAxiom(R, S),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(R, B))),
            shape("EquivalentObjectProperties",
                DF.getOWLEquivalentObjectPropertiesAxiom(R, S),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(R, B))),
            shape("DisjointObjectProperties",
                DF.getOWLDisjointObjectPropertiesAxiom(R, S),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(R, B)),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(S, B))),
            shape("InverseObjectProperties", DF.getOWLInverseObjectPropertiesAxiom(R, S),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(R, B))),
            shape("ObjectPropertyDomain", DF.getOWLObjectPropertyDomainAxiom(R, A)),
            shape("ObjectPropertyRange", DF.getOWLObjectPropertyRangeAxiom(R, B)),
            shape("FunctionalObjectProperty", DF.getOWLFunctionalObjectPropertyAxiom(R)),
            shape("InverseFunctionalObjectProperty",
                DF.getOWLInverseFunctionalObjectPropertyAxiom(R)),
            shape("ReflexiveObjectProperty", DF.getOWLReflexiveObjectPropertyAxiom(R)),
            shape("IrreflexiveObjectProperty", DF.getOWLIrreflexiveObjectPropertyAxiom(R)),
            shape("SymmetricObjectProperty", DF.getOWLSymmetricObjectPropertyAxiom(R)),
            shape("AsymmetricObjectProperty", DF.getOWLAsymmetricObjectPropertyAxiom(R)),
            shape("TransitiveObjectProperty", DF.getOWLTransitiveObjectPropertyAxiom(R)),
            shape("SubPropertyChainOf",
                DF.getOWLSubPropertyChainOfAxiom(Arrays.asList(R, S), T)),

            // ── data properties ──
            shape("SubDataPropertyOf", DF.getOWLSubDataPropertyOfAxiom(D, E),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D, STRING))),
            shape("EquivalentDataProperties", DF.getOWLEquivalentDataPropertiesAxiom(D, E),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D, STRING))),
            shape("DisjointDataProperties", DF.getOWLDisjointDataPropertiesAxiom(D, E),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D, STRING)),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(E, STRING))),
            // With corroborating evidence, which is what the `/alone` suffix contrasts
            // against. These two were byte-identical to their `/alone` twins — 66 shapes but
            // 64 distinct axiom sets — so the contrast the naming promises was never made and
            // six of the parameterised checks were literal repeats.
            shape("DataPropertyDomain", DF.getOWLDataPropertyDomainAxiom(D, A),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D, STRING))),
            shape("DataPropertyRange", DF.getOWLDataPropertyRangeAxiom(D, STRING)),
            shape("FunctionalDataProperty", DF.getOWLFunctionalDataPropertyAxiom(D),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D, STRING))),

            // ── the shapes that say "role" without saying which kind ──
            shape("DataPropertyDomain/alone", DF.getOWLDataPropertyDomainAxiom(D, A)),
            shape("FunctionalDataProperty/alone", DF.getOWLFunctionalDataPropertyAxiom(D)),
            shape("DisjointDataProperties/alone",
                DF.getOWLDisjointDataPropertiesAxiom(D, E)),
            shape("HasKey/data-only", DF.getOWLHasKeyAxiom(A,
                Collections.singleton(D))),
            shape("DataMinCardinality/unqualified",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataMinCardinality(2, D))),
            shape("DataMaxCardinality/unqualified",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataMaxCardinality(2, D))),
            shape("DataExactCardinality/unqualified",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataExactCardinality(2, D))),

            // ── keys, datatypes ──
            shape("HasKey/object-only", DF.getOWLHasKeyAxiom(A,
                Collections.singleton(R))),
            shape("HasKey/mixed", DF.getOWLHasKeyAxiom(A, Arrays.asList(R, D))),
            shape("DatatypeDefinition", DF.getOWLDatatypeDefinitionAxiom(
                DF.getOWLDatatype(IRI.create(NS + "Code")),
                DF.getOWLDatatypeRestriction(STRING,
                    DF.getOWLFacetRestriction(OWLFacet.MIN_LENGTH, DF.getOWLLiteral(3))))),

            // ── assertions ──
            shape("ClassAssertion", DF.getOWLClassAssertionAxiom(A, AA)),
            shape("ObjectPropertyAssertion",
                DF.getOWLObjectPropertyAssertionAxiom(R, AA, BB)),
            shape("NegativeObjectPropertyAssertion",
                DF.getOWLNegativeObjectPropertyAssertionAxiom(R, AA, BB)),
            shape("DataPropertyAssertion",
                DF.getOWLDataPropertyAssertionAxiom(D, AA, DF.getOWLLiteral("v"))),
            shape("NegativeDataPropertyAssertion",
                DF.getOWLNegativeDataPropertyAssertionAxiom(D, AA, DF.getOWLLiteral("v"))),
            shape("SameIndividual", DF.getOWLSameIndividualAxiom(AA, BB)),
            shape("DifferentIndividuals", DF.getOWLDifferentIndividualsAxiom(AA, BB)),

            // ── annotations ──
            shape("AnnotationAssertion/label", DF.getOWLAnnotationAssertionAxiom(
                    DF.getRDFSLabel(), A.getIRI(), DF.getOWLLiteral("A label")),
                DF.getOWLSubClassOfAxiom(A, B)),
            shape("AnnotationAssertion/custom", DF.getOWLAnnotationAssertionAxiom(
                    NOTE, A.getIRI(), DF.getOWLLiteral("a note")),
                DF.getOWLSubClassOfAxiom(A, B)),

            // ── class expressions, where the filler settles the property's kind ──
            shape("ObjectAllValuesFrom",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectAllValuesFrom(R, B))),
            shape("ObjectHasValue",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectHasValue(R, AA))),
            shape("ObjectHasSelf", DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectHasSelf(R))),
            shape("ObjectMinCardinality/qualified",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectMinCardinality(2, R, B))),
            shape("ObjectMinCardinality/unqualified",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectMinCardinality(2, R))),
            shape("ObjectOneOf",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectOneOf(AA, BB))),
            shape("DataAllValuesFrom",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataAllValuesFrom(D, STRING))),
            shape("DataHasValue",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataHasValue(D, DF.getOWLLiteral("v")))),
            shape("DataMinCardinality/qualified",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataMinCardinality(2, D, STRING))),
            shape("DataOneOf",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D,
                    DF.getOWLDataOneOf(DF.getOWLLiteral("x"), DF.getOWLLiteral("y"))))),
            shape("DataComplementOf",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D,
                    DF.getOWLDataComplementOf(STRING)))),
            shape("DataUnionOf",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D,
                    DF.getOWLDataUnionOf(STRING, INTEGER)))),
            shape("DatatypeRestriction",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(D,
                    DF.getOWLDatatypeRestriction(INTEGER,
                        DF.getOWLFacetRestriction(OWLFacet.MIN_INCLUSIVE,
                            DF.getOWLLiteral(1)))))),

            // ── names that break the case convention, where only a statement can say ──
            shape("upper-case object property",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(
                    DF.getOWLObjectProperty(IRI.create(NS + "Related")), B))),
            shape("upper-case data property",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLDataSomeValuesFrom(
                    DF.getOWLDataProperty(IRI.create(NS + "Score")), STRING))),
            shape("lower-case class",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(
                    R, DF.getOWLClass(IRI.create(NS + "lowerC"))))),
            shape("lower-case class/subsumption",
                DF.getOWLSubClassOfAxiom(DF.getOWLClass(IRI.create(NS + "lowerC")), B)),
            shape("digit-initial class",
                DF.getOWLSubClassOfAxiom(DF.getOWLClass(IRI.create(NS + "762705008")), B)),
            shape("digit-initial object property",
                DF.getOWLSubClassOfAxiom(A, DF.getOWLObjectSomeValuesFrom(
                    DF.getOWLObjectProperty(IRI.create(NS + "246075003")), B))),

            // ── a pun: one IRI, two kinds, which OWL 2 DL forbids but SNOMED CT uses ──
            shape("pun class+object property",
                DF.getOWLDeclarationAxiom(DF.getOWLClass(IRI.create(NS + "Attr"))),
                DF.getOWLDeclarationAxiom(
                    DF.getOWLObjectProperty(IRI.create(NS + "Attr"))),
                DF.getOWLSubClassOfAxiom(A, DF.getOWLClass(IRI.create(NS + "Attr"))),
                DF.getOWLSubObjectPropertyOfAxiom(R,
                    DF.getOWLObjectProperty(IRI.create(NS + "Attr"))))
        );
    }

    // ── machinery ───────────────────────────────────────────────────────────

    private static String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static OWLOntology read(String document) throws Exception {
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

    /**
     * Every entity's kind, by IRI.
     *
     * <p>A set per IRI rather than one kind, because a punned name genuinely has two and
     * collapsing them would hide the case this most needs to watch.
     *
     * <p>{@code rdfs:label} is left out: the reader invents one for every entity that has
     * none, which is long-standing and deliberate, and it would otherwise make an
     * annotation property of nothing in particular appear in every comparison.
     */
    /**
     * The round-tripped ontology with the reader's invented labels taken out.
     *
     * <p>{@code DefaultLabelAdder} gives every entity that has none an {@code rdfs:label}
     * equal to its local name. That is long-standing and deliberate, but it drags
     * {@code xsd:string} into the signature as the datatype of those literals, which made
     * every shape here report {@code xsd:string} as an invented datatype. Removing the
     * invented labels is better than excluding {@code xsd:string}, which several fixtures
     * genuinely test.
     */
    private static OWLOntology withoutInventedLabels(OWLOntology original, OWLOntology back) {
        Set<OWLAxiom> keep = original.getAxioms();
        back.axioms(AxiomType.ANNOTATION_ASSERTION)
            .filter(ax -> ax.getProperty().isLabel())
            .filter(ax -> !keep.contains(ax))
            .collect(java.util.stream.Collectors.toList())
            .forEach(ax -> back.getOWLOntologyManager().removeAxiom(back, ax));
        return back;
    }

    private static Map<String, Set<Kind>> kinds(OWLOntology o) {
        Map<String, Set<Kind>> found = new TreeMap<>();
        record(found, o.classesInSignature(), Kind.CLASS);
        record(found, o.objectPropertiesInSignature(), Kind.OBJECT_PROPERTY);
        record(found, o.dataPropertiesInSignature(), Kind.DATA_PROPERTY);
        record(found, o.datatypesInSignature(), Kind.DATATYPE);
        record(found, o.annotationPropertiesInSignature(), Kind.ANNOTATION_PROPERTY);
        record(found, o.individualsInSignature(), Kind.INDIVIDUAL);
        // owl:Thing and owl:Nothing arrive from renderings like `⊤ ⊑ ∀r.C`; rdfs:label is
        // invented by the reader. Neither is an entity the document is about.
        found.remove("http://www.w3.org/2002/07/owl#Thing");
        found.remove("http://www.w3.org/2002/07/owl#Nothing");
        found.remove("http://www.w3.org/2000/01/rdf-schema#label");
        return found;
    }

    private static void record(Map<String, Set<Kind>> into,
                               Stream<? extends OWLEntity> entities, Kind kind) {
        entities.forEach(e -> into
            .computeIfAbsent(e.getIRI().toString(), k -> EnumSet.noneOf(Kind.class))
            .add(kind));
    }

    /** Only the part of the IRI a reader needs, so the messages stay readable. */
    private static String shortName(String iri) {
        int hash = iri.lastIndexOf('#');
        return hash < 0 ? iri : iri.substring(hash + 1);
    }

    private static String describe(Map<String, Set<Kind>> kinds) {
        StringBuilder out = new StringBuilder();
        kinds.forEach((iri, set) ->
            out.append("      ").append(shortName(iri)).append(" : ").append(set).append('\n'));
        return out.toString();
    }

    // ── the invariant ───────────────────────────────────────────────────────

    /**
     * No entity may come back with a different kind from the one it went out with.
     *
     * <p>Entities that vanish entirely are a different defect — the writer dropping an
     * axiom, filed as #23 — and are reported by {@link #noEntityVanishes} so that the two
     * failures do not hide each other.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAxiomShape")
    void everyEntityKeepsItsKind(String name, List<OWLAxiom> axioms) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology original = m.createOntology();
        axioms.forEach(axiom -> m.addAxiom(original, axiom));

        String written = write(original);
        String body = bodyOf(written);
        OWLOntology back = assertDoesNotThrow(() -> read(written),
            () -> name + ": the written document must reload\n" + body);

        Map<String, Set<Kind>> before = kinds(original);
        Map<String, Set<Kind>> after = kinds(withoutInventedLabels(original, back));

        List<String> changed = new ArrayList<>();
        before.forEach((iri, was) -> {
            Set<Kind> now = after.get(iri);
            if (now != null && !now.equals(was)) {
                changed.add("      " + shortName(iri) + " : " + was + " became " + now);
            }
        });
        assertTrue(changed.isEmpty(),
            () -> name + ": these entities changed kind\n" + String.join("\n", changed)
                + "\n    written as:\n" + body);
    }

    /**
     * No entity may appear that was not there before.
     *
     * <p>An invented entity is how the silent corruptions announced themselves: a string
     * written unquoted became an individual, a lower-case class filler became a skolem
     * class, a bare foreign name became a local one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAxiomShape")
    void noEntityIsInvented(String name, List<OWLAxiom> axioms) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology original = m.createOntology();
        axioms.forEach(axiom -> m.addAxiom(original, axiom));

        String written = write(original);
        String body = bodyOf(written);
        OWLOntology back = read(written);

        Map<String, Set<Kind>> before = kinds(original);
        Map<String, Set<Kind>> after = kinds(withoutInventedLabels(original, back));
        List<String> invented = new ArrayList<>();
        after.keySet().forEach(iri -> {
            if (!before.containsKey(iri)) {
                invented.add("      " + shortName(iri) + " : " + after.get(iri));
            }
        });
        assertTrue(invented.isEmpty(),
            () -> name + ": these entities were invented\n" + String.join("\n", invented)
                + "\n    written as:\n" + body);
    }

    /**
     * No entity may vanish.
     *
     * <p>Separate from the kind check so that an axiom being dropped and an entity being
     * reclassified cannot be mistaken for one another.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAxiomShape")
    void noEntityVanishes(String name, List<OWLAxiom> axioms) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology original = m.createOntology();
        axioms.forEach(axiom -> m.addAxiom(original, axiom));

        String written = write(original);
        String body = bodyOf(written);
        OWLOntology back = read(written);

        Map<String, Set<Kind>> before = kinds(original);
        Map<String, Set<Kind>> after = kinds(withoutInventedLabels(original, back));
        List<String> gone = new ArrayList<>();
        before.keySet().forEach(iri -> {
            if (!after.containsKey(iri)) {
                gone.add("      " + shortName(iri) + " : " + before.get(iri));
            }
        });
        assertTrue(gone.isEmpty(),
            () -> name + ": these entities vanished\n" + String.join("\n", gone)
                + "\n    written as:\n" + body);
    }

    // ── the other half of the invariant ─────────────────────────────────────

    /**
     * What hand-written DLe means, independently of what the writer would have produced.
     *
     * <p>The round-trip checks above are asymmetric, and deliberately so: they catch the
     * writer believing the reader will work a kind out when it will not, which is the defect
     * class the exclusion list came from. They cannot catch the reader getting *weaker*,
     * because the writer compensates — crippling the case guess entirely still passes them,
     * since the writer then states every kind outright and the kinds survive.
     *
     * <p>So this table fixes the reader's side directly: a document, and the kind each name
     * must have after reading it. Nothing here depends on the writer.
     *
     * <p>Format: {@code document || name=KIND, name=KIND}. `⊤ ⊑ ∀q.xsd:string` and friends
     * appear where a fixture needs a name classified without relying on the convention.
     */
    private static Stream<Arguments> readerExpectations() {
        return Stream.of(
            // the convention alone
            Arguments.of("A ⊑ B", "A=CLASS, B=CLASS"),
            Arguments.of("A ⊑ ∃r.B", "A=CLASS, B=CLASS, r=OBJECT_PROPERTY"),
            // a datatype filler settles the kind against the convention
            Arguments.of("A ⊑ ∃d.xsd:string", "A=CLASS, d=DATA_PROPERTY"),
            Arguments.of("A ⊑ ∃Score.xsd:string", "A=CLASS, Score=DATA_PROPERTY"),
            Arguments.of("A ⊑ ∃d.{\"x\"}", "A=CLASS, d=DATA_PROPERTY"),
            Arguments.of("A ⊑ ∃d.¬xsd:string", "A=CLASS, d=DATA_PROPERTY"),
            Arguments.of("A ⊑ ∃d.[xsd:string ⊓ [minLength 3]]", "A=CLASS, d=DATA_PROPERTY"),
            // an inverse is object-only
            Arguments.of("A ⊑ ∃r⁻.B", "A=CLASS, B=CLASS, r=OBJECT_PROPERTY"),
            // a chain is object-only throughout
            Arguments.of("p ∘ q ⊑ t",
                "p=OBJECT_PROPERTY, q=OBJECT_PROPERTY, t=OBJECT_PROPERTY"),
            // an explicit kind statement beats the convention
            Arguments.of("Related ⊑ owl:topObjectProperty", "Related=OBJECT_PROPERTY"),
            Arguments.of("Score ⊑ owl:topDataProperty", "Score=DATA_PROPERTY"),
            // propagation across a subsumption, in both orders
            Arguments.of("A ⊑ ∃d.xsd:string\nd ⊑ e",
                "A=CLASS, d=DATA_PROPERTY, e=DATA_PROPERTY"),
            Arguments.of("d ⊑ e\nA ⊑ ∃e.xsd:string",
                "A=CLASS, d=DATA_PROPERTY, e=DATA_PROPERTY"),
            // propagation across an equivalence, in both orders
            Arguments.of("A ⊑ ∃d.xsd:string\nd ≡ e",
                "A=CLASS, d=DATA_PROPERTY, e=DATA_PROPERTY"),
            Arguments.of("d ≡ e\nA ⊑ ∃e.xsd:string",
                "A=CLASS, d=DATA_PROPERTY, e=DATA_PROPERTY"),
            // and through a chain of equivalences
            Arguments.of("d ≡ e\ne ≡ f\nA ⊑ ∃f.xsd:string",
                "A=CLASS, d=DATA_PROPERTY, e=DATA_PROPERTY, f=DATA_PROPERTY"),
            // annotation properties are their own kind
            Arguments.of("@ann A note \"v\"\nA ⊑ B",
                "A=CLASS, B=CLASS, note=ANNOTATION_PROPERTY"),
            // individuals come from position, never from the convention
            Arguments.of("Bob : A", "A=CLASS, Bob=INDIVIDUAL"),
            Arguments.of("(Bob,ann):r",
                "Bob=INDIVIDUAL, ann=INDIVIDUAL, r=OBJECT_PROPERTY"),
            Arguments.of("(Bob,\"v\"):d", "Bob=INDIVIDUAL, d=DATA_PROPERTY"),
            // a datatype in a range position
            Arguments.of("⊤ ⊑ ∀d.xsd:integer", "d=DATA_PROPERTY"),
            // the domain idiom says role, not which kind, so the default applies
            Arguments.of("∃p.⊤ ⊑ A", "A=CLASS, p=OBJECT_PROPERTY"),
            // a digit-initial name has no convention to go on, so position decides
            Arguments.of("A ⊑ ∃:246075003.B",
                "A=CLASS, B=CLASS, 246075003=OBJECT_PROPERTY"),
            Arguments.of("A ⊑ ∃:118586006.xsd:integer", "A=CLASS, 118586006=DATA_PROPERTY"),

            // The case guess carrying the whole decision. `p ⊓ q ⊑ ⊥` is an ordinary class
            // intersection as far as the syntax goes; only the convention reclassifies it as
            // role disjointness, so this is the one fixture that fails if the guess is
            // removed. `Disj(p, q)` cannot stand in for it — its keyword settles the kind by
            // itself.
            Arguments.of("p ⊓ q ⊑ ⊥", "p=OBJECT_PROPERTY, q=OBJECT_PROPERTY"),

            // A stated kind propagating down a sub-property pair, which is how a name with
            // no evidence of its own inherits one.
            Arguments.of("s ⊑ owl:topObjectProperty\nd ⊑ s",
                "s=OBJECT_PROPERTY, d=OBJECT_PROPERTY"),
            Arguments.of("s ⊑ owl:topDataProperty\nd ⊑ s",
                "s=DATA_PROPERTY, d=DATA_PROPERTY"),

            // A class whose name breaks the convention is still a class. The filler
            // position says class; the convention only guesses, and a guess does not get to
            // overrule it. This used to become the skolem class dle:E_lowerC_… with both the
            // class and the property discarded.
            Arguments.of("A ⊑ ∃r.lowerC",
                "A=CLASS, lowerC=CLASS, r=OBJECT_PROPERTY"),
            Arguments.of("A ⊑ ∀r.lowerC",
                "A=CLASS, lowerC=CLASS, r=OBJECT_PROPERTY"),
            Arguments.of("A ⊑ ≥2 r.lowerC",
                "A=CLASS, lowerC=CLASS, r=OBJECT_PROPERTY")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readerExpectations")
    void theReaderAssignsTheStatedKinds(String document, String expected) throws Exception {
        String full = "@prefix : <" + NS + ">\n" + document + "\n";
        OWLOntology o = assertDoesNotThrow(() -> read(full),
            () -> "must parse:\n" + full);
        Map<String, Set<Kind>> actual = kinds(o);

        for (String clause : expected.split(",")) {
            String[] parts = clause.trim().split("=");
            String name = parts[0].trim();
            Kind wanted = Kind.valueOf(parts[1].trim());
            Set<Kind> got = actual.get(NS + name);
            assertNotNull(got,
                () -> name + " is missing entirely from:\n" + describe(actual));
            assertTrue(got.contains(wanted),
                () -> name + " must be " + wanted + " but is " + got + "\n"
                    + describe(actual));
        }
    }

    /**
     * A stated kind is not overwritten by propagation; the clash is reported instead.
     *
     * <p>`d` is a data property by its filler and `s` is stated to be an object property, so
     * `d ⊑ s` spans the two hierarchies — something OWL cannot express. Propagation could
     * "resolve" it by overwriting one side, and used to: the comment in the scanner records
     * a case where data-ness crossed the barrier and the error then blamed a filler two
     * lines away. Reporting it is the whole point of giving a stated kind priority.
     */
    @Test
    void aStatedKindIsNotOverwrittenByPropagation() {
        String document = "@prefix : <" + NS + ">\n"
            + "s ⊑ owl:topObjectProperty\n"
            + "A ⊑ ∃d.xsd:string\n"
            + "d ⊑ s\n";
        Throwable t = assertThrows(Throwable.class, () -> read(document),
            "a sub-property axiom across the two hierarchies has to be reported");
        assertTrue(String.valueOf(t.getMessage()).contains("data property"),
            () -> "and the message must name the clash: " + t.getMessage());
    }

    /**
     * An inverse inside a restriction is object evidence, so a clash with it is reported.
     *
     * <p>A data property has no inverse, so `∃d⁻.B` says `d` is an object property as
     * plainly as any filler does. Recording that is what lets the pair below be reported
     * rather than resolved: without it the document declares one IRI as both kinds, which
     * OWL 2 DL forbids and no reasoner will load.
     *
     * <p>Distinct from the equivalence and chain spellings covered in
     * {@code RoleCharacteristicKindTest}, which reach the evidence by a different route —
     * this one goes through the restriction classifier.
     */
    @Test
    void anInverseInARestrictionIsEvidence() {
        // The filler is ⊤ deliberately. With a named class filler the position pins the
        // kind anyway — a non-⊤ class filler is object evidence by itself — so such a
        // fixture would pass whether or not the inverse was recorded, and would not be
        // testing this at all. `⊤` says only "role", which leaves the inverse as the sole
        // reason the kind is known.
        for (String document : new String[] {
                "@prefix : <" + NS + ">\n(aa,\"5\"):d\nA ⊑ ∃d⁻.⊤\n",
                "@prefix : <" + NS + ">\n(aa,\"5\"):d\nA ⊑ ∃d⁻.B\n"}) {
            Throwable t = assertThrows(Throwable.class, () -> read(document),
                () -> "d is a data property on one line and an inverse on the next:\n"
                    + document);
            String message = String.valueOf(t.getMessage());
            assertTrue(message.contains("object property") && message.contains("data property"),
                () -> "the message must name both kinds: " + message);
        }
    }

    /**
     * A predicate reference works wherever its declaration sits.
     *
     * <p>The reference used to be resolved while the scan was still running, so a
     * declaration below it had not been seen yet and the same document read two ways
     * depending on the order of its lines (#27). Nothing is guessed now, and the scan that
     * records declarations finishes before any reference is resolved, so there is no order
     * left to depend on.
     *
     * <p>The class IRI embeds a hash of the expression and is the contract with the Python
     * implementation, so it is compared rather than merely the axiom count.
     */
    @Test
    void aPredicateReferenceDoesNotDependOnDeclarationOrder() throws Exception {
        String declaration = "greaterThan(x,y) ≝ x > y\n";
        String setup = "⊤ ⊑ ∀a.xsd:integer\n⊤ ⊑ ∀b.xsd:integer\n";
        String reference = "R ≡ ∃a.greaterThan\n";

        Set<String> first = predicateClasses("@prefix : <" + NS + ">\n"
            + declaration + setup + reference);
        Set<String> last = predicateClasses("@prefix : <" + NS + ">\n"
            + setup + reference + declaration);
        assertEquals(first, last,
            "the same document, and the declaration's position must not change it");
        assertFalse(first.isEmpty(), "and it must actually build a predicate class");
    }

    /** The synthetic classes a document's predicate references produce. */
    private static Set<String> predicateClasses(String document) throws Exception {
        return read(document).classesInSignature()
            .map(c -> c.getIRI().toString())
            .filter(iri -> iri.startsWith("http://quoll.github.io/DLe/vocab#"))
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * A predicate is referenced only where it is declared.
     *
     * <p>A bare lower-case filler used to become a predicate on the strength of its case,
     * which is what destroyed lower-case classes. An undeclared name has no definition to
     * expand, so reading it as a predicate reference could never have meant anything.
     */
    @Test
    void anUndeclaredNameIsNotAPredicateReference() throws Exception {
        assertTrue(predicateClasses("@prefix : <" + NS + ">\nA ⊑ ∃r.notDeclared\n").isEmpty(),
            "nothing declares notDeclared, so no predicate class may appear");
        assertFalse(predicateClasses("@prefix : <" + NS + ">\n"
            + "isDeclared(x) ≝ x > 0\n⊤ ⊑ ∀a.xsd:integer\nR ≡ ∃a.isDeclared\n").isEmpty(),
            "and a declared one must still be referenced");
    }

    /**
     * An annotation property is the third kind, and clashes with it are reported.
     *
     * <p>OWL 2 DL wants the object, data and annotation property IRIs pairwise disjoint.
     * Two of the three pairs were watched, so a name could be a data property on one line
     * and an annotation property on the next with nothing said — an ontology no reasoner
     * will load, by the same defect as the object/data pair with a third of it unguarded.
     */
    @Test
    void anAnnotationPropertyClashIsReported() {
        String[][] clashes = {
            {"(aa,\"5\"):d\n@ann C d \"v\"\n", "d"},
            {"A ⊑ ∃e.B\n@ann C e \"v\"\n", "e"},
            {"e domain C\n(x,y):e\n", "e"},
            {"e range C\n(x,y):e\n", "e"},
        };
        for (String[] clash : clashes) {
            Throwable t = assertThrows(Throwable.class,
                () -> read("@prefix : <" + NS + ">\n" + clash[0]),
                () -> clash[1] + " is two kinds of property in:\n" + clash[0]);
            String message = String.valueOf(t.getMessage());
            assertTrue(message.contains("annotation property"),
                () -> "the message must name the annotation kind: " + message);
            assertTrue(message.contains("line"),
                () -> "and where each use is: " + message);
        }
    }

    /**
     * And the shapes that are not clashes must still be accepted.
     *
     * <p>{@code @label}, {@code @doc}, {@code @db} and {@code @storage} each name a fixed
     * rdfs: property rather than one of the document's own, so a document name that happens
     * to match one of them is not in the annotation position at all.
     */
    @Test
    void whatIsNotAClashIsStillAccepted() throws Exception {
        assertDoesNotThrow(() -> read("@prefix : <" + NS + ">\n"
            + "@ann C p \"v\"\n@ann D p \"w\"\n"), "one annotation property, twice");
        assertDoesNotThrow(() -> read("@prefix : <" + NS + ">\n"
            + "@label A \"x\"\n(aa,bb):label\n"),
            "@label names rdfs:label, not the document's `label`");
        assertDoesNotThrow(() -> read("@prefix : <" + NS + ">\n"
            + "@ann C note \"v\"\nA ⊑ ∃r.B\n"), "unrelated names");
    }

    /**
     * A datatype definition works wherever it sits relative to its use.
     *
     * <p>This is a regression test in the strict sense: the first version of the
     * document-defined-datatype fix put the detection inside the main scan, which classifies
     * each restriction as it passes it — so a definition further down the file had not been
     * seen yet and `⊤ ⊑ ∀d.MyType` read one way above its definition and another below it.
     * That is exactly the order dependence just removed from predicates, reintroduced a few
     * hours later in another place, and is why the detection is now a separate sweep.
     */
    @Test
    void aDatatypeDefinitionDoesNotDependOnItsPositionInTheFile() throws Exception {
        String definition = "MyType ≡ [xsd:string ⊓ [minLength 3]]\n";
        String use = "⊤ ⊑ ∀d.MyType\n";
        for (String document : new String[] {definition + use, use + definition}) {
            OWLOntology o = assertDoesNotThrow(
                () -> read("@prefix : <" + NS + ">\n" + document),
                () -> "must parse in either order:\n" + document);
            assertEquals(1, o.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
                () -> "d is a data property either way:\n" + document + o.getLogicalAxioms());
            assertTrue(o.datatypesInSignature()
                    .anyMatch(t -> t.getIRI().toString().endsWith("#MyType")),
                () -> "and MyType a datatype:\n" + document + o.getLogicalAxioms());
        }
    }

    /**
     * A chain of datatype aliases resolves the same way whatever order it is written in.
     *
     * <p>The pre-pass that finds datatype definitions reads the set it is filling — deciding
     * whether the right-hand side is a data range asks whether its name is a datatype — and
     * it walked the tree once, in document order. So {@code A2 \u2261 A1} was only recognised
     * if {@code A1 \u2261 xsd:string} had already been passed. Across the six orderings of the
     * same three statements it found three datatypes, or two, or one, and the orderings that
     * found fewest went on to declare a datatype as a class and a data property as an object
     * property — all at exit 0.
     *
     * <p>The single-definition test above cannot see this: one definition needs no fixpoint.
     */
    @Test
    void aChainOfDatatypeAliasesDoesNotDependOnOrder() throws Exception {
        String[] statements = {
            "A1 \u2261 xsd:string\n",
            "A2 \u2261 A1\n",
            "A3 \u2261 A2\n",
        };
        int[][] orders = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        for (int[] order : orders) {
            String document = "@prefix : <" + NS + ">\n"
                + statements[order[0]] + statements[order[1]] + statements[order[2]]
                + "B \u2291 \u2200d.A3\n";
            OWLOntology o = assertDoesNotThrow(() -> read(document),
                () -> "must parse in every order:\n" + document);
            assertEquals(3, o.datatypesInSignature()
                    .filter(t -> t.getIRI().toString().contains("#A")).count(),
                () -> "all three aliases are datatypes:\n" + document + o.getLogicalAxioms());
            assertEquals(0, o.classesInSignature()
                    .filter(c -> c.getIRI().toString().contains("#A")).count(),
                () -> "and none of them is a class:\n" + document + o.getLogicalAxioms());
            assertTrue(o.containsDataPropertyInSignature(IRI.create(NS + "d")),
                () -> "d stays a data property:\n" + document + o.getLogicalAxioms());
            assertFalse(o.containsObjectPropertyInSignature(IRI.create(NS + "d")),
                () -> "and not an object one:\n" + document + o.getLogicalAxioms());
        }
    }

    /**
     * A datatype the document defines is refused in a class position, in both spellings.
     *
     * <p>{@code assertNamedClass} asked only the built-in datatype list, so the compact
     * {@code b:Code} was accepted as {@code ClassAssertion(:Code :b)} while the spaced
     * {@code b : Code} was refused. The same assertion, two answers, and the accepted one put
     * a datatype where only a class may go.
     */
    @Test
    void aDocumentDefinedDatatypeIsRefusedInAClassPositionInEitherSpelling() {
        for (String assertion : new String[] {"b:Code", "b : Code"}) {
            String document = "@prefix : <" + NS + ">\nCode \u2261 xsd:string\n" + assertion + "\n";
            DLESemanticException e = assertThrows(DLESemanticException.class,
                () -> read(document),
                () -> "a datatype is not a class, whichever way it is spelled:\n" + document);
            assertTrue(e.getMessage().contains("datatype"),
                () -> "the message must say what is wrong: " + e.getMessage());
        }
    }

    /**
     * The case convention does not override a datatype the document defines.
     *
     * <p>The convention's datatype exception consulted the built-in list only, because the
     * static form it shares with the writer is handed a resolved IRI and cannot see
     * {@code datatypeNames}. So a lower-case defined datatype was guessed to be a role on its
     * spelling, and ended up declared as a datatype *and* an object property — a pun OWL 2 DL
     * does not allow, at exit 0.
     */
    @Test
    void aLowerCaseDefinedDatatypeIsNotGuessedToBeARole() {
        String document = "@prefix : <" + NS + ">\n"
            + "code \u2261 [xsd:string \u2293 [minLength 3]]\n"
            + "code \u2291 thing\n";
        DLESemanticException e = assertThrows(DLESemanticException.class,
            () -> read(document),
            "a datatype cannot also be a property, and saying so beats guessing from case");
        assertTrue(e.getMessage().contains("datatype"),
            () -> "the message must name the conflict: " + e.getMessage());
    }

    /**
     * A datatype and a property cannot share an IRI, in either property kind.
     *
     * <p>Class and property is a pun DLe allows on purpose. Datatype and property is not:
     * OWL 2 DL keeps datatypes in a set disjoint from every property set, so the result is
     * not a document with two readings but one outside the profile. Nothing checked it, and a
     * datatype *position* records no finding, so {@code xsd:string} was invisible to the
     * conflict rule — {@code x \u2261 xsd:string} with {@code A \u2291 \u2203x.B} produced
     * {@code Declaration(ObjectProperty(xsd:string))}, reserved vocabulary as a property,
     * with the datatype definition gone and an invented label on it, at exit 0.
     */
    @Test
    void aDatatypeIsNotAlsoAProperty() {
        String[] documents = {
            "x \u2261 xsd:string\nA \u2291 \u2203x.B\n",
            "x \u2261 xsd:string\n(a,\"5\"):x\n",
        };
        for (String body : documents) {
            String document = "@prefix : <" + NS + ">\n" + body;
            DLESemanticException e = assertThrows(DLESemanticException.class,
                () -> read(document), () -> "a datatype is not a property:\n" + document);
            assertTrue(e.getMessage().contains("datatype")
                    && e.getMessage().contains("separate sets"),
                () -> "the message must say why: " + e.getMessage());
        }
    }

    /** The pun DLe does allow is untouched by that. */
    @Test
    void aClassAndPropertyPunIsStillAccepted() throws Exception {
        OWLOntology o = assertDoesNotThrow(() -> read("@prefix : <" + NS + ">\n"
            + "Attr \u2291 \u22a4\nAttr \u2291 owl:topObjectProperty\nsub \u2291 Attr\n"));
        assertTrue(o.containsClassInSignature(IRI.create(NS + "Attr")),
            () -> "Attr is a class: " + o.getLogicalAxioms());
        assertTrue(o.containsObjectPropertyInSignature(IRI.create(NS + "Attr")),
            () -> "and a property: " + o.getLogicalAxioms());
    }

    /**
     * A kind conflict that can only be reached by propagation is reported.
     *
     * <p>The conflict check ran once, as the first statement of propagation, so it saw only
     * what the document says directly. A data property propagated up a chain onto a name an
     * {@code @ann} had already made an annotation property went unreported entirely, and the
     * document left the OWL 2 DL profile at exit 0 with no declaration for the name at all.
     * Propagation records what it reaches as PROPAGATED, which is evidence, so running the
     * same rule again afterwards catches it.
     */
    @Test
    void aConflictReachedByPropagationIsReported() {
        String document = "@prefix : <" + NS + ">\n"
            + "A \u2291 \u2203x.xsd:string\n"
            + "x \u2291 y\n"
            + "@ann C y \"v\"\n";
        DLESemanticException e = assertThrows(DLESemanticException.class,
            () -> read(document),
            "y is a data property by propagation and an annotation property by position");
        assertTrue(e.getMessage().contains("annotation property")
                && e.getMessage().contains("data property"),
            () -> "the message must name both kinds: " + e.getMessage());
    }

    /** The fixture table has to actually exercise every kind, or it proves less than it says. */
    @Test
    void theFixturesCoverEveryKind() {
        Map<Kind, Integer> seen = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) seen.put(kind, 0);
        everyAxiomShape().forEach(args -> {
            @SuppressWarnings("unchecked")
            List<OWLAxiom> axioms = (List<OWLAxiom>) args.get()[1];
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            try {
                OWLOntology o = m.createOntology();
                axioms.forEach(axiom -> m.addAxiom(o, axiom));
                kinds(o).values().forEach(set ->
                    set.forEach(k -> seen.merge(k, 1, Integer::sum)));
            } catch (OWLOntologyCreationException e) {
                throw new AssertionError(e);
            }
        });
        seen.forEach((kind, count) -> assertTrue(count > 0,
            () -> "no fixture exercises " + kind + "; the table covers " + seen));
    }

    /**
     * A predicate filler excuses its own finding, not every conflict on the name.
     *
     * <p>An object finding recorded because a restriction's filler looked like a class is not
     * evidence when the filler turns out to be a predicate reference. The exemption was keyed
     * on the name, and only the first filler recorded for it, so a genuine and unrelated
     * conflict elsewhere in the document was suppressed — and what surfaced instead blamed
     * the filler for a clash two lines away, the precise failure this area's comments say the
     * design exists to avoid.
     *
     * <p>Asserted against the same document with the predicate definition removed: the two
     * must report the same conflict, because the predicate has nothing to do with it.
     */
    @Test
    void aPredicateFillerDoesNotHideAnUnrelatedConflict() {
        String conflict = "A \u2291 \u2203x.B\n(a,\"5\"):x\n";
        DLESemanticException withPredicate = assertThrows(DLESemanticException.class,
            () -> read("@prefix : <" + NS + ">\n"
                + "greaterThan(u,v) \u225d u > v\n"
                + "R \u2261 \u2203x.greaterThan\n" + conflict),
            "x is an object property on one line and a data property on the next");
        DLESemanticException without = assertThrows(DLESemanticException.class,
            () -> read("@prefix : <" + NS + ">\n" + conflict));

        assertTrue(withPredicate.getMessage().contains("as an object property")
                && withPredicate.getMessage().contains("as a data property"),
            () -> "the real conflict must be reported: " + withPredicate.getMessage());
        assertFalse(withPredicate.getMessage().contains("found"),
            () -> "and not as a complaint about a filler: " + withPredicate.getMessage());
        // Same complaint, with the line numbers normalised away — the predicate definition
        // shifts them by two, and nothing else about the report may change.
        assertEquals(normaliseLines(without.getMessage()),
            normaliseLines(withPredicate.getMessage()),
            "the predicate definition must make no difference to what is reported");
    }

    /** A diagnostic with every line and column reference replaced by a placeholder. */
    private static String normaliseLines(String message) {
        return message.replaceAll("at \\d+:\\d+", "at N:N").replaceAll("line \\d+", "line N");
    }

    /** A predicate reference on its own is still perfectly acceptable. */
    @Test
    void aPredicateReferenceAloneIsStillAccepted() {
        assertDoesNotThrow(() -> read("@prefix : <" + NS + ">\n"
            + "greaterThan(u,v) \u225d u > v\n"
            + "R \u2261 \u2203x.greaterThan\n"));
    }

    /**
     * {@code ⊥} used as an ordinary class expression, in the positions that take one.
     *
     * <p>Mutating the reader to return {@code owl:Thing} for {@code ⊥} failed one test in the
     * whole suite, and that one reached it only incidentally through an idiom; the mirror
     * mutation on {@code ⊤} fails thirty. Every one of the 198 parameterised checks in this
     * file is blind to it by construction, because {@code owl:Nothing} is deleted from the
     * kind map before they compare anything — a deliberate exclusion, since the reader
     * invents {@code owl:Thing} and {@code owl:Nothing} freely, but it means the bottom
     * concept has nothing watching it.
     *
     * <p>{@code A ⊓ B ⊑ ⊥} is deliberately not here: that is the disjointness idiom and is
     * read as {@code DisjointClasses}, which several tests already cover.
     */
    @Test
    void theBottomConceptSurvivesInEveryOrdinaryPosition() throws Exception {
        OWLClass a = DF.getOWLClass(IRI.create(NS + "A"));
        OWLObjectProperty r = DF.getOWLObjectProperty(IRI.create(NS + "r"));
        OWLAxiom[] axioms = {
            DF.getOWLSubClassOfAxiom(a, DF.getOWLNothing()),
            DF.getOWLEquivalentClassesAxiom(a, DF.getOWLNothing()),
            DF.getOWLSubClassOfAxiom(a, DF.getOWLObjectAllValuesFrom(r, DF.getOWLNothing())),
            DF.getOWLSubClassOfAxiom(a, DF.getOWLObjectSomeValuesFrom(r, DF.getOWLNothing())),
        };
        for (OWLAxiom axiom : axioms) {
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            OWLOntology o = m.createOntology();
            m.addAxiom(o, axiom);
            String written = write(o);
            assertTrue(read(written).containsAxiom(axiom),
                () -> axiom + " did not survive:\n" + written);
            assertFalse(read(written).containsAxiom(replaceBottomWithTop(axiom)),
                () -> "and must not have become the top concept:\n" + written);
        }
    }

    /** The same axiom with owl:Nothing swapped for owl:Thing, for a negative assertion. */
    private static OWLAxiom replaceBottomWithTop(OWLAxiom axiom) {
        OWLClass a = DF.getOWLClass(IRI.create(NS + "A"));
        OWLObjectProperty r = DF.getOWLObjectProperty(IRI.create(NS + "r"));
        if (axiom instanceof OWLEquivalentClassesAxiom) {
            return DF.getOWLEquivalentClassesAxiom(a, DF.getOWLThing());
        }
        OWLClassExpression sup = ((OWLSubClassOfAxiom) axiom).getSuperClass();
        if (sup instanceof OWLObjectAllValuesFrom) {
            return DF.getOWLSubClassOfAxiom(a,
                DF.getOWLObjectAllValuesFrom(r, DF.getOWLThing()));
        }
        if (sup instanceof OWLObjectSomeValuesFrom) {
            return DF.getOWLSubClassOfAxiom(a,
                DF.getOWLObjectSomeValuesFrom(r, DF.getOWLThing()));
        }
        return DF.getOWLSubClassOfAxiom(a, DF.getOWLThing());
    }
}
