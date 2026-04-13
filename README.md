# DLe: Description Logic - Extended
DLe is an extension of Description Logic (DL) designed for communicating structured systems to large language models (LLMs).

It provides a compact, formal, and declarative way to describe ontologies, data models, and relationships, while remaining readable to both humans and machines. The syntax is described in [the Wiki](https://github.com/quoll/DLe/wiki).

DLe is implemented as a module for [OWLAPI](https://github.com/owlcs/owlapi).

## What this is for

DLe is intended as an interface language between ontologies and LLMs.

It is useful when you want an LLM to:
 * understand the structure of a system
 * reason about relationships and constraints
 * generate queries against a backing database
 * identify anomalies or derive insights from modeled data

The goal is not to teach the LLM a new language, but to express systems in a form it already understands.

## Why Description Logic?

Description Logic (DL) is the formal foundation of [OWL (Web Ontology Language)](https://www.w3.org/TR/owl2-overview/) and is widely used in:
 * ontology engineering
 * semantic web technologies
 * academic literature and textbooks

DL uses mathematical notation to provide a precise, declarative syntax for describing:
 * classes (concepts)
 * properties (roles)
 * constraints and relationships

DL uses mathematical notation that is compact in Unicode and minimizes token count. Many LLMs have been exposed to DL-style expressions during training, making it a strong candidate for structured communication.

## Extensions to DL

DLe introduces a small number of extensions:
 * ≝ (U+225D) for defining symbols or predicates (outside DL semantics)
 * predicate restrictions of the form `∃r₁,…,rₙ.P` (over multiple role values)
 * annotations (e.g. `@label`, `@db`) for metadata and database mapping

These are designed to feel like natural continuations of DL, rather than a separate language.

## Example
 - ● standard Description Logic
 - 🦉 supported in OWL
```
@label Project "Project"                                ○🦉
@db dependsOn "DEPENDS_ON"                              ○🦉

∃dependsOn.⊤ ⊑ Project                                  ●🦉
⊤ ⊑ ∀dependsOn.Project                                  ●🦉

LargeProject ≡ Project ⊓ ∃teamSize.[≥10]                ●🦉
InvalidProjectDates ≡ ∃startDate,endDate.greaterThan    ●✖

greaterThan(x,y) ≝ x > y                                ○✖
```
This states:
 * The label of `Project` is "Project".
 * `dependsOn` appears in a database as `"DEPENDS_ON"`. (property annotation)
 * The next two lines declare the domain and range of `dependsOn` as `Project`.
 * `LargeProject` is a `Project` with `teamSize ≥ 10`.
 * `InvalidProjectDates` uses a predicate restriction over `startDate` and `endDate`.
 * `greaterThan` defines the predicate used above (outside DL).

## Design principles

DLe follows a small set of constraints:
 * **Leverage existing knowledge**  
   Use standard DL syntax wherever possible.
 * **Minimal, intuitive extensions**  
   Only extend DL where necessary, using forms that are easy to infer.
 * **Declarative, not procedural**  
   Describe _what is true_, not _how to compute it_.
 * **Interoperable with OWL**  
   DLe can be mapped to and from OWL using OWLAPI, preserving core semantics.
 * **LLM-first readability**  
   The syntax is chosen to be interpretable without prior explanation.

## Status
This is an experimental language and tooling layer, evolving through practical use with LLMs.
