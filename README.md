# DLe: Description Logic - Extended
DLe is an extension of Description Logic (DL) designed for communicating structured systems to large language models (LLMs).

It provides a compact, formal, and declarative way to describe ontologies, data models, and relationships, while remaining readable to both humans and machines. The syntax is described in [the Wiki](https://github.com/quoll/DLe/wiki).

DLe is implemented as a module for [OWLAPI](https://github.com/owlcs/owlapi).

## What is it?
DLe is a "storer" and "parser" for the OWLAPI library.

### How do I use it?
This can be added to the ontology manager and used like any other syntax of OWL.

Alternatively, if you have DLe installed (via Maven), then you can run it with the `owltx` utility.
```bash
$ mvn install
$ cd owltx
$ ./owltx /path/to/input/file.ofn /path/to/output/file.dle
```

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

## Example
SNOMED-CT is a large ontology for clinical data, describing anatomy, drugs, diseases, and many other medical systems. The following is an extract from SNOMED-CT in OWL Functional Notation that describes: "Malignant neoplasm of lower inner quadrant of breast". Since SNOMED-CT uses numerical codes for identifiers, the labels have been included:
```
EquivalentClasses(
    sct:s373080008
    ObjectIntersectionOf(
        sct:s64572001
        ObjectSomeValuesFrom(
            sct:s609096000
            ObjectIntersectionOf(
                ObjectSomeValuesFrom(sct:s116676008 sct:s1240414004)
                ObjectSomeValuesFrom(sct:s363698007 sct:s19100000)))))

AnnotationAssertion(rdfs:label sct:s373080008 "Malignant neoplasm of lower inner quadrant of breast (disorder)"@en)
AnnotationAssertion(rdfs:label sct:s64572001 "Disease (disorder)"@en)
AnnotationAssertion(rdfs:label sct:s609096000 "Role group (attribute)"@en)
AnnotationAssertion(rdfs:label sct:s116676008 "Associated morphology (attribute)"@en)
AnnotationAssertion(rdfs:label sct:s363698007 "Finding site (attribute)"@en)
AnnotationAssertion(rdfs:label sct:s1240414004 "Malignant neoplasm (morphologic abnormality)"@en)
AnnotationAssertion(rdfs:label sct:s19100000 "Structure of lower inner quadrant of breast (body structure)"@en)
```
For those unfamiliar with SNOMED-CT, each of the labeled attributes and classes also have detailed descriptions in the ontology. However, only `sct:s373080008` has been included in this example.

This states:
> The class of "Malignant neoplasm of lower inner quadrant of breast" is a type of disease, characterized by being in a "role group" in which the finding site is at the "Structure of lower inner quadrant of breast", and the morphology is a Malignant neoplasm.

This appears in DLe as:
```
sct:s373080008 ≡ sct:s64572001 ⊓ (∃sct:s609096000.((∃sct:s116676008.sct:s1240414004) ⊓ (∃sct:s363698007.sct:s19100000)))

@label sct:s373080008 "Malignant neoplasm of lower inner quadrant of breast (disorder)"
@label sct:s64572001 "Disease (disorder)"
@label sct:s609096000 "Role group (attribute)"
@label sct:s116676008 "Associated morphology (attribute)"
@label sct:s363698007 "Finding site (attribute)"
@label sct:s1240414004 "Malignant neoplasm (morphologic abnormality)"
@label sct:s19100000 "Structure of lower inner quadrant of breast (body structure)"
```
The extensive labeling does consume a lot of tokens, but no more than the standard labeling form. However, the description logic on the first line contains significantly fewer tokens, and can be easier to read for those familiar with the mathematical syntax.

## Extensions to DL

DLe introduces a small number of extensions:
 * ≝ (U+225D) for defining symbols or predicates (outside DL semantics)
 * predicate restrictions of the form `∃r₁,…,rₙ.P` (over multiple role values)
 * annotations (e.g. `@label`, `@db`) for metadata and database mapping

These are designed to feel like natural continuations of DL, rather than a separate language.

## Extension Example
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
