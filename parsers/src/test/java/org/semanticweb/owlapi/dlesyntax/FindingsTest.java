package org.semanticweb.owlapi.dlesyntax;

import org.junit.jupiter.api.Test;

import static org.semanticweb.owlapi.dlesyntax.Findings.Certainty.*;
import static org.semanticweb.owlapi.dlesyntax.Findings.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The priority and conflict rules, on their own.
 *
 * <p>Tested here rather than only through a document, because these two rules are what the
 * rest of the inference rests on: which finding wins, and which pairs are contradictions
 * rather than puns. A bug in either would show up somewhere far away and look like something
 * else.
 */
class FindingsTest {

    /** Firmer evidence wins, whatever order it arrived in. */
    @Test
    void theFirmestFindingWins() {
        Findings f = new Findings();
        f.record("d", OBJECT_PROPERTY, GUESSED, 1);
        f.record("d", DATA_PROPERTY, POSITIONAL, 2);
        assertEquals(DATA_PROPERTY, f.firmestOf("d", DATA_PROPERTY).kind,
            "a position beats the case convention");
        assertFalse(f.hasEvidenceFor("d", OBJECT_PROPERTY),
            "and the guess is not evidence for the other kind");

        Findings reversed = new Findings();
        reversed.record("d", DATA_PROPERTY, POSITIONAL, 2);
        reversed.record("d", OBJECT_PROPERTY, GUESSED, 1);
        assertFalse(reversed.hasEvidenceFor("d", OBJECT_PROPERTY),
            "and the order it was met in makes no difference");
    }

    /** The whole priority order, so the ranking is pinned rather than assumed. */
    @Test
    void theOrderIsWeakestToFirmest() {
        assertTrue(DEFAULTED.compareTo(GUESSED) < 0, "a default is weaker than a guess");
        assertTrue(GUESSED.compareTo(PROPAGATED) < 0, "a guess is weaker than propagation");
        assertTrue(PROPAGATED.compareTo(DECLARED) < 0, "propagation is weaker than a declaration");
        assertTrue(DECLARED.compareTo(POSITIONAL) < 0, "a declaration is weaker than a position");
        assertTrue(POSITIONAL.compareTo(STATED) < 0, "and saying it outright is firmest");
    }

    /** A guess is not evidence; everything from propagation upward is. */
    @Test
    void onlyEvidenceCounts() {
        assertFalse(DEFAULTED.isEvidence(), "the fallback is not evidence");
        assertFalse(GUESSED.isEvidence(), "nor is the case convention");
        assertTrue(PROPAGATED.isEvidence());
        assertTrue(DECLARED.isEvidence());
        assertTrue(POSITIONAL.isEvidence());
        assertTrue(STATED.isEvidence());
    }

    /** Two firm findings of different property kinds contradict each other. */
    @Test
    void twoPropertyKindsWithEvidenceConflict() {
        Findings f = new Findings();
        f.record("d", DATA_PROPERTY, POSITIONAL, 2);
        f.record("d", OBJECT_PROPERTY, POSITIONAL, 3);
        Findings.Finding[] clash = f.propertyKindConflict("d");
        assertNotNull(clash, "one IRI cannot be both kinds of property");
        assertEquals(2, clash.length);
        assertTrue(clash[0].line == 2 || clash[1].line == 2, "and both lines are available");
        assertTrue(clash[0].line == 3 || clash[1].line == 3);
    }

    /**
     * A class and a property is a pun, not a conflict.
     *
     * <p>DLe carries these deliberately — SNOMED CT's attribute roots are both — so the
     * disjointness is between the three property kinds and not between kinds in general.
     */
    @Test
    void aClassAndAPropertyIsNotAConflict() {
        Findings f = new Findings();
        f.record("Attr", CLASS, POSITIONAL, 1);
        f.record("Attr", OBJECT_PROPERTY, STATED, 2);
        assertNull(f.propertyKindConflict("Attr"), "a pun is not a contradiction");
    }

    /** A guess is never half of a conflict; it loses to the evidence instead. */
    @Test
    void aGuessIsNeverHalfOfAConflict() {
        Findings f = new Findings();
        f.record("d", DATA_PROPERTY, POSITIONAL, 2);
        f.record("d", OBJECT_PROPERTY, GUESSED, 1);
        assertNull(f.propertyKindConflict("d"),
            "an unclassified name that merely looks like a role is not a contradiction");
        assertTrue(f.hasEvidenceFor("d", DATA_PROPERTY),
            "and the evidence decides it");
    }

    /** Annotation properties are the third disjoint kind, not an exception to the rule. */
    @Test
    void anAnnotationPropertyConflictsWithTheOtherTwo() {
        for (Findings.Kind other : new Findings.Kind[] {OBJECT_PROPERTY, DATA_PROPERTY}) {
            Findings f = new Findings();
            f.record("p", ANNOTATION_PROPERTY, POSITIONAL, 1);
            f.record("p", other, POSITIONAL, 2);
            assertNotNull(f.propertyKindConflict("p"),
                () -> "annotation property against " + other + " is a conflict");
        }
    }

    /** The same kind recorded twice is agreement, not a conflict. */
    @Test
    void agreementIsNotAConflict() {
        Findings f = new Findings();
        f.record("r", OBJECT_PROPERTY, POSITIONAL, 1);
        f.record("r", OBJECT_PROPERTY, STATED, 2);
        assertNull(f.propertyKindConflict("r"));
        assertEquals(STATED, f.firmestOf("r", OBJECT_PROPERTY).certainty,
            "and the firmer of the two is what is known");
    }

    /** Nothing recorded means nothing known — not a default masquerading as an answer. */
    @Test
    void anUnknownNameHasNoFinding() {
        Findings f = new Findings();
        assertNull(f.firmestOf("nothing", OBJECT_PROPERTY));
        assertNull(f.propertyKindConflict("nothing"));
        assertFalse(f.hasEvidenceFor("nothing", OBJECT_PROPERTY));
    }

    /** hasEvidenceFor ignores guesses, which is what separates it from membership. */
    @Test
    void hasEvidenceForIgnoresGuesses() {
        Findings f = new Findings();
        f.record("r", OBJECT_PROPERTY, GUESSED, 1);
        assertFalse(f.hasEvidenceFor("r", OBJECT_PROPERTY),
            "looking like a role is not evidence of being one");
        f.record("r", OBJECT_PROPERTY, POSITIONAL, 2);
        assertTrue(f.hasEvidenceFor("r", OBJECT_PROPERTY));
    }

    /** A diagnostic needs the line, so the firmest of a given kind is reachable. */
    @Test
    void theLineForAKindIsAvailable() {
        Findings f = new Findings();
        f.record("d", DATA_PROPERTY, GUESSED, 7);
        f.record("d", DATA_PROPERTY, POSITIONAL, 9);
        assertEquals(9, f.firmestOf("d", DATA_PROPERTY).line,
            "the firmest finding of that kind is the one worth naming");
        assertNull(f.firmestOf("d", CLASS));
    }
}
