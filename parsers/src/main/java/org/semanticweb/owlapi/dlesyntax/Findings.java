package org.semanticweb.owlapi.dlesyntax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * What a document says about the kind of each name, and how firmly it says it.
 *
 * <p>DLe names entities without stating what they are: {@code A ⊑ ∃r.B} does not say that
 * {@code r} is an object property rather than a data property, and OWL needs to know. So the
 * reader infers — and the inference has to keep two things apart that used to share one
 * representation.
 *
 * <p>Before this, a guess and a fact lived in the same sets. {@code objectPropertyNames} held
 * both "the document put this in a position only an object property can occupy" and "this
 * name is lower-case so it is probably a role", while a separate pair of maps held only the
 * firm evidence. Everything downstream saw the sets, so nothing could tell one from the
 * other. That is how the equivalence check came to refuse names that merely had no evidence
 * yet, and why "role of unknown kind" — what {@code Func(p)} and {@code Disj(p, q)} and
 * {@code ∃p.⊤ ⊑ C} actually tell you — had nowhere to live.
 *
 * <p>A finding records a kind, how it was arrived at, and the line it came from. A name may
 * gather several, and a conflict is two firm findings that disagree.
 *
 * <p><b>What this does not yet do.</b> The firmest finding is not yet what the rest of the
 * reader consults. Resolution still lives in the scanner's name sets, which propagation
 * walks and the visitor is handed; findings are the evidence those sets are built from, and
 * the sole authority on how firm that evidence is. Making the firmest finding the operative
 * answer is the other half of §4.1 and is not done.
 *
 * <p>See {@code docs/inference-design.md} §4.1.
 */
final class Findings {

    /** The kinds a name may have. */
    enum Kind {
        CLASS("a class"),
        OBJECT_PROPERTY("an object property"),
        DATA_PROPERTY("a data property"),
        DATATYPE("a datatype"),
        ANNOTATION_PROPERTY("an annotation property");

        private final String description;

        Kind(String description) {
            this.description = description;
        }

        /** For a diagnostic: "d is used as a data property on line 2". */
        String description() {
            return description;
        }

        /**
         * Whether this is one of the three kinds OWL 2 DL keeps disjoint.
         *
         * <p>Object, data and annotation properties must not share an IRI. A class may share
         * one with a property — that is the pun DLe exists to carry — so the disjointness
         * is between these three and not between kinds in general.
         */
        boolean isProperty() {
            return this == OBJECT_PROPERTY || this == DATA_PROPERTY
                || this == ANNOTATION_PROPERTY;
        }
    }

    /**
     * How firmly a kind is known, weakest first.
     *
     * <p>Declared in this order so that {@code compareTo} means "firmer than", and so the
     * priority in §2 of the design is a property of the type rather than a convention
     * remembered at each use.
     */
    enum Certainty {
        /** Nothing said which kind of role; object property is the fallback. */
        DEFAULTED,
        /** The case convention suggested it. A guess, and never a conflict. */
        GUESSED,
        /** Reached by a subsumption or equivalence from a name that had firmer evidence. */
        PROPAGATED,
        /** The document defines it — a datatype definition, or a predicate definition. */
        DECLARED,
        /** A position only this kind can occupy: a datatype filler, an inverse, a chain. */
        POSITIONAL,
        /** Said outright, by `X ⊑ owl:topObjectProperty` or its data equivalent. */
        STATED;

        /**
         * Whether a finding at this certainty is evidence rather than a guess.
         *
         * <p>A guess loses to evidence silently; two pieces of evidence that disagree are a
         * contradiction in the document and are reported. Keeping that distinction in the
         * type is the whole point of this class.
         */
        boolean isEvidence() {
            return compareTo(PROPAGATED) >= 0;
        }
    }

    /** One thing the document said about one name. */
    static final class Finding {
        final Kind kind;
        final Certainty certainty;
        final int line;

        Finding(Kind kind, Certainty certainty, int line) {
            this.kind = kind;
            this.certainty = certainty;
            this.line = line;
        }

        @Override
        public String toString() {
            return kind + "/" + certainty + "@" + line;
        }
    }

    /** Insertion-ordered so diagnostics name lines in the order the reader met them. */
    private final Map<String, List<Finding>> byName = new LinkedHashMap<>();

    /**
     * Records what a position said about a name.
     *
     * <p>Recording the same kind twice is harmless and common — a name may sit in several
     * positions that agree — and the firmest wins.
     */
    void record(String name, Kind kind, Certainty certainty, int line) {
        byName.computeIfAbsent(name, k -> new ArrayList<>())
            .add(new Finding(kind, certainty, line));
    }

    /** Every finding for a name, in the order they were met. */
    List<Finding> of(String name) {
        return byName.getOrDefault(name, java.util.Collections.emptyList());
    }

    /** Every name anything has been recorded about. */
    Iterable<String> names() {
        return byName.keySet();
    }


    /** The firmest finding of a given kind, or null. Used to name a line in a diagnostic. */
    @Nullable
    Finding firmestOf(String name, Kind kind) {
        return of(name).stream()
            .filter(f -> f.kind == kind)
            .max(Comparator.comparing(f -> f.certainty))
            .orElse(null);
    }

    /** Whether a name has evidence — not merely a guess — for a kind. */
    boolean hasEvidenceFor(String name, Kind kind) {
        return of(name).stream().anyMatch(f -> f.kind == kind && f.certainty.isEvidence());
    }

    /**
     * The two property kinds a name has firm evidence for, if it contradicts itself.
     *
     * <p>Only the three property kinds, because only they have to be disjoint: a name that
     * is both a class and a property is a pun, which DLe carries on purpose. A guess is
     * never half of a conflict — it loses to the evidence instead.
     *
     * @return the two conflicting findings, firmest of each kind, or null if consistent
     */
    @Nullable
    Finding[] propertyKindConflict(String name) {
        List<Finding> evidence = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (!kind.isProperty()) continue;
            Finding found = of(name).stream()
                .filter(f -> f.kind == kind && f.certainty.isEvidence())
                .max(Comparator.comparing(f -> f.certainty))
                .orElse(null);
            if (found != null) evidence.add(found);
        }
        if (evidence.size() < 2) return null;
        // Two is enough to report; a third would say nothing further.
        return new Finding[] {evidence.get(0), evidence.get(1)};
    }
}
