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

`⊤` is the exception and the reason level 2 is not simply "appears in a role position". It is
the top *concept* and `rdfs:Literal` the top data range — two disjoint universes — so strictly
`∃d.⊤` is a concept where a data range belongs. DLe is forgiving about that on input, because
`∃d.⊤` is a common and readable way to say "has some value", and reads it as whichever top the
property's kind calls for. `⊥` behaves the same way. So neither is evidence of a kind, and the
domain idiom is written differently for each: `∃r.⊤ ⊑ C` for an object property, `(≥1 d) ⊑ C`
for a data one, which names no filler at all.

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
| `FunctionalDataProperty` | `Functional(p)` is spelled identically for both kinds |
| `DisjointDataProperties` | so is `Disj(p, q)` |
| `DataPropertyDomain` | so is the domain form — `(≥1 p) ⊑ C` for a data property, `∃p.⊤ ⊑ C` for an object one, and neither names a filler that says which |
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
| #32 subproperty-of-top consumed as a marker | settled, not fixed: the statement is implicit and is removed, an authored one with it — see §6.1 |

#33 is listed to be explicit that it is *not* part of this: it is about names the syntax
cannot spell, which is a lexical problem with a lexical fix.

## 6. Decisions

First recorded 2026-09-16, revised 2026-09-21. The questions this section used to pose are
answered; the reasoning is kept where it affects the work, and a decision that was later
reversed is rewritten rather than appended to — §6.1 and §6.2 both once proposed the thing
they now decline, and leaving that standing would have had the next reader build it.

### 6.1 `X ⊑ owl:topObjectProperty` is implicit and is removed — settled

Settled on #32, against what the rest of this section originally proposed. Recorded here
because the reasoning matters and the issue is closed.

**The rule.** `X ⊑ owl:topObjectProperty` and `X ⊑ owl:topDataProperty` are read as the
ordinary sub-property axioms they are and then removed, leaving a declaration. They are
tautologies — every object property is beneath the top one — so nothing entailed stops being
entailed. An authored one is therefore dropped, silently, and that is accepted.
`X ⊑ ⊤` is deliberately **not** removed: `C ⊑ ⊤` is how a class is declared in DL and appears
throughout real documents.

**What was rejected.** A marker that is not an axiom — a new annotation form, consumed on
read and never an axiom — was the fix this section used to propose. It was turned down to
keep DLe close to context-free DL: it would put the kind system into the syntax, which is
the one thing the notation exists not to do. The measured fidelity cost of the other obvious
fix, keeping the statement as an axiom *and* as a marker, was:

| document | axioms before | after |
|---|---|---|
| `bc-example.dle` | 177 | 182 |
| `relations.ttl` | 21 | 23 |

Both were byte-exact round trips and stopped being so, because the writer's own marker came
back as content. The naive version was implemented, measured and reverted rather than
shipped with that unremarked.

**One correction to the record.** The closing note on #32 said Turtle never writes these.
It does — `:worksFor rdfs:subPropertyOf owl:topObjectProperty` appears in the Turtle output
when the axiom is in the model. DLe is the only one of the three formats that drops it:
`OFN → OFN` and `OFN → TTL` both keep it, `OFN → DLe → OFN` does not. The tautology argument
stands on its own; the claim that every other format agreed does not, and the decision does
not rest on it.

**Where the loss is.** Not in the DLe text, which is faithful and stable —
`worksFor ⊑ owl:topObjectProperty` is written and survives `DLe → DLe` byte-identically. The
axiom disappears on the way back out to OWL. Reporting it was considered and declined: the
reader cannot tell an authored marker from one the writer generated, so a warning would fire
on every round trip of DLe's own output.

### 6.2 `@convention properties:upper` — declined

Declined. It was accepted here on a premise that `Findings`/`Certainty` has since made
false: that *a vocabulary with PascalCase properties needs a kind statement on every one*.

It does not. Positional evidence outranks the case guess, and a name in a property position
is in a position only a property can occupy. A vocabulary written entirely in PascalCase,
properties included —

