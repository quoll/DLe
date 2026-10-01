package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.*;

import java.util.List;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name that is both a class and a role — a pun — must survive a round trip, and must
 * not drag the classes above it into the role hierarchy.
 *
 * <p>DL writes class subsumption and sub-property subsumption identically as {@code a ⊑ b},
 * so the reader infers which hierarchy a pair belongs to. Structure settles it where there
 * is any; otherwise the convention that concepts are capitalised does. SNOMED CT defeats
 * both: its identifiers are numeric, and its attribute roots are declared classes while
 * also parenting object properties. Left alone, one such name turned four ancestor classes
 * into object properties.
 *
 * <p>The kinds are therefore stated outright, in existing DL and OWL vocabulary:
 * {@code X ⊑ ⊤} and {@code X ⊑ owl:topObjectProperty}. A name carrying both is punned.
 */
class PunnedNameTest {

    private static final String NS = "http://example.org/t#";
    /** Numeric identifiers live under their own prefix, as SNOMED CT's do under sct:. */
    private static final String N = "http://example.org/n#";
    private static final String PREFIX = "@prefix : <" + NS + ">\n";

    private OWLOntology parse(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        new DLEOntologyParser().parse(new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        return ontology;
    }

    /** Looks in both namespaces: numeric identifiers live under n:, named ones under the default. */
    private boolean isClass(OWLOntology o, String local) {
        return o.containsClassInSignature(IRI.create(N + local))
            || o.containsClassInSignature(IRI.create(NS + local));
    }

    private boolean isRole(OWLOntology o, String local) {
        for (IRI iri : new IRI[] {IRI.create(N + local), IRI.create(NS + local)}) {
            if (o.containsObjectPropertyInSignature(iri)
                    || o.containsDataPropertyInSignature(iri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pun on a numeric identifier, the SNOMED CT shape: {@code n:7} parents a role and is
     * also a class, with two ordinary classes above it.
     *
     * <p>The identifiers must be numeric to be representative. A local name beginning with a
     * lower-case *letter* is classified a role by convention before any of this is reached,
     * so a name like {@code n7} would not exercise the ambiguity at all — which is what a
     * first version of this fixture did.
     */
    private static final String PUN =
        PREFIX
        + "@prefix n: <" + N + ">\n"
        + "Tumour ≡ ∃n:1.Malignant\n"
        + "@label n:1 \"an attribute\"\n"
        + "n:1 ⊑ n:7\n"
        + "n:7 ⊑ ⊤\n"
        + "n:7 ⊑ owl:topObjectProperty\n"
        + "n:7 ⊑ n:8\n"
        + "n:8 ⊑ n:9\n";

    // ── A pun must be usable, not merely declarable ─────────────────────────

    /**
     * The pun's class side must work in a class position.
     *
     * <p>This is the whole point and it did not work: a name resolves to one kind, so once
     * {@code X ⊑ owl:topObjectProperty} classified it a role it was a role everywhere, and
     * {@code ∃r.X} — the ordinary way a class gets used — rejected the entire document.
     * Stating the pun was the thing that broke the pun. The writer emitted such documents,
     * so the tool produced DLe it could not read.
     */
    @Test
    void aPunWorksAsAClassFiller() throws Exception {
        OWLOntology o = parse(PREFIX
            + "Attr ⊑ ⊤\n"
            + "Attr ⊑ owl:topObjectProperty\n"
            + "A ⊑ ∃rel.Attr\n");
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "A")),
                df.getOWLObjectSomeValuesFrom(
                    df.getOWLObjectProperty(IRI.create(NS + "rel")),
                    df.getOWLClass(IRI.create(NS + "Attr"))))),
            () -> "the pun must be usable as a filler: " + o.getLogicalAxioms());
        assertTrue(o.containsObjectPropertyInSignature(IRI.create(NS + "Attr")),
            "and must still be a role");
    }

    /** Every complex class expression, not just restrictions. */
    @Test
    void aPunWorksInEveryClassExpression() throws Exception {
        for (String rhs : new String[] {"{Q}", "A ⊓ B", "A ⊔ B", "¬A", "∀rel.A", "≥2 rel.A"}) {
            String doc = PREFIX + "p ⊑ owl:topObjectProperty\np ⊑ " + rhs + "\n";
            OWLOntology o = parse(doc);
            assertFalse(o.getLogicalAxioms().isEmpty(), () -> "no axioms for: p ⊑ " + rhs);
        }
    }

