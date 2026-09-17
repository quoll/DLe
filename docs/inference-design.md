# Entity kind inference

DLe names entities without saying what they are. `A ⊑ B` does not state that `A` and `B` are
classes, and `A ⊑ ∃r.B` does not state that `r` is a property — let alone which kind of
property. OWL requires all of it: a reasoner needs to know that `r` is an object property and
not a data property, and OWL 2 DL requires the class, object-property, data-property,
datatype and annotation-property IRI sets to be pairwise disjoint.

So the reader infers. This document is the design for that inference: what it decides, what it
decides from, in what order, and how the writer stays in step with it.

It is a design, not a description. Where it differs from the code today, the difference is
the work.

---

## 1. What has to be decided

For every name in a document, one of:

| kind | example position |
|---|---|
| class | `A ⊑ B`, the filler of `∃r.C` |
| object property | `r` in `A ⊑ ∃r.B` |
| data property | `d` in `A ⊑ ∃d.xsd:string` |
| datatype | `xsd:string`, or a name declared as one |
| annotation property | `p` in `@ann A p "v"` |
| individual | `a` in `a : C`, `(a,b):r` |
| predicate | a DLe extension; `predicateNames` |

Individuals and predicates are decided by position alone and are not at issue. The other five
are, and three of them — object property, data property, annotation property — are spelled
identically in most positions.

## 2. Evidence, and its priority

The priority order below is the design. It is close to what the code does, but the code does
not state it anywhere and in two places does not follow it.

**1. An explicit kind statement in the document.** `X ⊑ owl:topObjectProperty` and
`X ⊑ owl:topDataProperty` say the kind outright. `X ⊑ ⊤` says `X` is a class, but only counts
as such when `X` is also stated to be a role — otherwise it is an ordinary subsumption, which
is what leaves the corpus documents that open with it alone.

These exist because the writer emits them: they are how a document carries a kind the rest of
the syntax cannot express. Nothing may override them.

**2. A position only one kind can occupy.** These are facts about the syntax, not guesses:

- a datatype filler — `∃d.xsd:string`, `∃d.[xsd:string ⊓ [matches "…"]]`, `∃d.{"x"}`,
  `∃d.¬xsd:string` — makes `d` a data property;
- an inverse — `r⁻` — makes `r` an object property, since a data property has no inverse;
- a chain, in any position — `p ∘ q ⊑ r` — makes all three object properties;
- an annotation statement — `@ann A p "v"` — makes `p` an annotation property;
- a class filler that is not `⊤` — `∃r.B` — makes `r` an object property.

`⊤` is the exception and the reason level 2 is not simply "appears in a role position":
`∃r.⊤ ⊑ C` is the domain idiom and says only that `r` is a role, not which kind.

**3. A datatype the document declares.** A name used in a datatype position — the base of a
`[… ⊓ [facet …]]`, or the range of a data property — is a datatype whether or not it is in
any built-in list.

**4. Propagation.** A kind reaches every name tied to an evidenced one by `⊑` or `≡`. Both
are order-independent, because the evidence may sit anywhere in the document: they run to a
fixpoint after the whole document is scanned, never as a statement is read.

**5. The case convention.** A lower-case local part suggests a role; an upper-case one
suggests a class. This is a *guess*. It is applied once, after levels 1–4 have settled, and
never overrides them.

**6. The default.** A name known to be a role but not which kind is an object property.

**A conflict is an error, not a resolution.** Two *evidenced* kinds for one name — levels 1–4
disagreeing — is a contradiction in the document and is reported with both line numbers. It
is never settled by priority. A guess losing to evidence is not a conflict.

## 3. What is wrong today

### 3.1 The guess is stored where the evidence is

`objectPropertyNames` and `dataPropertyNames` hold both earned and assumed classifications.
`objectEvidence` and `dataEvidence` hold only earned ones. Every consumer downstream sees the
sets, so nothing can tell a fact from a guess.

Consequences, all observed:

- `classifyUnknownRole` adds to `objectPropertyNames`, so *kind unknown* becomes
  *object property* immediately and indistinguishably. Level 6 is applied at level 2's time.
- The equivalence refusal written for #38 fired on names that merely had no evidence yet,
  because from the sets they looked like evidenced object properties.
- `reportKindConflicts` has to read the evidence maps rather than the sets, and so cannot see
  conflicts that only propagation reveals.

### 3.2 "Role" and "which kind of role" are one flag

`classifyProp(propCtx, isData, pinsKind, fillerName)` takes a boolean. There is no third
state for *this is a role and the position does not say which kind*, so that state is encoded
as `pinsKind == false` plus membership of the object set — which is also what an evidenced
object property looks like.