```
Person ⊑ ⊤
Organisation ⊑ ⊤
Document ⊑ ⊤
Employee ≡ Person ⊓ ∃WorksFor.Organisation
Author ≡ Person ⊓ ∃Wrote.Document
∃WorksFor.⊤ ⊑ Person
⊤ ⊑ ∀WorksFor.Organisation
Manager ⊑ Employee ⊓ ≥1Manages.Employee
Wrote ⊑ Contributed
```

— reads with `WorksFor`, `Manages`, `Wrote` and `Contributed` all object properties, no
convention declared and no markers written. Writing it back costs one line,
`Contributed ⊑ owl:topObjectProperty`, for the one name that appears only on the right of a
subsumption and so genuinely has no evidence. That is the document stating the one thing it
never said, not the price of the convention's absence.

The dependency on evidence is real rather than incidental: drop the `Author` line and
`Wrote` loses its only property position, so `Wrote ⊑ Contributed` becomes a subsumption
between classes. That is the rule working. Nothing left in the document says otherwise, and
a convention declaration would be *overriding* the evidence there, not supplying it.

The mirror case holds too: lower-case *class* names read as classes from `person ⊑ ⊤`,
because STATED propagates downward.

So the remaining argument is verbosity that is not there, against a real cost: a declaration
makes a document's meaning depend on its own header, so deleting a line changes what the
names are. For an interchange syntax that is worse than the per-name markers, which are
local, explicit and already round-trip. It would also put a global mode into the kind
subsystem, which is where most of the defects found in review have lived.

The convention now decides only for a name that appears in no structural position at all —
and for those, a marker is one line and says exactly what is meant.

### 6.3 A class under a pun — done

Done, and by the route this section predicted: an explicit `X ⊑ ⊤` is `STATED`, which
outranks a `PROPAGATED` role, so the case is expressible without disturbing the default.
Measured:

| document | `Child` reads as |
|---|---|
| `Root ⊑ owl:topObjectProperty` / `Child ⊑ Root` | object property — the SNOMED default, propagation crossing downward as before |
| the same, plus `Child ⊑ ⊤` | class, with `Root` punned class-and-property |
| a punned root with a role child and a class child | each correctly, independently |

So the default that serves SNOMED CT is intact, and a document that means otherwise can now
say so in one line.

### 6.4 All of §4.1–§4.3 is in scope

Not deferred again. Staged as below so each step is revertable; tagged
`pre-inference-refactor` before the first.

## 7. Staging

1. **Is `X ⊑ owl:topObjectProperty` an axiom or a marker?** Settled, and nothing to stage.
   It is implicit and is removed, an authored one with it; `X ⊑ ⊤` is kept. Keeping it as an
   axiom *as well* was measured and does not "lose nothing" — it costs byte-exactness on
   every document that needs a marker. See §6.1.
2. **Does the case convention stay global?** Yes — settled, and nothing to stage. The
   question assumed a vocabulary with PascalCase properties needs a kind statement on every
   one; positional evidence already outranks the case guess, so it needs none. See §6.2.
3. **Should a pun with class children be expressible?** Yes, and it now is — an explicit
   `X ⊑ ⊤` outranks the propagated role. The downward propagation still crosses the class
   barrier by default, which is what SNOMED CT needs. See §6.3.
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

   One consequence of that: PROPAGATED versus POSITIONAL is still not load-bearing. A
   mutation swapping every POSITIONAL for PROPAGATED no longer survives the suite — it is
   caught by `PropagationEvidenceTest.aClassStatedOutrightIsRecordedAsStated` — but that
   test asserts the recorded value, not an outcome. Under the swap no reading changes,
   including for a name carrying a PROPAGATED role finding and a POSITIONAL class finding
   at once, which is the shape that ought to separate them. The label is pinned; the
   distinction becomes load-bearing when the sets go and the firmest finding decides.

   #27 and #37 did not wait for this; they were the predicate guess, fixed in stage 1.
5. **The shared `Position` rule** (§4.2). Last, because it is only safe once both sides have a
   single kind model to agree about.

Stopping after stage 3 is a coherent release: the known silent failures are gone and fidelity
is available on demand, with the inference still as it is. Stages 4 and 5 are what stop the
exclusion list growing again.
