# CHANGES

A log of substantive changes to DLe, written so that the Python implementation —
[addle](https://github.com/quoll/addle), which builds on Owlready2 — can be
brought into step without rediscovering the reasoning.

This is **not** a release changelog. It records *why* each change was made and
*what a second implementation has to do about it*, including the decisions that
are not visible in the diff. Entries stay here until addle has caught up.

---

## How to port an entry to addle

addle keeps two files as verbatim copies of files in this repository, and checks
them for drift:

| This repository | addle |
|---|---|
| `parsers/src/main/antlr4/org/semanticweb/owlapi/dlesyntax/DLESyntax.g4` | `grammar/DLESyntax.g4` |
| `parsers/src/test/resources/data/wildlife-reserve-test.dle` | `tests/data/wildlife-reserve-test.dle` |

Any grammar change therefore means, in addle:

```bash
cp <dle>/parsers/src/main/antlr4/org/semanticweb/owlapi/dlesyntax/DLESyntax.g4 grammar/
tools/grammar.py generate        # regenerate src/addle/_antlr (needs Java + ANTLR 4.13.1)
tools/grammar.py update          # re-record grammar/DLESyntax.g4.sha256
make test
tools/grammar.py check --against <dle>
```

addle's weekly `grammar drift` workflow opens an issue when these files diverge.
An open issue suppresses further reports, so **closing it re-arms the check**.

Rough correspondence of the parts that usually need touching:

| This repository | addle | Role |
|---|---|---|
| `EntityTypeScanner.java` | `src/addle/scanner.py` | pass 1, entity-type classification |
| `DLESyntaxAxiomVisitor.java` | `src/addle/reader.py` | pass 2, building the model |
| `DLESyntaxObjectRenderer.java`, `DLESyntaxStorerBase.java` | `src/addle/writer.py` | rendering back to DLe |
| `DLESyntaxAxiomVisitor.DLE_NS` and annotation IRIs | `src/addle/vocab.py` | the shared wire format |

Not every change needs porting. Anything that is purely an OWL API or `owltx`
concern has no Owlready2 counterpart; entries below say so explicitly.

---

## Unreleased

### Redundant parentheses are transparent

Branch `feature/parenthesised-property-expressions`. Landed in two passes:
`968c9d3` covered role positions; a follow-up extended it to class positions,
the `Self` filler and predicate-restriction fillers after review found the first
pass was asymmetric.
**Porting to addle: required.** Grammar changed, and both passes are affected.

#### Problem

Generated ontologies contain parentheses around a property expression in a role
position, which the grammar rejected:

```
FlaggedOwnerOrgNotConsumer ≡ Application ⊓ ∃ownerOrg.⊤ ⊓ ∃consumedBy.⊤
                            ⊓ ¬∃consumedBy.(∃(ownerOrg⁻).Self)
```

The offending fragment is `∃(ownerOrg⁻).Self`. `propertyExpr` admitted only
`name` and `name⁻`, so a `(` after `∃` was a syntax error.

The parentheses here are redundant — `∃ownerOrg⁻.Self` means the same thing. They
are accepted anyway for two reasons. Generators (LLMs in particular) emit them
whether or not they are wanted, and deciding *which* parentheses are droppable
requires parsing the structure first, so rejecting them is not an option even for
a tool that only wants to strip them. Accepting them also leaves room for more
complex property expressions written in place, rather than forcing a named
property to be introduced just to be referenced once.

#### Grammar change

```antlr
// before
propertyExpr
    : name INVERSE  # InversePropertyExpr
    | name          # SimplePropertyExpr
    ;

// after
propertyExpr
    : propertyExpr INVERSE  # InversePropertyExpr
    | '(' propertyExpr ')'  # ParenPropertyExpr
    | name                  # SimplePropertyExpr
    ;
```

`⁻` becomes a postfix operator over the whole expression rather than over a name.
This is what makes `r⁻`, `(r⁻)`, `(r)⁻`, `((r⁻))` and `(r⁻)⁻` all parse from one
rule. ANTLR reports no ambiguity against `atom`'s `'(' classExpr ')'`: `(r).C`
resolves to a role because `ImplicitSomeValuesFrom` needs the following `.`,
while `(A ⊔ B)` can only be a class expression.

Two further rules changed, so that the same transparency holds outside role
positions:

```antlr
// atom: a property expression used where a class expression is expected, as in
// `contains ≡ locatedIn⁻`. Was `name INVERSE`.
    | propertyExpr INVERSE                # InversePropertyAtom

// primary: the filler of a multi-role predicate restriction. Was `DOT name`.
    | EXISTS propertyExpr (',' propertyExpr)+ DOT predicateRef # MultiRoleSomeValuesFrom
    | FORALL propertyExpr (',' propertyExpr)+ DOT predicateRef # MultiRoleAllValuesFrom

// new
predicateRef
    : '(' predicateRef ')'
    | name
    ;
```

The trailing `INVERSE` on `InversePropertyAtom` is required: without it the
alternative would collide with `NameAtom`.

**The shape of the parse tree changed in two places, and this is the trap.**
`InversePropertyExprContext` and `InversePropertyAtomContext` no longer have a
`name` child — they have a `propertyExpr` child. `MultiRole*Context` no longer
has a `name` child either; it has a `predicateRef`. Any code that reached for
`.name()` on these, or that used the alternative's *type* to decide whether a
role is inverted, is now wrong. In Python this surfaces as an `AttributeError`
rather than a wrong answer, because the generated context class has no `name`
method at all.

Note the parity subtlety for `InversePropertyAtom`. The atom is
`propertyExpr INVERSE`, so the parity of the whole atom is the parity of the
inner expression *plus one*: `(locatedIn)⁻` is an inverse, `locatedIn⁻⁻` is not.
The Java code reads `isInverse(inner) ? prop : inverseOf(prop)` — note the
inversion of the test relative to the `propertyExpr` case.

#### Consequences that are requirements, not choices

Two things follow from parentheses being transparent. Both are load-bearing.

**1. A doubled `⁻` must cancel.** `r⁻⁻` is `r`. This is not a tidiness measure:
OWL has no inverse-of-an-inverse, and `OWLDataFactory.getOWLObjectInverseOf`
accepts a *named* property only. Since parentheses are transparent and `⁻` is
postfix, every property expression must reduce to exactly one of two things — a
named property, or the inverse of a named property — before it can be handed to
OWL at all. The reduction is: strip parentheses, count `⁻` markers, apply an
inverse if the count is odd.

In Owlready2 the same constraint applies for the same reason: `Inverse(Inverse(p))`
is not a representable role, so parity has to be computed before constructing
anything.

**2. Predicate-restriction expression text must be canonicalised.** A
multi-role restriction such as `∃a,b.greaterThan` is encoded as a synthetic class
in the `dle:` namespace whose IRI embeds `String.hashCode()` of the expression
text. If that text kept the parentheses as written, `∃(a⁻),b.p` and `∃a⁻,b.p` —
the same expression — would mint two different classes.

So the role text used for hashing is now the canonical form (`r` or `r⁻`, never
parenthesised) rather than the raw source text. Consequences:

- Documents containing no redundant parentheses hash to **exactly** the IRIs they
  did before this change. Nothing already written moves.
- addle must canonicalise identically or the two implementations will mint
  different synthetic classes for the same document. addle already reproduces
  `String.hashCode()` in `vocab.py:java_string_hash`; what changes is the *string*
  being hashed.

**3. The class-expression path needs its own unwrapping.** Property expressions
handle their parentheses in the grammar, but a parenthesised *class* expression
is a `ParenAtom` wrapping a whole `classExpr`. Code that recognises DLe's special
fillers matches on parse-tree shape, so without unwrapping it cannot see a filler
wrapped in parentheses: `∃r.(Self)` and `∃a.(greaterThan)` both failed with
`IllegalStateException` before this change, and `∃a,b.(greaterThan)` was a syntax
error.

Parentheses are stripped only when genuinely redundant, i.e. when they enclose a
single `primary`. In `(A ⊔ B)` they group, and the expression must be returned
untouched — there is a test asserting `(B ⊔ C) ⊓ D` differs from `B ⊔ (C ⊓ D)`.

#### Java implementation

- New `Parens` holds the class-expression unwrapping: `unwrap`, `lonePrimary`,
  `atomOf` (for both `primary` and `classExpr`), and `predicateName`. Unwrapping
  is depth-unbounded — `((Self))` occurs.
- `DLESyntaxAxiomVisitor.visitSomeValuesFrom` finds the `Self` and predicate
  fillers through `Parens.atomOf` instead of casting to `AtomWrapContext`.
- `DLESyntaxAxiomVisitor.visitInversePropertyAtom` uses `PropertyExprs`, with the
  parity inversion noted above.
- `EntityTypeScanner`'s shape helpers — `primaryBareName`, `isBottomClassExpr`,
  `singleInverseAtom`, `isDataPrimary` — go through `Parens`. Without this a
  parenthesised filler silently stops being *classified*, which is worse than
  failing: `singleInverseAtom` is what recognises `contains ≡ locatedIn⁻` as an
  inverse-property axiom, and if it stops matching the axiom does not error, it
  quietly degrades into something else.
- New `PropertyExprs` (package-private, `parsers/src/main/java/.../PropertyExprs.java`)
  holds the reduction: `coreName`, `coreNameText`, `isInverse`, `render`. It is a
  separate class because both passes need it and neither owns it.
- `DLESyntaxAxiomVisitor.buildObjectProp` now resolves the core name and applies
  an inverse on odd parity, instead of switching on the alternative type.
  `propName` and `propCtxName` delegate to `PropertyExprs` — kept as thin
  adapters so their many call sites did not have to change.
- `DLESyntaxAxiomVisitor.buildPredicateClass` uses `PropertyExprs.render(...)`
  instead of `roles.get(i).getText()`.
- `EntityTypeScanner.classifyProp` uses `PropertyExprs.isInverse` and
  `coreNameText`; it previously switched on the alternative type to decide that
  an inverse role must be an object property.
- The writer needed **no** change. Parentheses collapse at parse time, so the
  renderer never sees them and keeps emitting the canonical `r⁻`.

#### Porting checklist for addle

Precise targets, as of addle commit `1767c64`:

1. **`src/addle/scanner.py:27` — `_property_name`.** Recognises only
   `InversePropertyExprContext` and `SimplePropertyExprContext` and calls
   `ctx.name()`. Replace with a loop that strips `ParenPropertyExprContext` and
   `InversePropertyExprContext` wrappers down to the `SimplePropertyExprContext`
   and returns its name. Consider adding an `_is_inverse` alongside it.

2. **`src/addle/reader.py` — `_property_expr`.** Currently
   `ctx.name().getText()` plus an `isinstance(ctx, P.InversePropertyExprContext)`
   test. Must become: core name, then `self._inverse(prop)` only when parity is
   odd. `_inverse` itself is unchanged — note it already relies on Owlready2's
   `Inverse.__new__` collapsing to a declared named inverse, and still needs the
   eager blank-node assignment for `PropertyChain`.

3. **`src/addle/reader.py:770` — `_predicate_class`.** `roles = [e.getText() for
   e in role_ctxs]` must render canonically instead, or synthetic class IRIs will
   diverge from this implementation's. This is the cross-implementation
   compatibility point; there is a test for the exact IRI
   (`test_synthetic_iri_matches_the_java_implementation`) that will *not* catch
   this, because it uses a paren-free document.

4. **`src/addle/reader.py:576, 587, 611` — `_domain_shape`, `_range_shape`,
   `_self_shape`.** These require `isinstance(expr, P.SimplePropertyExprContext)`
   to recognise the DLe idioms `∃p.⊤ ⊑ C`, `⊤ ⊑ ∀p.C` and `∃p.Self ⊑ ⊥`. A bare
   role still matches, so nothing breaks — but `∃(p).⊤ ⊑ C` would silently stop
   being read as a domain axiom and fall through to a general concept inclusion.
   Recommendation: match on "core name, non-inverted, parentheses ignored" so a
   parenthesised role is still recognised. This is a genuine behavioural decision
   rather than a mechanical port; the Java side does not face it, because its
   idiom recognition works on the axiom shape rather than on the alternative type.

5. **`src/addle/writer.py` — no change expected**, for the same reason as the Java
   renderer. Worth confirming with a round-trip test rather than assuming.

6. **`src/addle/vocab.py` — no change.** `java_string_hash` and
   `synthetic_class_iri` are unaffected; only their input string changes.

7. **`src/addle/scanner.py` — the class-position analogues.**
   `_single_inverse_name` reads `atom.name()` off an `InversePropertyAtomContext`
   and is what recognises `contains ≡ locatedIn⁻`; it needs the core-name
   reduction and, being an *equivalence* recogniser, its silent-degradation risk
   is the one called out above. `_atom`'s `InversePropertyAtomContext` branch
   needs the same. `_unwrap_atom`, `_unwrap_class_expr_atom` and `_leaf_atoms`
   are the analogues of Java's `Parens` and need paren-stripping so that a
   parenthesised filler is still classified.

8. **`src/addle/reader.py` — `Self` and predicate fillers through parentheses.**
   `_primary` detects `Self` with
   `isinstance(_unwrap_atom_of(filler), P.SelfAtomContext)` and predicates via
   `_predicate_filler`, both of which need paren-stripping to arbitrary depth.
   `_multi_role` reads `ctx.name()`, which is now `ctx.predicateRef()`.
   `_atom`'s `InversePropertyAtomContext` branch needs the parity reduction.

9. **Decide the same boundary.** Transparency deliberately stops at
   keyword-argument positions: `Trans((r))`, `Disj((a),(b))`, `C ⊑ key((id))` and
   parenthesised `@label`/`@doc`/`@db`/`@storage`/`@ann` subjects are all syntax
   errors, because those positions name an entity rather than take an expression
   and the keyword's own parentheses already delimit. There are tests pinning
   this as an error so the boundary cannot drift; mirror them rather than
   extending transparency further.

#### Tests to mirror

`parsers/src/test/java/.../ParenthesisTransparencyTest.java`, 43 tests. The
useful shape is a helper asserting that a parenthesised spelling yields *the same
axioms* as the plain one, which keeps the tests about meaning rather than about
parse trees. Guard it against comparing two empty axiom sets, or a document that
silently parses to nothing will make the test pass for the wrong reason.

Covered: the reported expression verbatim; transparency in every role position
(`∃`, `∀`, `Self`, qualified and unqualified cardinality, implicit `∃`, property
chains, functional-property axioms); every class-position spelling
(`(r⁻)`, `(r)⁻`, `((r))⁻`, `((r)⁻)`, `r⁻⁻⁻`) plus the left-hand side of an
equivalence and a sub-property axiom; `Self` at each nesting depth; multi-role and
single-role predicate fillers; `⁻⁻` cancelling in both role and class position and
`⁻⁻⁻` not; entity typing unaffected for both object and data properties;
predicate-restriction IRIs unchanged by redundant parentheses; the keyword-argument
boundary pinned as a syntax error; and a write/read round trip through the storer.

Two assertions are worth copying deliberately, because both replaced tests that
passed for the wrong reason:

- `parenthesesThatGroupAreNotStripped` asserts an *inequality* against the other
  grouping. Comparing two spellings of the same grouping is vacuous, because
  OWLAPI normalises intersection operand order into a set — the earlier version
  would have passed even if both sides were parsed wrongly in the same way.
- `keywordArgumentPositionsTakeABareName` asserts the specific parser exception
  rather than any exception, which a broad `assertThrows` would also satisfy for
  an unrelated internal failure.

Regression evidence beyond the unit tests: the functional-syntax axiom dump of
every `.dle` document in the repository (6 files, ~3,600 axiom lines) is
byte-identical between `main` and this branch. Worth reproducing on the addle
side, since the scanner's shape helpers were touched and those drive axiom
*recognition*.

One note on that last test. It originally declared `@prefix : <http://example.org/t#>`
and failed — not because of parentheses, but because of the storer issue recorded
below. Keeping a custom default prefix out of that test is deliberate.

#### Still not transparent, deliberately or otherwise

- Keyword-argument positions, deliberately: see item 9 above.
- An inverse on a data property, `⊤ ⊑ ∀age⁻.xsd:integer`, fails with
  `IllegalStateException: Expected class expression, got: xsd:integer`. Data
  properties have no inverses in OWL, so rejecting it is correct; surfacing it as
  an internal error rather than a diagnostic is not. Pre-existing, and listed
  below rather than fixed here.

---

## Found but not fixed

Discovered while working on the above; all reproduce on `main`, so none is caused
by that change. Recorded here so they are not mistaken for new breakage, and so
they are not ported. These are being fixed in their own PR.

### The storer ignores the format passed to `saveOntology`

`DLESyntaxStorerBase.storeOntology` (around line 75) reads:

```java
OWLDocumentFormat sourceFormat = o.getFormat() != null ? o.getFormat() : outputFormat;
```

`o.getFormat()` wins over the format the caller passed. A caller who parses
directly with `new DLEOntologyParser().parse(...)` and then hands the returned,
prefix-populated `DLESyntaxDocumentFormat` to `saveOntology` has it discarded, so
no `@prefix` declarations are emitted and every bare name silently re-resolves to
the DLe default namespace on reload. Loading via
`manager.loadOntologyFromOntologyDocument` works, because that records the format
on the ontology.

addle has no equivalent: its writer takes prefixes from the ontology's `base_iri`
and its own argument, so there is nothing to port.

### `owltx` writing to stdout emits plain DL, not DLe

`owltx/src/java/io/github/quoll/owltx/Main.java:212` returns
`new DLSyntaxDocumentFormat()` — the OWL API's plain DL syntax — as the final
format fallback, while both the usage text and the project documentation say the
default is DLE syntax. With no output file and no `--format`, output has no
`@prefix`, no header, lowercase `self` where the grammar requires `Self`, and no
line breaks at all. The file-output path is unaffected and correct.

addle's CLI has its own format defaulting (`src/addle/__main__.py`) and already
defaults to DLe, so there is nothing to port — but it is worth confirming that
`addle input.dle` and `addle input.dle out.dle` agree.

### `owltx/VERSION` is stale in git

Committed as `0.4.0` while the POM is at `0.4.1`. The build regenerates it, so it
appears as a spurious modification after every build. Harmless, but it means a
fresh checkout's launch script looks for a JAR version that was never built.

### An inverse on a data property reports an internal error

`⊤ ⊑ ∀age⁻.xsd:integer` throws `IllegalStateException: Expected class expression,
got: xsd:integer` from `DLESyntaxAxiomVisitor.asClass`. The input is invalid —
OWL data properties have no inverses — so rejection is right, but it should be a
diagnostic naming the problem, not an internal failure.

addle reaches the same input through `reader.py:_class_expr`, which would raise
`DleSemanticError` or an Owlready2 type error depending on the path; worth
checking what it actually does when this is fixed here.

### `mvn verify` can leave a stale shaded jar

Not a code issue, but it cost an hour of confusion and will cost it again.
`mvn verify` without `clean` can reuse a previously shaded `owltx` jar, so
hand-testing a grammar change through `owltx` reports the *old* behaviour while
`mvn test` reports the new one. Use `mvn clean package` before trusting anything
`owltx` prints.