`pinsTheKind` on the writer side exists to compensate, and its exclusion list is now five
entries long:

| excluded for a data property | added because |
|---|---|
| `FunctionalDataProperty` | `Func(p)` is spelled identically for both kinds |
| `DisjointDataProperties` | so is `Disj(p, q)` |
| `DataPropertyDomain` | so is `∃p.⊤ ⊑ C` |
| `HasKey` | so is `key(p)` |
| unqualified data cardinality | so is `≥2 p` |

Each was found by a separate bug report. They are not five bugs; they are one rule stated
backwards. The rule should be *a position pins the kind only if the kind is recoverable from
the rendering* — positive evidence — rather than *everything pins the kind except these*.

### 3.3 The writer models the reader, over different input

This is the structural problem, and it is subtler than "duplicated code".

The case convention is already shared: `readerCanGuessRole` delegates to
`EntityTypeScanner.caseSuggestsRole`, which was the fix in `08d14c3` after the two copies
disagreed — `EX:p ⊓ EX:q ⊑ ⊥` read as classes while `Disj(EX:p, EX:q)`, the same axiom, read
as properties.

What remains duplicated is the *evidence model*, and it cannot simply be shared, because the
two sides have different inputs:

- the reader decides from **parse-tree positions**: `classifyRestriction` computes
  `pinsKind = isDataFiller || !isTopFiller(filler)`;
- the writer decides from **OWL axiom types**: `pinsTheKind(axiom, dataProperty)` matches on
  `OWLFunctionalDataPropertyAxiom`, `OWLDataPropertyDomainAxiom` and the rest.

They are two formulations of one rule over two representations. They must agree, and nothing
checks that they do. Every entry in the exclusion list above is a recorded disagreement,
found in production rather than by construction.

The invariant that matters is:

> For every axiom `A` and entity `e` in it, if the writer decides not to state `e`'s kind,
> then reading back the rendering of `A` must give `e` the same kind.

That is checkable exhaustively — write, read, compare — and is not checked at all.

### 3.4 Three narrower faults