    /** A punned ontology must survive the writer, which is where this was discovered. */
    @Test
    void aPunWithAClassAxiomRoundTrips() throws Exception {
        String written = rewrite(PREFIX
            + "p ⊑ owl:topObjectProperty\n"
            + "p ⊑ ∃hasPart.Leg\n"
            + "sub1 ⊑ p\n");
        OWLOntology back = parse(written);
        assertTrue(back.containsClassInSignature(IRI.create(NS + "p"))
                && back.containsObjectPropertyInSignature(IRI.create(NS + "p")),
            () -> "the pun must survive: " + written);
        assertEquals(written, rewrite(written), "and writing must be idempotent");
    }

    /** A concept child of a pun must not be dragged into the role hierarchy. */
    @Test
    void aClassBeneathAPunStaysAClass() throws Exception {
        String written = rewrite(PREFIX
            + "Attr ⊑ ⊤\n"
            + "Attr ⊑ owl:topObjectProperty\n"
            + "Finding ⊑ Attr\n"
            + "A ⊑ ∃Attr.B\n");
        OWLOntology back = parse(written);
        assertTrue(back.containsClassInSignature(IRI.create(NS + "Finding")),
            () -> "the writer must mark it, so the reader keeps it a class:\n" + written);
        assertFalse(back.containsObjectPropertyInSignature(IRI.create(NS + "Finding")),
            () -> "it was never a role:\n" + written);
    }

    /**
     * The kind of property a pun is must not change the answer for its children.
     *
     * <p>Read directly, without the {@code ⊑ ⊤} marker the writer would add: the case guess
     * below a pun was applied on the object-property edge only, so this exact shape made
     * {@code Upper} a class under a punned object property and a <em>data property</em>
     * under a punned data one. Nothing in the document distinguishes the two readings, so
     * neither should the classifier.
     */
    @Test
    void aCapitalisedChildOfAPunIsAClassWhicheverKindThePunIs() throws Exception {
        for (String top : new String[] {"owl:topObjectProperty", "owl:topDataProperty"}) {
            OWLOntology o = parse(PREFIX
                + "pun ⊑ ⊤\n"
                + "pun ⊑ " + top + "\n"
                + "Upper ⊑ pun\n"
                + "lower ⊑ pun\n");
            IRI upper = IRI.create(NS + "Upper");
            assertTrue(o.containsClassInSignature(upper),
                () -> "a capitalised child of a pun must stay a class under " + top
                    + ": " + o.getLogicalAxioms());
            assertFalse(o.containsDataPropertyInSignature(upper)
                    || o.containsObjectPropertyInSignature(upper),
                () -> "and must not also become a property under " + top
                    + ": " + o.getLogicalAxioms());
            // The lower-case sibling is the control: it still follows the pun's own kind.
            IRI lower = IRI.create(NS + "lower");
            assertFalse(o.containsClassInSignature(lower),
                () -> "a lower-case child must still be a role under " + top
                    + ": " + o.getLogicalAxioms());
        }
    }

    // ── Classification must not override direct evidence ────────────────────

