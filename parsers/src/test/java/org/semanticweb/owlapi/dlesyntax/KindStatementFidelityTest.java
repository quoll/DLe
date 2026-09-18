package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A kind statement has one job: to say what the reader could not have worked out. These
 * tests are about whether it actually does, for the families the writer used to get wrong.
 *
 * <p>The writer decided whether a statement was needed by asking whether a name's
 * <em>local part</em> was upper or lower case. The reader's rule is different — it guesses
 * a role only for a <em>bare</em> lower-case name, with no prefix at all — so the two
 * disagreed for every prefixed name and every digit-initial one, in both directions:
 * properties that needed a statement got none, and classes that never needed one got two.
 * Both families are ordinary. The digit-initial one is what SNOMED CT is made of.
 */
class KindStatementFidelityTest {

    /** Parses DLe text into a fresh ontology. */
    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), o,
            manager.getOntologyLoaderConfiguration());
        return o;
    }

    /** Writes an ontology as DLe, with the given prefixes bound. */
    private String write(OWLOntology o, String defaultNs, String... prefixPairs) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        if (defaultNs != null) format.setDefaultPrefix(defaultNs);
        for (int i = 0; i < prefixPairs.length; i += 2) {
            format.setPrefix(prefixPairs[i], prefixPairs[i + 1]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The document with its explanatory {@code #} header removed. */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    // ── Properties the reader cannot guess ──────────────────────────────────

    /**
     * A prefixed sub-property pair survives, because the writer says it is one.
     *
     * <p>`ex:rel ⊑ ex:relation` is the only role evidence in the document, and the reader's
     * guess stops at the prefix without ever considering case — so without a statement this
     * came back as a class subsumption, losing both declarations and the axiom. The writer
     * thought it was covered because the local parts are lower case.
     */
    @Test
    void aPrefixedSubPropertyPairSurvivesARoundTrip() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/ex#";
        OWLObjectProperty sub = df.getOWLObjectProperty(IRI.create(ns + "rel"));
        OWLObjectProperty sup = df.getOWLObjectProperty(IRI.create(ns + "relation"));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(sub, sup));

        String written = write(o, "http://example.org/d#", "ex:", ns);
        OWLOntology back = parse(written);
        assertTrue(back.containsObjectPropertyInSignature(sub.getIRI()),
            () -> "ex:rel must come back an object property:\n" + written);
        assertTrue(back.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).stream()
                .anyMatch(ax -> ax.getSubProperty().equals(sub)
                    && ax.getSuperProperty().equals(sup)),
            () -> "the sub-property axiom must survive:\n" + written);
    }

    /**
     * A digit-initial sub-property pair survives. This is the SNOMED CT shape.
     *
     * <p>A numeric local part is neither upper nor lower case, so the writer's old test
     * declined to mark it — while the reader, which needs a lower-case <em>bare</em> name,
     * could not guess it either. Nothing said it was a role, and the axiom was lost.
     */
    @Test
    void aDigitInitialSubPropertyPairSurvivesARoundTrip() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://snomed.info/id/";
        OWLObjectProperty sub = df.getOWLObjectProperty(IRI.create(ns + "116676008"));
        OWLObjectProperty sup = df.getOWLObjectProperty(IRI.create(ns + "762705008"));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(sub, sup));

        String written = write(o, "http://example.org/d#", "sct:", ns);
        OWLOntology back = parse(written);
        assertTrue(back.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).stream()
                .anyMatch(ax -> ax.getSubProperty().equals(sub)
                    && ax.getSuperProperty().equals(sup)),
            () -> "the sub-property axiom must survive:\n" + written);
    }

    /** The same, for data properties — the side with no test coverage at all before. */
    @Test
    void aPrefixedDataSubPropertyPairSurvivesARoundTrip() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/ex#";
        OWLDataProperty sub = df.getOWLDataProperty(IRI.create(ns + "code"));
        OWLDataProperty sup = df.getOWLDataProperty(IRI.create(ns + "identifier"));
        manager.addAxiom(o, df.getOWLSubDataPropertyOfAxiom(sub, sup));

        String written = write(o, "http://example.org/d#", "ex:", ns);
        OWLOntology back = parse(written);
        assertTrue(back.containsDataPropertyInSignature(sub.getIRI()),
            () -> "ex:code must come back a data property:\n" + written);
        assertTrue(back.getAxioms(AxiomType.SUB_DATA_PROPERTY).stream()
                .anyMatch(ax -> ax.getSubProperty().equals(sub)
                    && ax.getSuperProperty().equals(sup)),
            () -> "the data sub-property axiom must survive:\n" + written);
    }

    /**
     * A data property hierarchy in bare lower-case names survives.
     *
     * <p>This one is quiet and total. `a ⊑ b` between two data properties is exactly what
     * the reader's case guess claims — and that guess yields an <em>object</em> property,
     * always. The writer asked only "will the reader guess a role?", saw that it would, and
     * said nothing. Two data properties went in and two object properties came out, with
     * the subsumption rewritten to match.
     */
    @Test
    void aBareLowerCaseDataPropertyHierarchySurvives() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/d#";
        OWLDataProperty sub = df.getOWLDataProperty(IRI.create(ns + "a"));
        OWLDataProperty sup = df.getOWLDataProperty(IRI.create(ns + "b"));
        manager.addAxiom(o, df.getOWLSubDataPropertyOfAxiom(sub, sup));

        String written = write(o, ns);
        OWLOntology back = parse(written);
        assertTrue(back.containsDataPropertyInSignature(sub.getIRI())
                && back.containsDataPropertyInSignature(sup.getIRI()),
            () -> "both must come back data properties:\n" + written);
        assertFalse(back.containsObjectPropertyInSignature(sub.getIRI()),
            () -> "and neither may become an object property:\n" + written);
        assertEquals(o.getLogicalAxioms(), back.getLogicalAxioms(),
            () -> "the axiom must survive unchanged:\n" + written);
    }

    /**
     * A data property equivalence survives too.
     *
     * <p>Not covered by the fix above, because an {@code EquivalentDataProperties} axiom
     * counted as evidence that the name was a role. Rendered, it is `p ≡ q` — which is
     * exactly a class equivalence, and tells the reader nothing. Only a use that a class
     * could not have counts.
     */
    @Test
    void aDataPropertyEquivalenceSurvives() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/d#";
        manager.addAxiom(o, df.getOWLEquivalentDataPropertiesAxiom(
            df.getOWLDataProperty(IRI.create(ns + "p")),
            df.getOWLDataProperty(IRI.create(ns + "q"))));

        String written = write(o, ns);
        assertEquals(o.getLogicalAxioms(), parse(written).getLogicalAxioms(),
            () -> "the equivalence must stay a data property equivalence:\n" + written);
    }

    /**
     * An object property with only an equivalence, and a name the guess cannot reach.
     *
     * <p>The control for the test above: excluding equivalences from the evidence must not
     * silently stop applying to object properties. A prefixed name gets no help from the
     * reader's guess either, so it needs the statement just as much.
     */
    @Test
    void aPrefixedObjectPropertyEquivalenceSurvives() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/ex#";
        manager.addAxiom(o, df.getOWLEquivalentObjectPropertiesAxiom(
            df.getOWLObjectProperty(IRI.create(ns + "p")),
            df.getOWLObjectProperty(IRI.create(ns + "q"))));

        String written = write(o, "http://example.org/d#", "ex:", ns);
        assertEquals(o.getLogicalAxioms(), parse(written).getLogicalAxioms(),
            () -> "the equivalence must stay an object property equivalence:\n" + written);
    }

    // ── Classes the reader could never misread ──────────────────────────────

    /**
     * A prefixed lower-case class pair is marked, and round-trips exactly.
     *
     * <p>The case convention is about the local part, so `ex:cat ⊑ ex:animal` reads as a
     * role pair just as `cat ⊑ animal` does — which is why these two classes have to say
     * they are classes. The prefix used to disable the convention entirely; that made this
     * pair safe without statements, at the cost of `ex:hasPart ⊑ ex:related` being read as
     * classes and losing its axiom. The convention now applies either way, so whichever of
     * the two a document means, it survives.
     */
    @Test
    void aPrefixedLowerCaseClassPairIsMarkedAndRoundTrips() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/ex#";
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(ns + "cat")), df.getOWLClass(IRI.create(ns + "animal"))));

        String written = write(o, "http://example.org/d#", "ex:", ns);
        assertTrue(statementsOnly(written).contains("ex:cat ⊑ ⊤")
                && statementsOnly(written).contains("ex:animal ⊑ ⊤"),
            () -> "both names must say they are classes:\n" + written);

        OWLOntology back = parse(written);
        assertTrue(back.containsClassInSignature(IRI.create(ns + "cat")),
            () -> "and come back as classes:\n" + written);
        assertFalse(back.containsObjectPropertyInSignature(IRI.create(ns + "cat")),
            () -> "not as properties:\n" + written);
        assertTrue(back.getLogicalAxioms().containsAll(o.getLogicalAxioms()),
            () -> "nothing may be lost:\n" + written);
        // What is gained is the two statements themselves, read back as the ordinary axioms
        // they are. `X ⊑ ⊤` is only consumed as a marker when X is also stated to be a role;
        // for a name that is just a class it is indistinguishable from one an author wrote,
        // and consuming it would destroy that. Both are tautologies, so the cost is a line.
        Set<OWLLogicalAxiom> gained = new java.util.HashSet<>(back.getLogicalAxioms());
        gained.removeAll(o.getLogicalAxioms());
        assertTrue(gained.stream().allMatch(ax -> ax instanceof OWLSubClassOfAxiom
                && ((OWLSubClassOfAxiom) ax).getSuperClass().isOWLThing()),
            () -> "and nothing beyond the two ⊑ ⊤ tautologies: " + gained);
    }

    /**
     * And a prefixed lower-case property pair now needs no statement at all.
     *
     * <p>The other side of the same change: this is what the convention is for, so the
     * reader reaches it unaided and the writer stays quiet. Before, it either lost the
     * axiom or needed two statements to keep it.
     */
    @Test
    void aPrefixedLowerCasePropertyPairNeedsNoStatement() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/ex#";
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(IRI.create(ns + "hasPart")),
            df.getOWLObjectProperty(IRI.create(ns + "related"))));

        String written = write(o, "http://example.org/d#", "ex:", ns);
        assertFalse(statementsOnly(written).contains("owl:top"),
            () -> "the convention already says these are roles:\n" + written);
        assertEquals(o.getLogicalAxioms(), parse(written).getLogicalAxioms(),
            () -> "and the axiom must survive without one:\n" + written);
    }

    /**
     * A datatype is neither a class nor a role, whatever case its name is in.
     *
     * <p>The convention now reads a lower-case local part as a role, and the standard
     * datatypes are spelled in lower case — so without an exception `mytype ≡ owl:real`
     * would make both sides object properties. The four below are the only datatypes
     * outside {@code xsd:} whose names begin lower case; everything else the seeded
     * vocabularies call a class is capitalised, and their lower-case names really are
     * properties.
     *
     * <p>The last case is the reason the exception is keyed on the resolved IRI rather than
     * on the prefix text: the XSD namespace reached under some other prefix is still the XSD
     * namespace.
     */
    @Test
    void aLowerCaseDatatypeIsRecognisedAsADatatype() {
        // Refused as a class expression, exactly as xsd:string is — which is the positive
        // statement the old version of this test could not make. It asserted only that
        // `mytype` did not become a *role*, and passed while `owl:real` was being read as a
        // CLASS: the third wrong answer. A datatype is neither, and each of these is now
        // decided on its resolved IRI rather than on the `xsd:` prefix text.
        // A subsumption, not an equivalence. `mytype ≡ xsd:string` used to be the probe
        // here, and it is now a datatype alias — a legitimate DatatypeDefinition, which is a
        // better answer than a refusal. A datatype on the right of `⊑` is still nothing but
        // a class position, so that is the probe.
        // rdfs:Literal is deliberately not in this list. It is the top data range, and
        // `T ⊑ rdfs:Literal` is now the datatype counterpart of `C ⊑ ⊤` and
        // `r ⊑ owl:topObjectProperty` — a tautology that states a kind — so it is the one
        // datatype that does belong on the right of a `⊑`. See
        // {@link #theDatatypeMarkerStatesAKindAndProducesNoAxiom}.
        for (String datatype : new String[] {
                "xsd:string", "owl:real", "owl:rational",
                "rdf:langString", "rdf:dirLangString"}) {
            Throwable t = assertThrows(Throwable.class,
                () -> parse("@prefix : <http://example.org/t#>\nA ⊑ " + datatype + "\n"),
                () -> datatype + " is a datatype and cannot be a class expression");
            assertTrue(String.valueOf(t.getMessage()).contains("datatype " + datatype),
                () -> "the diagnostic must name it: " + t.getMessage());
        }
    }

    /**
     * {@code T ⊑ rdfs:Literal} says T is a datatype, and says nothing else.
     *
     * <p>The reader knows a built-in datatype by its namespace and one the document defines by
     * its definition. A name that is only declared is neither, so {@code ⊤ ⊑ ∀d.T} came back
     * as an object property range with T a class — the kind lost in both directions at once,
     * silently, and the whole family of shapes with it.
     *
     * <p>Every datatype lies beneath OWL 2's top data range, so the statement asserts nothing
     * that was not already true. Alone among the three kind markers it is not also an axiom:
     * OWL has no datatype subsumption, only {@code DatatypeDefinition}, which is an
     * equivalence and would say something far stronger. So it is consumed whole into a
     * declaration, and — unlike the property markers, which parse as real sub-property axioms
     * and are removed afterwards — there is nothing left to filter out. A round trip cannot
     * gain an axiom from it, which is the cost #32 accepted for the other two.
     */
    @Test
    void theDatatypeMarkerStatesAKindAndProducesNoAxiom() throws Exception {
        OWLOntology o = parse("@prefix : <http://example.org/t#>\n"
            + "T ⊑ rdfs:Literal\n");
        assertTrue(o.containsDatatypeInSignature(IRI.create("http://example.org/t#T")),
            () -> "the statement must declare T a datatype: " + o.getLogicalAxioms());
        assertFalse(o.containsClassInSignature(IRI.create("http://example.org/t#T")),
            () -> "and not a class as well: " + o.getLogicalAxioms());
        assertTrue(o.getLogicalAxioms().isEmpty(),
            () -> "the marker is not an axiom, so none should be produced: "
                + o.getLogicalAxioms());
    }

    /** And the answer does not depend on where in the file the statement sits. */
    @Test
    void theDatatypeMarkerWorksFromEitherSideOfItsUse() throws Exception {
        String marker = "T ⊑ rdfs:Literal\n";
        String use = "⊤ ⊑ ∀d.T\n";
        for (String document : new String[] {marker + use, use + marker}) {
            OWLOntology o = parse("@prefix : <http://example.org/t#>\n" + document);
            assertEquals(1, o.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
                () -> "d is a data property either way:\n" + document + o.getLogicalAxioms());
            assertEquals(0, o.getAxioms(AxiomType.OBJECT_PROPERTY_RANGE).size(),
                () -> "and never an object property:\n" + document + o.getLogicalAxioms());
        }
    }

    /**
     * A document may define its own datatype, and it is then a datatype everywhere.
     *
     * <p>The built-in list cannot know about one, so it was read as a class: a data property
     * ranged on it became an object property and the datatype itself became a class, with
     * the document loading cleanly. A definition is the strongest statement that a name is a
     * datatype and outranks both the list and the case convention.
     */
    @Test
    void aDatatypeTheDocumentDefinesIsADatatype() throws Exception {
        // The alias form, and the restriction form.
        for (String definition : new String[] {
                "MyType ≡ xsd:string\n",
                "MyType ≡ [xsd:string ⊓ [minLength 3]]\n"}) {
            OWLOntology o = parse("@prefix : <http://example.org/t#>\n" + definition
                + "⊤ ⊑ ∀d.MyType\n");
            assertEquals(1, o.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
                () -> definition.trim() + " makes d a data property: " + o.getLogicalAxioms());
            assertEquals(0, o.getAxioms(AxiomType.OBJECT_PROPERTY_RANGE).size(),
                () -> "and not an object property: " + o.getLogicalAxioms());
            assertTrue(o.datatypesInSignature()
                    .anyMatch(t -> t.getIRI().toString().endsWith("#MyType")),
                () -> "MyType must be a datatype: " + o.getLogicalAxioms());
        }
    }

    /**
     * A bare name in the default namespace resolves before the datatype test.
     *
     * <p>It used to resolve to nothing, which is invisible in the ordinary case — a bare name
     * in the document's own namespace is not a datatype either way — and wrong whenever the
     * default namespace is one that matters.
     */
    @Test
    void aBareNameInTheXsdNamespaceIsADatatype() throws Exception {
        OWLOntology o = parse("@prefix : <http://www.w3.org/2001/XMLSchema#>\n"
            + "⊤ ⊑ ∀p.string\n");
        assertEquals(1, o.getAxioms(AxiomType.DATA_PROPERTY_RANGE).size(),
            () -> "the bare `string` is xsd:string: " + o.getLogicalAxioms());
    }

    /** The XSD namespace is the XSD namespace whatever prefix reaches it. */
    @Test
    void theXsdNamespaceIsRecognisedUnderAnyPrefix() throws Exception {
        OWLOntology o = parse("@prefix xs2: <http://www.w3.org/2001/XMLSchema#>\n"
            + "@prefix : <http://example.org/t#>\nPerson ⊑ ∃name.xs2:string\n");
        assertTrue(o.containsDataPropertyInSignature(IRI.create("http://example.org/t#name")),
            () -> "a datatype filler makes the property a data property: "
                + o.getLogicalAxioms());
        assertFalse(o.containsObjectPropertyInSignature(
                IRI.create("http://example.org/t#name")),
            () -> o.getLogicalAxioms().toString());
    }

    /**
     * A document that rebinds {@code xsd:} elsewhere must not have its own names captured.
     *
     * <p>Nothing in that namespace is a datatype, so the exclusion must not apply and the
     * ordinary case convention decides — two lower-case local parts, therefore roles. The
     * point is that the test is on the resolved IRI: a text match on the `xsd:` prefix
     * would have called these datatypes.
     */
    @Test
    void aReboundXsdPrefixDoesNotMakeNamesDatatypes() throws Exception {
        OWLOntology o = parse("@prefix xsd: <http://example.org/dt/>\n"
            + "@prefix : <http://example.org/t#>\nmytype ≡ xsd:notAType\n");
        IRI notAType = IRI.create("http://example.org/dt/notAType");
        assertFalse(o.containsDatatypeInSignature(notAType),
            () -> "not a datatype: " + o.getLogicalAxioms());
        assertTrue(o.containsObjectPropertyInSignature(notAType),
            () -> "the case convention applies instead: " + o.getLogicalAxioms());
    }

    /** The control: a lower-case name that is not a datatype still reads as a role. */
    @Test
    void aLowerCaseNonDatatypePairStillReadsAsRoles() throws Exception {
        OWLOntology o = parse("@prefix : <http://example.org/t#>\nmytype ≡ otherthing\n");
        assertTrue(o.containsObjectPropertyInSignature(IRI.create("http://example.org/t#mytype")),
            () -> "the convention must still apply: " + o.getLogicalAxioms());
    }

    /**
     * Three role-shaped forms are not evidence of <em>which</em> kind of role.
     *
     * <p>{@code Func(p)}, {@code Disj(p, q)} and the domain idiom {@code ∃p.⊤ ⊑ C} are
     * written identically for object and data properties. An object property can rely on
     * them, because a role with no data evidence is what the reader guesses object from —
     * but for a data property they say only "role", and the reader then guesses wrong. Each
     * of these silently and stably turned a data property into an object property.
     */
    @Test
    void aDataPropertyIsStatedDespiteRoleShapedEvidence() throws Exception {
        for (String extra : new String[] {"Func(p)", "Disj(p, q)", "∃p.⊤ ⊑ C"}) {
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            OWLOntology o = m.createOntology();
            OWLDataFactory df = m.getOWLDataFactory();
            String ns = "http://example.org/d#";
            OWLDataProperty p = df.getOWLDataProperty(IRI.create(ns + "p"));
            m.addAxiom(o, df.getOWLDeclarationAxiom(p));
            if (extra.startsWith("Func")) {
                m.addAxiom(o, df.getOWLFunctionalDataPropertyAxiom(p));
            } else if (extra.startsWith("Disj")) {
                m.addAxiom(o, df.getOWLDisjointDataPropertiesAxiom(p,
                    df.getOWLDataProperty(IRI.create(ns + "q"))));
            } else {
                m.addAxiom(o, df.getOWLDataPropertyDomainAxiom(p,
                    df.getOWLClass(IRI.create(ns + "C"))));
            }

            String written = write(o, ns);
            OWLOntology back = parse(written);
            assertTrue(back.containsDataPropertyInSignature(p.getIRI()),
                () -> "p must stay a data property with " + extra + ":\n" + written);
            assertFalse(back.containsObjectPropertyInSignature(p.getIRI()),
                () -> "and must not become an object property with " + extra + ":\n" + written);
        }
    }

    /**
     * The property above a pun is told what it is.
     *
     * <p>A punned name carries a concept statement, which puts it in the reader's class
     * barrier — and that barrier propagates <em>up</em>, so the property above the pun was
     * read as a concept and the sub-property axiom between them became a subsumption. The
     * case convention cannot rescue it: that guess needs both sides of the pair to look
     * like roles, and a pun has been explicitly marked a concept.
     */
    @Test
    void thePropertyAboveAPunIsStated() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        String ns = "http://example.org/d#";
        IRI pun = IRI.create(ns + "P");
        OWLObjectProperty r = df.getOWLObjectProperty(IRI.create(ns + "r"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(pun)));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(pun)));
        m.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(df.getOWLObjectProperty(pun), r));

        String written = write(o, ns);
        assertTrue(statementsOnly(written).contains("r ⊑ owl:topObjectProperty"),
            () -> "the super needs its kind stated:\n" + written);
        assertTrue(parse(written).getAxioms(AxiomType.SUB_OBJECT_PROPERTY).stream()
                .anyMatch(ax -> ax.getSuperProperty().equals(r)),
            () -> "so the sub-property axiom survives:\n" + written);
    }

    /** The built-in vocabulary never gets a kind statement about itself. */
    @Test
    void theBuiltInVocabularyIsNeverStated() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        OWLDataFactory df = m.getOWLDataFactory();
        String ns = "http://example.org/d#";
        IRI pun = IRI.create(ns + "Attr");
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(pun)));
        m.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(pun), df.getOWLTopObjectProperty()));

        String body = statementsOnly(write(o, ns));
        assertFalse(body.contains("owl:topObjectProperty ⊑"),
            () -> "OWL already fixes its kind:\n" + body);
    }

    // ── Statements that cannot be spelled, or must not be written ───────────

    /**
     * A document that binds {@code owl:} elsewhere does not get a corrupt statement.
     *
     * <p>The writer asked the renderer for a short form and trusted it. OWL API's prefix
     * manager pre-seeds the OWL namespace in both directions and {@code setPrefix} replaces
     * only the forward entry, so the renderer still answered {@code owl:topObjectProperty}
     * — a name that, in this document, denotes something else entirely. The round trip
     * invented a class and an axiom and never converged.
     *
     * <p>With no prefix mapping the OWL namespace the statement is genuinely unwriteable —
     * DLe has no angle-bracket form in a name position — so the pun is not marked. That
     * loses the disambiguation, which is the lesser harm; the point of the test is that
     * nothing false is written and nothing is invented.
     */
    @Test
    void aReboundOwlPrefixWithNoAliasWritesNothingFalse() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/d#";
        IRI pun = IRI.create(ns + "attribute");
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(pun)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(pun)));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(ns + "Dog")),
            df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(pun),
                df.getOWLClass(IRI.create(ns + "Cat")))));

        // owl: bound away from the OWL namespace, with nothing else mapping it.
        String written = write(o, ns, "owl:", "http://example.com/notowl#");
        assertFalse(statementsOnly(written).contains("owl:topObjectProperty"),
            () -> "that name would mean notowl#topObjectProperty here:\n" + written);
        assertFalse(statementsOnly(written).contains("⊑ ⊤"),
            () -> "and the class half alone is worse than neither:\n" + written);

        OWLOntology back = parse(written);
        assertFalse(back.containsClassInSignature(
                IRI.create("http://example.com/notowl#topObjectProperty")),
            () -> "nothing may be invented in the rebound namespace:\n" + written);
    }

    /**
     * Whether a document can be written must not depend on an unrelated name's case.
     *
     * <p>Deciding whether a lower-case class needed marking meant asking the renderer for
     * the short form of the classes around it, using the throwing accessor. An IRI with no
     * declared prefix and no remainder has no short form, so `:thing1 ⊑ <urn:isbn:123>`
     * failed the save while `:Thing1 ⊑ <urn:isbn:123>` — which never asks the question —
     * saved cleanly.
     */
    @Test
    void anUnspellableNeighbourDoesNotFailTheSave() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://example.org/d#";
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(ns + "thing1")),
            df.getOWLClass(IRI.create("urn:isbn:123"))));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(ns + "Animal")), df.getOWLClass(IRI.create(ns + "thing1"))));

        String written = assertDoesNotThrow(() -> write(o, ns));
        assertTrue(statementsOnly(written).contains("Animal ⊑ thing1"), () -> written);
    }

    // ── Contradictions are reported, not settled ────────────────────────────

    /**
     * A name stated to be both an object and a data property is refused, citing both lines.
     *
     * <p>Both statements were honoured, giving one IRI two property declarations — punning
     * OWL 2 DL forbids, and which DLe cannot write. The reader then let data win, so a
     * document could turn its own object property axioms into data property ones. There is
     * no right answer to choose, so it is reported, and reported from the evidence rather
     * than from whichever axiom happened to be built second.
     */
    @Test
    void statingBothRoleKindsIsRefused() {
        String doc = "@prefix : <http://example.org/d#>\n"
            + "X ⊑ owl:topObjectProperty\n"
            + "X ⊑ owl:topDataProperty\n";
        Throwable t = assertThrows(Throwable.class, () -> parse(doc));
        String message = String.valueOf(t.getMessage());
        assertTrue(message.contains("used as an object property on line 2")
                && message.contains("data property on line 3"),
            () -> "expected both lines named, got: " + message);
    }
}
