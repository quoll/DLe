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

### Parenthesised property expressions in role positions

Commit `968c9d3`, branch `feature/parenthesised-property-expressions`.
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

**The shape of the parse tree changed, and this is the trap.**
`InversePropertyExprContext` no longer has a `name` child — it has a
`propertyExpr` child. Any code that reached for `.name()` on it, or that used the
alternative's *type* to decide whether a role is inverted, is now wrong. In
Python this surfaces as an `AttributeError` rather than a wrong answer, because
the generated context class has no `name` method at all.

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

#### Java implementation

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

#### Tests to mirror

`parsers/src/test/java/.../ParenthesisedPropertyExprTest.java`, 21 tests. The
useful shape is a helper asserting that a parenthesised spelling yields *the same
axioms* as the plain one, which keeps the tests about meaning rather than about
parse trees. Guard it against comparing two empty axiom sets, or a document that
silently parses to nothing will make the test pass for the wrong reason.

Covered: the reported expression verbatim; transparency in every role position
(`∃`, `∀`, `Self`, qualified and unqualified cardinality, implicit `∃`, property
chains, functional-property axioms); a non-inverse role in parentheses;
`⁻` outside the parentheses; nested parentheses; `⁻⁻` cancelling and `⁻⁻⁻` not;
entity typing unaffected for both object and data properties; predicate
restriction IRIs ignoring redundant parentheses; class-position parentheses still
working; and a write/read round trip through the storer.

One note on that last test. It originally declared `@prefix : <http://example.org/t#>`
and failed — not because of parentheses, but because of the storer issue recorded
below. Keeping a custom default prefix out of that test is deliberate.

---

## Found but not fixed

Discovered while working on the above. Both reproduce on `main` with input as
plain as `Animal ⊑ Organism`, so neither is caused by the change above. Recorded
here so they are not mistaken for new breakage, and so they are not ported.

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