- **`isDatatypeIri` matches the whole XSD namespace** (#39), including names OWL 2's datatype
  map excludes and the XSD *facet* IRIs. A prefix bound into that namespace makes every name
  under it a datatype; and `resolve()` returns null for a bare name, so a bare name in that
  namespace is invisible to the test.
- **Annotation properties are not considered** (#40). OWL 2 DL requires all three property
  IRI sets to be disjoint; only two are checked.
- **A document-declared datatype is invisible** (#36). The test is a static list, so
  `Declaration(Datatype(:MyType))` in the document does not count, and both the datatype and
  the data properties ranged on it flip.

## 4. The design

### 4.1 One kind per name, with provenance

Replace the five sets and three maps with a single record per name:

```
Kind      := CLASS | OBJECT_PROPERTY | DATA_PROPERTY | DATATYPE | ANNOTATION_PROPERTY
Certainty := STATED | POSITIONAL | DECLARED | PROPAGATED | GUESSED | DEFAULTED
Finding   := (Kind, Certainty, line)
```

`Certainty` is the priority of §2 as a value. A name accumulates findings; the kind is the
highest-certainty one. Two findings of different kinds at certainty `STATED`…`PROPAGATED` are
a conflict, reported with both lines. A `GUESSED` or `DEFAULTED` finding never conflicts —
it loses.

This makes §3.1 and §3.2 structural rather than conventional. *Role of unknown kind* becomes
representable — a `POSITIONAL` finding with no kind, which is exactly what `∃p.⊤ ⊑ C` and
`Func(p)` and `Disj(p, q)` and `≥2 p` provide — and the five-entry exclusion list disappears,
because those positions simply never produce a kind finding in the first place.

### 4.2 Both sides ask the same question

Give the shared rule one signature, over an abstraction both sides can supply:

```
Finding kindFromPosition(Position p)
```

where `Position` is constructed from a parse-tree node by the reader and from an OWL axiom by
the writer. The reader's `pinsKind` computation and the writer's `pinsTheKind` both become
callers.

They still differ in how they build a `Position`, which is unavoidable. What changes is that
the *rule* exists once, and the mapping from each representation into `Position` is small
enough to read.

### 4.3 The invariant is tested by construction

A single parameterised test over every OWL axiom type × every entity kind: build the axiom,
write it, read it back, assert the kind survives. That is the check §3.3 lacks. It would have
caught all five exclusion-list entries before they shipped, and it is the test that keeps the
two `Position` mappings honest as either side changes.

### 4.4 The writer's policy, stated

A kind statement is written exactly when the kind is not recoverable from levels 1–4 of the
rendering. That is a consequence of §4.1 and §4.3 rather than a separate rule, and it removes
the prediction: the writer no longer asks "would the reader guess this right?" but "does the
rendering carry a finding at `POSITIONAL` or better?".

Two additions you asked for:

- **`--explicit-kinds`**, writing a kind statement for every entity regardless. Round-tripping
  data becomes fully explicit and independent of inference; hand-written documents stay
  compact. This is the cheap, high-value half — it makes fidelity a user choice rather than a
  property of the inference being perfect.
- **`𝑈` and `𝐵`** for `owl:topObjectProperty` and `owl:bottomObjectProperty`, documented in
  the header, accepted on read and never written.

  They are safe, and verifiably so. They are U+1D448 and U+1D435 — mathematical italic
  capitals, distinct codepoints from ASCII `U` and `B`, so they cannot collide with a class
  named `U`. And they are outside `NameStart` altogether, which stops at `\uFFFD`: today
  `𝑈 ⊑ C` is a token recognition error, so nothing can already be using them as a name.

  One limitation worth stating: the textbook has no italic-capital convention for the
  data-property equivalents, so the aliases cover half the vocabulary and
  `owl:topDataProperty` would still be written out. That asymmetry is a reason to treat them
  as documentation of the textbook's notation rather than as DLe's preferred spelling.

### 4.5 The narrower faults, in the new shape

- **#39** — `isDatatypeIri` becomes the OWL 2 datatype map plus the four non-XSD names, not a
  namespace prefix match; and it is consulted only for a *resolved* IRI, with a bare name in
  the default namespace resolved first.
- **#40** — `ANNOTATION_PROPERTY` is a `Kind`, so annotation positions produce findings and
  the conflict check covers all three sets without a special case.
- **#36** — a datatype position produces a `DECLARED` finding, so the document's own
  declarations outrank any built-in list.

## 5. How the open issues map

| issue | becomes |
|---|---|
| #27 `checkUnaryPredicate` reads sets it is still filling | disappears: findings are collected, then resolved; nothing reads a partial state |
| #33 a dot or colon in a local name is corrupted | separate — lexical, not inference. Needs an escaping form or a refusal |
| #36 pun loses object axioms; upper-case datatype flips | §4.5, plus `DATATYPE` as a first-class `Kind` |
| #37 lower-case class as a bare filler is destroyed | the filler gets a `POSITIONAL` CLASS finding, which outranks the case guess |
| #39 XSD namespace matched wholesale | §4.5 |
| #40 annotation properties ignored | §4.5 |
| #32 subproperty-of-top consumed as a marker | needs the marker to be distinguishable from an authored axiom — see §6 |

#33 is listed to be explicit that it is *not* part of this: it is about names the syntax
cannot spell, which is a lexical problem with a lexical fix.

## 6. Decisions

Recorded 2026-09-16. The questions this section used to pose are answered; the reasoning is
kept where it affects the work.

### 6.1 `X ⊑ owl:topObjectProperty` should be writable by an author — but not for free

Agreed in principle: nothing ever said an author may not write it, and losing it silently is
#32.

**Measured cost of the obvious fix.** Keeping the statement as an axiom as well as a marker
costs fidelity on documents that need a marker, because the writer's own marker then comes
back as content:

| document | axioms before | after |
|---|---|---|
| `bc-example.dle` | 177 | 182 |
| `relations.ttl` | 21 | 23 |

Both were byte-exact round trips and stop being so. The added axioms are tautologies — every
object property is a sub-property of the top one — so no *meaning* changes, but
`OFN → DLe → OFN` stops being an identity, which is the bar the rest of this work has held.

**So the clean fix is to stop overloading the syntax**: give the writer a marker that is not
an axiom, and let `X ⊑ owl:topObjectProperty` always be the axiom it looks like. That is one
new annotation form, consumed on read and never an axiom, and it makes §4.4's
`--explicit-kinds` cheaper too, since explicit kinds then cost no spurious axioms.

Not done yet, and deliberately not done the cheap way: the naive version was implemented,
measured, and reverted rather than shipped with the fidelity loss unremarked.

### 6.2 `@convention properties:upper`

Accepted, with the constraint that the header documents it **only when a document uses it**.
The header is a single static resource today and is emitted wholesale, so that constraint is
the substantial part of the work rather than an afterthought: it needs the header split into
an always-part and per-feature parts.

It also needs to survive a round trip to be worth having — the reader must record that a
document declared a convention and the writer must re-emit it — which means the declaration
has to live somewhere, as `@prefix` and `@ontology` already do.

Sequenced after §4.1, because the convention is one input to the kind decision and is much
easier to make configurable once that decision is one function over one model.

### 6.3 A class under a pun

Worth attempting, and probably feasible — more so than §3's framing suggested. The obstacle
is that downward propagation crosses the class barrier unconditionally to serve SNOMED CT,
where a punned root has only roles beneath it. With `Certainty` in place the case becomes
expressible: an explicit `X ⊑ ⊤` is `STATED`, which outranks a `PROPAGATED` role, so a class
under a pun can be said outright even while the default keeps working for SNOMED.

To be attempted after §4.1 and dropped only if it needs something unreliable.

### 6.4 All of §4.1–§4.3 is in scope

Not deferred again. Staged as below so each step is revertable; tagged
`pre-inference-refactor` before the first.

## 7. Staging

1. **Is `X ⊑ owl:topObjectProperty` an axiom or a marker?** Today it is consumed as a marker,
   so an author cannot state it (#32). Options: consume it *and* keep it — it is trivially
   true, so keeping it is harmless; or mark generated statements somehow. The first is
   simpler and loses nothing.
2. **Does the case convention stay global?** A vocabulary with PascalCase properties needs a
   kind statement on every one. A per-document declaration — `@convention properties:upper` —
   would fix that, at the cost of a document whose meaning depends on its own header.
3. **Should a pun with class children be expressible?** The downward propagation deliberately
   crosses the class barrier, so *every* name below a punned name becomes a role. Right for
   SNOMED CT, wrong in general, and DLe cannot currently say otherwise.
4. **How much of this is release-blocking?** §4.1–§4.3 is a real refactor. §4.4's
   `--explicit-kinds` flag is small and independently useful, and may be enough to unblock a
   release on its own by making fidelity opt-in rather than inferred.

Each stage is independently verifiable and leaves the tree green.

1. **The invariant test** (§4.3), against the current code. ✅ Done — `KindPreservationTest`,
   66 axiom shapes × three checks plus 27 reader expectations. It found exactly two real
   defects, both since fixed: the predicate case-guess that destroyed lower-case classes
   (#37, and #27 with it, being the same guess), and `DatatypeDefinition` written as nothing
   (part of #23, which also recovered axioms belonging to no entity block). Across all 66
   shapes, nothing changed kind — that is the starting point the refactor has to preserve.
2. **`--explicit-kinds`** (§4.4). Small, self-contained, and gives a fidelity guarantee that
   does not depend on any of the rest landing.
3. **The narrower faults** (§4.5): #39, #40, #36. Each is contained and each is caught by the
   stage-1 test.
4. **`Finding` and `Certainty`** (§4.1). **Half done.** `Findings` is now the single store
   for what the document says about a kind and how firmly, replacing the three per-kind line
   maps. Conflict detection is one rule over it instead of three loops — which is why the
   annotation kind went unwatched so long, since adding a kind meant remembering to add
   another loop. A guess is structurally incapable of being half of a conflict, and a class
   beside a property is a pun rather than a contradiction.

   Every site that classifies a name now records why: STATED for a kind said outright,
   POSITIONAL for a position only one kind can occupy, DECLARED for a datatype the document
   defines, PROPAGATED across a subsumption or equivalence, DEFAULTED for a role of unknown
   kind falling back to object property.

   **The two representations are now checked to agree.** `findingsDisagreements` compares
   them name by name, and `FindingsAgreementTest` asserts they match over 43 shapes and a
   corpus document. That check found five places where a name sat in a set with no finding at
   all — the unqualified cardinality and domain idioms, the five object-only characteristics,
   and both propagation phases — which is exactly the drift two representations invite.

   **What is not done:** the firmest finding is still not what the reader consults.
   Resolution lives in the name sets, which propagation walks and the visitor is handed. The
   difference is that drift between them is now caught by a test rather than left to
   discipline, which makes retiring the sets a mechanical step instead of a risky one.

   One consequence of that: PROPAGATED versus POSITIONAL is not yet observable. Both count as
   evidence, and nothing compares them in a way that changes an outcome, so a mutation
   swapping one for the other survives the whole suite. It becomes load-bearing when the
   sets go and the firmest finding decides.

   #27 and #37 did not wait for this; they were the predicate guess, fixed in stage 1.
5. **The shared `Position` rule** (§4.2). Last, because it is only safe once both sides have a
   single kind model to agree about.

Stopping after stage 3 is a coherent release: the known silent failures are gone and fidelity
is available on demand, with the inference still as it is. Stages 4 and 5 are what stop the
exclusion list growing again.
