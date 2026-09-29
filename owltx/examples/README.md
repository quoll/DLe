# Example documents

Sample ontologies for trying out `owltx` and for exercising the parser and
writer. Every `.dle` file here round-trips through `owltx` without losing a
logical axiom, and every file converts to DLe, OWL functional syntax, Turtle
and OWL/XML.

Two of the other formats are narrower than DLe rather than the reverse.
Manchester syntax converts every file here without complaint but cannot express
every axiom, so it drops some silently — `wildlife-reserve-test.dle` comes back
13 logical axioms short. RDF/XML refuses `abox-forms.dle` outright, because
that document's names are needed as XML element names and begin with digits
(`bc-example.dle` also has digit-initial local names and does convert, so it is
the position that decides, not the name). Both are OWL API's serialisers: the
same losses occur with DLe absent from the path.

```bash
owltx examples/syntax-coverage.dle                      # DLe → DLe, canonically formatted
owltx --format functional examples/bc-example.dle       # DLe → OWL functional syntax
owltx examples/w.ttl out.dle                            # Turtle → DLe
```

| File | What it is |
|---|---|
| `syntax-coverage.dle` | A small rail network, written to cover the parts of DLe the other examples do not: role characteristic keywords, `∃r.Self`, `@version`, parenthesised role expressions, and property chains of more than two roles. |
| `wildlife-reserve-test.dle` | A synthetic wildlife reserve model. The broadest example: predicate definitions, multi-role predicate restrictions, datatype facets, enumerations, keys, chains. Also the parser's regression document, where a copy lives at `parsers/src/test/resources/data/`. |
| `w.ttl` | The same wildlife model in Turtle, for testing the RDF direction. |
| `bc-example.dle` | A breast-cancer concept extract, derived from SNOMED CT. Exercises prefixed names whose local part is numeric (`sct:116676008`) and heavy `@ann` use. |
| `bc-example.ttl`, `breast-cancer-example.ttl` | The same content in Turtle. |
| `relations.ttl` | A small set of SNOMED CT relationship concepts. |
| `abox-forms.dle` | Assertions about individuals, in every spelling DLe accepts for the ABox. Deliberately uses several spellings of one axiom shape, so it does not round-trip byte for byte — what must survive is the set of axioms. Its names begin with digits, which is what RDF/XML cannot write. |

## What is not here

Some documents used during development are not published:

- **The foundation model** (`foundation.dle`, `foundation-core.dle`, and the
  `f.ttl` rendering of it) describes a private data model and is not part of
  this project.
- **`header.dle` and `header.ofn`** were a trimmed excerpt of that same model,
  so they are excluded for the same reason. `syntax-coverage.dle` was written to
  cover what they were used for.

If you are adding an example, prefer a synthetic model over a real one.
