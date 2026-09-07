# Example documents

Sample ontologies for trying out `owltx` and for exercising the parser and
writer. Every file here converts cleanly, and every `.dle` file round-trips
through `owltx` without losing logical axioms.

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

## What is not here

Some documents used during development are not published:

- **The foundation model** (`foundation.dle`, `foundation-core.dle`, and the
  `f.ttl` rendering of it) describes a private data model and is not part of
  this project.
- **`header.dle` and `header.ofn`** were a trimmed excerpt of that same model,
  so they are excluded for the same reason. `syntax-coverage.dle` was written to
  cover what they were used for.

If you are adding an example, prefer a synthetic model over a real one.