    /**
     * A capitalised role below an ordinary role is still a role.
     *
     * <p>The case guess applies only below a <em>pun</em>, where both readings exist. Below a
     * pure role there is no class reading to offer, so the child must be a role whatever its
     * case. Applying the guess there turned this into a class subsumption, which is a
     * regression against documents that simply use PascalCase role names — a normal choice
     * that nothing should punish. It was found by asking whether any of this had disturbed
     * documents with no punning in them.
     */
    @Test
    void aCapitalisedRoleBelowAnOrdinaryRoleIsStillARole() throws Exception {
        OWLOntology o = parse(PREFIX + "A ⊑ ∃hasPart.B\nIsPartOf ⊑ hasPart\n");
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubObjectPropertyOfAxiom(
                df.getOWLObjectProperty(IRI.create(NS + "IsPartOf")),
                df.getOWLObjectProperty(IRI.create(NS + "hasPart")))),
            () -> "a pure role's child must stay a role: " + o.getLogicalAxioms());
        assertFalse(o.containsClassInSignature(IRI.create(NS + "IsPartOf")),
            "and must not gain a class reading");
    }

    /**
     * A capitalised name used as a role is a role. An earlier revision treated the case of
     * the name as a barrier, which invented a pun and refiled the sub-property axiom.
     */
    @Test
    void aCapitalisedRoleStaysARole() throws Exception {
        OWLOntology o = parse(PREFIX + "HasPart ⊑ TopRel\nDog ⊑ ∃HasPart.Leg\n");
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubObjectPropertyOfAxiom(
                df.getOWLObjectProperty(IRI.create(NS + "HasPart")),
                df.getOWLObjectProperty(IRI.create(NS + "TopRel")))),
            () -> "a capitalised role pair must stay a role pair: " + o.getLogicalAxioms());
        assertFalse(o.containsClassInSignature(IRI.create(NS + "TopRel")),
            "and must not become a class");
    }

    /** Data-ness must respect the same barriers object-ness does. */
    @Test
    void dataPropagationStopsAtAClass() throws Exception {
        OWLOntology o = parse(PREFIX
            + "E ⊑ ∃hasName.xsd:string\n"
            + "hasName ⊑ Descriptor\n"
            + "A ⊑ ∃rel.Descriptor\n");
        assertTrue(o.containsClassInSignature(IRI.create(NS + "Descriptor")),
            "Descriptor is a restriction filler, so it is a class");
        assertFalse(o.containsDataPropertyInSignature(IRI.create(NS + "Descriptor")),
            () -> "data-ness must not cross it: " + o.getLogicalAxioms());
    }

    /** A data property under an object property cannot be built; say so clearly. */
    @Test
    void mixingPropertyKindsIsRejectedWithAUsefulMessage() {
        Exception thrown = assertThrows(Exception.class, () -> parse(PREFIX
            + "parentRole ⊑ owl:topObjectProperty\n"
            + "childProp ⊑ parentRole\n"
            + "childProp ⊑ owl:topDataProperty\n"));
        assertTrue(thrown.getMessage().contains("separate hierarchies"),
            () -> "should name the conflict, not blame a datatype: " + thrown.getMessage());
    }

    /** Parentheses are transparent for kind statements, in both passes. */
    @Test
    void aParenthesisedKindStatementIsStillAKindStatement() throws Exception {
        OWLOntology o = parse(PREFIX
            + "Attr ⊑ ⊤\n"
            + "Attr ⊑ (owl:topObjectProperty)\n"
            + "finding ⊑ Attr\n");
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubObjectPropertyOfAxiom(
                df.getOWLObjectProperty(IRI.create(NS + "finding")),
                df.getOWLObjectProperty(IRI.create(NS + "Attr")))),
            () -> "the scanner and the visitor must agree: " + o.getLogicalAxioms());
    }

    // ── Two prefixes for one namespace ──────────────────────────────────────

    /**
     * Classification is keyed on the name as written, so two prefixes for one namespace can
     * misclassify an entity written both ways: the kind stated under one spelling never
     * pairs with the use under the other, and a sub-property axiom below it is refiled as a
     * class subsumption. Keying classification on resolved IRIs is the real fix and is a
     * change across the whole of it; the writer renders each IRI through one prefix, so this
     * cannot arise from a round trip and needs an authored document to reach. The warning
     * exists so that if one ever does, it is visible rather than a quietly wrong hierarchy.
     */
    @Test
    void twoPrefixesForOneNamespaceAreReported() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();
        parser.parse(new StringDocumentSource(
                "@prefix : <" + NS + ">\n@prefix a: <" + NS + ">\nA ⊑ B\n"),
            ontology, manager.getOntologyLoaderConfiguration());
        assertEquals(1, parser.getWarnings().size(),
            () -> "expected one warning, got " + parser.getWarnings());
        assertTrue(parser.getWarnings().get(0).contains("one prefix per namespace"),
            () -> parser.getWarnings().get(0));
    }

    /** A second prefix for a standard namespace is supported, and must stay quiet. */
    @Test
    void aSecondPrefixForAStandardNamespaceIsNotReported() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();
        parser.parse(new StringDocumentSource(PREFIX
                + "@prefix o: <http://www.w3.org/2002/07/owl#>\n"
                + "r ⊑ o:topObjectProperty\nA ⊑ ∃r.B\n"),
            ontology, manager.getOntologyLoaderConfiguration());
        assertEquals(List.of(), parser.getWarnings(),
            "binding another prefix to the OWL namespace is a supported thing to do");
    }

    @Test
    void anOrdinaryDocumentReportsNothing() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        DLEOntologyParser parser = new DLEOntologyParser();
        parser.parse(new StringDocumentSource(PREFIX
                + "@prefix ex: <http://other.example/x#>\nA ⊑ ex:B\n"),
            ontology, manager.getOntologyLoaderConfiguration());
        assertEquals(List.of(), parser.getWarnings());
    }

    // ── The writer must mean what it writes ─────────────────────────────────

    /**
     * A capitalised role is left alone when the document already shows it is a role.
     *
     * <p>The restriction settles what {@code hasPart} is, and the pair then settles
     * {@code IsPartOf}, so no statement is needed. Writing one anyway put a line into every
     * document that uses PascalCase role names, for nothing.
     */
    @Test
    void aCapitalisedRoleWithRoleEvidenceNeedsNoStatement() throws Exception {
        String written = rewrite(PREFIX + "A ⊑ ∃hasPart.B\nIsPartOf ⊑ hasPart\n");
        assertFalse(statementsOnly(written).contains("IsPartOf ⊑ owl:"),
            () -> "nothing here is ambiguous:\n" + written);
        assertEquals(parse(PREFIX + "A ⊑ ∃hasPart.B\nIsPartOf ⊑ hasPart\n").getLogicalAxioms(),
            parse(written).getLogicalAxioms(), () -> "and it must still round-trip:\n" + written);
    }

    /**
     * But it is stated when nothing shows it. {@code Studies ⊑ rel} on its own gives the
     * reader no reason to call either name a role, so without the statement the pair comes
     * back as a class subsumption and the sub-property axiom is lost.
     */
    @Test
    void aCapitalisedRoleWithNoRoleEvidenceIsStated() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(IRI.create(NS + "Studies")),
            df.getOWLObjectProperty(IRI.create(NS + "rel"))));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(statementsOnly(written).contains("Studies ⊑ owl:topObjectProperty"),
            () -> "without this the sub-property axiom is lost:\n" + written);
        assertEquals(o.getLogicalAxioms(), parse(written).getLogicalAxioms(),
            () -> "the axiom must survive:\n" + written);
    }

    /**
     * The top property is rendered through the prefix manager, not hard-coded as "owl:…".
     * The reader resolves it to an IRI for exactly this reason; the writer emitting literal
     * text in a document that binds {@code owl:} elsewhere lost three axioms and invented two.
     */
    @Test
    void aReboundOwlPrefixDoesNotCorruptWhatIsWritten() throws Exception {
        String doc = PREFIX
            + "@prefix owl: <http://evil.example/#>\n"
            + "@prefix o: <http://www.w3.org/2002/07/owl#>\n"
            + "r ⊑ o:topObjectProperty\n"
            + "r ⊑ ⊤\n"
            + "Dog ⊑ ∃r.Cat\n";
        String written = rewrite(doc);
        assertFalse(statementsOnly(written).contains("owl:topObjectProperty"),
            () -> "that spelling means <http://evil.example/#topObjectProperty> here:\n" + written);
        assertEquals(parse(doc).getLogicalAxioms(), parse(written).getLogicalAxioms(),
            () -> "the round trip must be lossless:\n" + written);
    }

    /**
     * An IRI that is both kinds of property states neither, and stays readable.
     *
     * <p>It used to state both. DLe has one statement per role kind and a name can only
     * have one, so the two lines contradict each other — the reader resolved the
     * contradiction by letting data win, quietly turning object property axioms into data
     * property ones, and now refuses the pair outright. Writing just one was no better:
     * which one appeared depended on which entity the base storer happened to ask about,
     * and the result failed to re-read with a message about datatypes.
     *
     * <p>So nothing is written, and the reader classifies from use. The data-property
     * declaration is lost, which is unavoidable — DLe cannot express this, and OWL 2 DL
     * forbids it, so nothing well-formed arrives here. What matters is that the document
     * the writer produces is one the reader can read.
     */
    @Test
    void anObjectAndDataPropertyStatesNeitherKind() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI x = IRI.create(NS + "X");
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(x)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(x)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLDataProperty(x)));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(0, countOccurrences(statementsOnly(written), "X ⊑ owl:topObjectProperty"),
            () -> "a contradictory pair must not be written:\n" + written);
        assertEquals(0, countOccurrences(statementsOnly(written), "X ⊑ owl:topDataProperty"),
            () -> "neither half of it:\n" + written);
        assertDoesNotThrow(() -> parse(written),
            () -> "and what is written must be readable:\n" + written);
    }

    /**
     * The same shape with a real object-property use: the axiom survives.
     *
     * <p>This is the case the old behaviour broke hardest. The writer emitted the data
     * statement — chosen by which entity the base class asked about, not by the evidence —
     * and the document then failed to parse at all, so the restriction was lost along with
     * everything after it.
     */
    @Test
    void aDualKindPropertyKeepsItsObjectUse() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI x = IRI.create(NS + "X");
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(x)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLDataProperty(x)));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(a,
            df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(x), a)));

        String written = writeOf(o);
        OWLOntology back = assertDoesNotThrow(() -> parse(written),
            () -> "the writer must not produce something it cannot read:\n" + written);
        assertTrue(back.containsObjectPropertyInSignature(x),
            () -> "the use settles the kind:\n" + written);
        assertTrue(back.getAxioms(AxiomType.SUBCLASS_OF).stream()
                .anyMatch(ax -> ax.getSubClass().equals(a)),
            () -> "and the restriction must survive:\n" + written);
    }

    /**
     * An authored `X ⊑ owl:topObjectProperty` must not be written twice.
     *
     * <p>Built rather than parsed. Reading that line consumes it — the statement is the
     * marker, not content — so a parsed ontology never holds the axiom, the dedup guard is
     * never reached, and the count of one was guaranteed by there being one producer. The
     * guard only bites when the ontology arrives from somewhere other than DLe, which is
     * the case this now covers.
     */
    @Test
    void anAuthoredTopPropertySubsumptionIsWrittenOnce() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLObjectProperty attr = df.getOWLObjectProperty(IRI.create(NS + "Attr"));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(attr.getIRI())));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            attr, df.getOWLTopObjectProperty()));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(IRI.create(NS + "finding")), attr));

        String written = writeOf(o);
        assertEquals(1, countOccurrences(statementsOnly(written), "Attr ⊑ owl:topObjectProperty"),
            () -> "written twice, from two sources:\n" + written);
        assertEquals(written, rewrite(written), "writing must be idempotent");
    }

    // ── Review findings, each of these failed a first time ──────────────────

    /**
     * The pun's children must keep their sub-property axioms even with no annotations.
     *
     * <p>{@link DualDeclarationResolver} pushes a dual declaration down to any unannotated
     * sub-property, rewriting {@code SubObjectPropertyOf(Y,X)} as {@code SubClassOf(Y,X)} —
     * the exact corruption the kind statements exist to prevent. It is now told to leave a
     * stated kind alone. Before that, the mechanism worked only on documents whose entities
     * all carried labels, which every SNOMED CT extract does, so it looked correct.
     */
    @Test
    void aStatedPunIsNotUndoneWhenItsChildIsUnannotated() throws Exception {
        OWLOntology o = parse(PREFIX
            + "@prefix n: <" + N + ">\n"
            + "n:1 ⊑ owl:topObjectProperty\n"
            + "n:1 ⊑ ⊤\n"
            + "n:2 ⊑ n:1\n");
        assertTrue(o.containsAxiom(subObjectPropertyOf("2", "1")),
            () -> "the child must stay a sub-property: " + o.getLogicalAxioms());
        assertFalse(o.containsAxiom(subClassOf("2", "1")),
            "the pun must not be pushed down to the child");
    }

    /** The statement is recognised by IRI, so any prefix bound to the OWL namespace works. */
    @Test
    void theOwlNamespaceIsRecognisedUnderAnyPrefix() throws Exception {
        OWLOntology o = parse(PREFIX
            + "@prefix o: <http://www.w3.org/2002/07/owl#>\n"
            + "@prefix n: <" + N + ">\n"
            + "n:1 ⊑ o:topObjectProperty\n"
            + "n:2 ⊑ n:1\n");
        assertTrue(isRole(o, "1"), "o: is the OWL namespace, so this states a role");
        assertTrue(o.containsAxiom(subObjectPropertyOf("2", "1")),
            () -> o.getLogicalAxioms().toString());
    }

    /**
     * And the converse: a document that binds {@code owl:} elsewhere means what it says.
     *
     * <p>Matching the spelling rather than the IRI consumed this line, destroying the
     * subsumption and turning a class into a property, silently.
     */
    @Test
    void aRebound0wlPrefixIsNotAKindStatement() throws Exception {
        OWLOntology o = parse(PREFIX
            + "@prefix owl: <http://example.org/fake#>\n"
            + "MyClass ⊑ owl:topObjectProperty\n");
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "MyClass")),
                df.getOWLClass(IRI.create("http://example.org/fake#topObjectProperty")))),
            () -> "the subsumption must survive against the bound namespace: "
                + o.getLogicalAxioms());
        assertFalse(o.containsObjectPropertyInSignature(IRI.create(NS + "MyClass")),
            "MyClass must not be reclassified as a property");
    }

    /**
     * A `⊤` subsumption an author wrote must not be eaten, nor written twice.
     *
     * <p>Built rather than parsed, and paired with a <em>lower-case</em> super. The old
     * fixture used `lowerCase ⊑ Other`, for which the writer considers no statement at all:
     * a lower-case class is marked only when the name across the `⊑` is also one the reader
     * could take for a role, and `Other` is not. So the single occurrence was the authored
     * axiom being rendered, and the "written once" half could not fail. A lower-case pair is
     * not expressible in DLe text either — it reads as two roles — so the API is the only
     * way in. {@link #aLowerCaseClassPairIsMarked} is the control that proves the fixture
     * reaches the code.
     */
    @Test
    void anAuthoredTopSubsumptionSurvivesAndIsWrittenOnce() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass lower = df.getOWLClass(IRI.create(NS + "lowerCase"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(lower, df.getOWLClass(IRI.create(NS + "other"))));
        OWLAxiom authored = df.getOWLSubClassOfAxiom(lower, df.getOWLThing());
        manager.addAxiom(o, authored);

        String written = writeOf(o);
        assertEquals(1, countOccurrences(statementsOnly(written), "lowerCase ⊑ ⊤"),
            () -> "the kind statement and the axiom must not both be written:\n" + written);
        OWLOntology back = parse(written);
        assertTrue(back.containsAxiom(authored),
            () -> "the ⊤ subsumption must survive: " + back.getLogicalAxioms());
        assertEquals(written, rewrite(written), "writing must be idempotent");
    }

    /**
     * The control for the test above: without the authored axiom, the writer invents one.
     *
     * <p>A lower-case pair is exactly the shape the reader's guess claims as two roles, so
     * both names need marking. If this ever stops holding, the test above goes vacuous
     * again — its whole point is that a second source exists to be deduplicated against.
     */
    @Test
    void aLowerCaseClassPairIsMarked() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(IRI.create(NS + "lowerCase")),
            df.getOWLClass(IRI.create(NS + "other"))));

        String body = statementsOnly(writeOf(o));
        assertEquals(1, countOccurrences(body, "lowerCase ⊑ ⊤"), () -> body);
        assertEquals(1, countOccurrences(body, "other ⊑ ⊤"), () -> body);
    }

    /** Writes an ontology as DLe with the test namespace as the default prefix. */
    private String writeOf(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** An IRI that is a property and also an individual must state its kind once, not twice. */
    @Test
    void aPropertyThatIsAlsoAnIndividualStatesItsKindOnce() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/t"));
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI iri = IRI.create(NS + "Studies");
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(iri)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLNamedIndividual(iri)));
        manager.addAxiom(o, df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(iri), df.getOWLObjectProperty(IRI.create(NS + "rel"))));

        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(o, format, new StreamDocumentTarget(out));
        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(1, countOccurrences(statementsOnly(written), "Studies ⊑ owl:topObjectProperty"),
            () -> "the property pass and the individual pass both wrote it:\n" + written);
    }

    /**
     * The document with its {@code #} comment lines removed.
     *
     * <p>Necessary because the explanatory header now documents the kind statements using
     * their own syntax, so a naive search of the output finds the documentation.
     */
    private static String statementsOnly(String document) {
        StringBuilder out = new StringBuilder();
        for (String line : document.split("\n", -1)) {
            if (!line.trim().startsWith("#")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private OWLAxiom subObjectPropertyOf(String sub, String sup) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        return df.getOWLSubObjectPropertyOfAxiom(
            df.getOWLObjectProperty(IRI.create(N + sub)),
            df.getOWLObjectProperty(IRI.create(N + sup)));
    }

    // ── The defect ──────────────────────────────────────────────────────────

    @Test
    void thePunIsBothAClassAndARole() throws Exception {
        OWLOntology o = parse(PUN);
        assertTrue(isClass(o, "7"), "the punned name must still be a class");
        assertTrue(isRole(o, "7"), "the punned name must still be a role");
    }

    @Test
    void theClassesAboveThePunStayClasses() throws Exception {
        OWLOntology o = parse(PUN);
        for (String above : new String[] {"8", "9"}) {
            assertTrue(isClass(o, above), above + " must stay a class");
            assertFalse(isRole(o, above), above + " must not become a role");
        }
    }

    @Test
    void theSubsumptionsAboveThePunStayClassSubsumptions() throws Exception {
        OWLOntology o = parse(PUN);
        assertTrue(o.containsAxiom(subClassOf("7", "8")), o.getLogicalAxioms().toString());
        assertTrue(o.containsAxiom(subClassOf("8", "9")), o.getLogicalAxioms().toString());
    }

    @Test
    void theRolesBelowThePunStayRoles() throws Exception {
        // Downward classification must still cross the pun: being the parent of roles is
        // what makes the name punned in the first place.
        OWLOntology o = parse(PUN);
        assertTrue(isRole(o, "1"), "a role below the pun must stay a role");
    }

    // ── The statements are consumed, not turned into axioms ─────────────────

    @Test
    void theKindStatementsAddNoAxioms() throws Exception {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLOntology o = parse(PUN);
        assertFalse(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(N + "7")), df.getOWLThing())),
            "X ⊑ ⊤ for a punned name is a declaration, not a subsumption to owl:Thing");
        assertFalse(o.getLogicalAxioms().toString().contains("topObjectProperty"),
            "owl:topObjectProperty must not appear in an axiom: " + o.getLogicalAxioms());
    }

    // ── What must not change ────────────────────────────────────────────────

    @Test
    void anOrdinaryClassKeepsItsSubsumptionUnderTop() throws Exception {
        // Every corpus document opens its blocks with this. It must keep meaning exactly
        // what it has always meant — treating it as a pun marker is what made an earlier
        // attempt reject valid documents.
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLOntology o = parse(PREFIX + "Station ⊑ ⊤\n");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(NS + "Station")), df.getOWLThing())),
            "Station ⊑ ⊤ must stay a subsumption: " + o.getLogicalAxioms());
    }

    @Test
    void aRoleUnderACapitalisedClassDoesNotMakeItARole() throws Exception {
        // The case that an earlier attempt turned into a hard parse failure, because it
        // exempted every name written with `⊑ ⊤` from the class protection.
        OWLOntology o = parse(PREFIX
            + "Station ⊑ ⊤\n"
            + "∃connectsTo.⊤ ⊑ Station\n"
            + "⊤ ⊑ ∀connectsTo.Station\n"
            + "connectsTo ⊑ Station\n");
        assertTrue(isClass(o, "Station"), "Station must stay a class");
        assertFalse(isRole(o, "Station"), "Station must not become a role");
    }

    @Test
    void anOrdinaryRoleHierarchyStillPropagatesUpward() throws Exception {
        // Load-bearing for hand-written DLe: a parent role with no domain or range of its
        // own is normal, and must still be recognised as a role.
        OWLOntology o = parse(PREFIX
            + "Tumour ≡ ∃monitors.Malignant\n"
            + "@label monitors \"monitors\"\n"
            + "monitors ⊑ responsibility\n");
        assertTrue(isRole(o, "responsibility"),
            "an uncapitalised parent of a role must be a role");
    }

    // ── The writer states the kinds, and only where needed ──────────────────

    @Test
    void theWriterStatesAPunAndNothingElse() throws Exception {
        String written = rewrite(PUN);
        assertTrue(statementsOnly(written).contains("n:7 ⊑ owl:topObjectProperty"),
            "the pun's role kind must be stated:\n" + written);
        assertTrue(statementsOnly(written).contains("n:7 ⊑ ⊤"),
            "the pun's class kind must be stated:\n" + written);
        // n8 and n9 are unambiguous classes; n1 an unambiguous role.
        for (String quiet : new String[] {"n:8 ⊑ owl:top", "n:9 ⊑ owl:top", "n:1 ⊑ owl:top"}) {
            assertFalse(statementsOnly(written).contains(quiet),
                quiet + " should not be stated:\n" + written);
        }
    }

    @Test
    void theWriterStatesNothingForAnUnambiguousDocument() throws Exception {
        String written = rewrite(PREFIX
            + "Station ⊑ ⊤\n"
            + "∃connectsTo.⊤ ⊑ Station\n"
            + "⊤ ⊑ ∀connectsTo.Station\n");
        assertFalse(statementsOnly(written).contains("owl:topObjectProperty"), written);
        assertFalse(statementsOnly(written).contains("owl:topDataProperty"), written);
    }

    @Test
    void aPunSurvivesARoundTrip() throws Exception {
        OWLOntology first = parse(PUN);
        OWLOntology second = parse(rewrite(PUN));
        assertEquals(first.getLogicalAxioms(), second.getLogicalAxioms(),
            "a punned document must round-trip with the same axioms");
        assertTrue(isClass(second, "7") && isRole(second, "7"),
            "the pun must survive the round trip");
    }

    /**
     * A kind statement the writer invents must sit where the reader will put its axiom.
     *
     * <p>{@code X ⊑ ⊤} comes back from the reader as a real {@code SubClassOf(X, owl:Thing)},
     * so on the next write it is rendered from that axiom and sorted into the block by its
     * text. Printing the statement above the block instead put it somewhere the axiom never
     * goes, and the first write disagreed with the second — one line out of place, then
     * stable from the second pass on.
     *
     * <p>The ontology is built directly rather than parsed from DLe, because the statement
     * has to be <em>invented</em> by the writer for this to bite. Written in DLe the
     * document would already contain it, the writer would suppress its own copy, and there
     * would be nothing out of place. A lower-case class under a pun is also not something
     * DLe text can say — the reader would classify it as a role, which is the very problem
     * the statement exists to prevent.
     */
    @Test
    void anInventedKindStatementSitsWhereItsAxiomWillRender() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology o = manager.createOntology();
        OWLDataFactory df = manager.getOWLDataFactory();
        IRI pun = IRI.create(NS + "attr");
        IRI child = IRI.create(NS + "child");
        // A pun: one IRI, both kinds.
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(pun)));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(pun)));
        // A class beneath it whose name gives the reader no help.
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(
            df.getOWLClass(child), df.getOWLClass(pun)));
        // And a second axiom in the same block, for the statement to be ordered against.
        // It has to be one no other entity can claim: the base storer writes each axiom in
        // the first block that reaches it, and properties are written before classes, so an
        // axiom naming anything else ends up elsewhere and this block is left with a single
        // line — nothing to be out of order with. Mentioning only `child` is what keeps it
        // here. That it is also unsatisfiable does not matter; nothing reasons over it.
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(child),
            df.getOWLObjectComplementOf(df.getOWLClass(child))));

        String first = write(o);
        assertTrue(statementsOnly(first).contains("child ⊑ ⊤"),
            () -> "the writer must invent the statement, or this proves nothing:\n" + first);
        assertTrue(statementsOnly(first).contains("child ⊑ ¬child"),
            () -> "and the block needs a second line, or there is no order to get wrong:\n"
                + first);
        assertEquals(statementsOnly(first), statementsOnly(rewrite(first)),
            () -> "writing must be idempotent from the first pass:\n" + first);
    }

    /** Writes an ontology as DLe, with the default prefix bound so names are short. */
    private String write(OWLOntology o) throws Exception {
        DLESyntaxDocumentFormat format = new DLESyntaxDocumentFormat();
        format.setDefaultPrefix(NS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        o.getOWLOntologyManager().saveOntology(o, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private String rewrite(String document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology();
        OWLDocumentFormat format = new DLEOntologyParser().parse(
            new StringDocumentSource(document), ontology,
            manager.getOntologyLoaderConfiguration());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, format, new StreamDocumentTarget(out));
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OWLAxiom subClassOf(String sub, String sup) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(N + sub)),
                                        df.getOWLClass(IRI.create(N + sup)));
    }
}
