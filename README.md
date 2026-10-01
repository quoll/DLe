# DLe: Description Logic - Extended

[![build](https://github.com/quoll/DLe/actions/workflows/build.yml/badge.svg)](https://github.com/quoll/DLe/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/quoll/DLe?sort=semver)](https://github.com/quoll/DLe/releases/latest)
The DLe module reads and writes Description Logic (DL) with Extensions. DL is a mathematical notation for describing data structures. It is ideal for communicating the structure and behavior of data for Large Language Models (LLMs).

DL provides a compact, formal, and declarative way to describe ontologies, data models, and relationships, while remaining readable to both humans and machines. The syntax is described in [the Wiki](https://github.com/quoll/DLe/wiki).

DLe is [OWL](https://www.w3.org/TR/owl2-overview/) compatible and is implemented as a module for [OWLAPI](https://github.com/owlcs/owlapi). The OWL constructs that are not in standard DL are provided via extensions. DLe also has extensions to describe rules through logic expressions.

## Wiki
Seriously, read [the Wiki](https://github.com/quoll/DLe/wiki). It contains everything here and a lot more.

## What is it?
DLe is a "storer" and "parser" for the OWLAPI library.

### How do I use it?
This can be added to the ontology manager and used like any other syntax of OWL.
```xml
        <dependency>
            <groupId>io.github.quoll.owlapi</groupId>
            <artifactId>dlextended-parsers</artifactId>
            <version>0.5.0</version>
        </dependency>
```

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
 * narrowed datatypes, as `xsd:integer[≥1 ⊓ ≤40]` (OWL facets)
 * `C ⊑ key(r₁,…,rₙ)` for the roles that identify an instance (OWL `hasKey`)

These are designed to feel like natural continuations of DL, rather than a separate language.

## Extension Example
 - ● standard Description Logic
 - 🦉 supported in OWL
```
@label Project "Project"                                ○🦉
@db dependsOn "DEPENDS_ON"                              ○🦉

∃dependsOn.⊤ ⊑ Project                                  ●🦉
⊤ ⊑ ∀dependsOn.Project                                  ●🦉

LargeProject ≡ Project ⊓ ∃teamSize.xsd:integer[≥10]     ●🦉
InvalidProjectDates ≡ ∃startDate,endDate.greaterThan    ●✖

greaterThan(x,y) ≝ x > y                                ○✖
```
This states:
 * The label of `Project` is "Project".
 * `dependsOn` appears in a database as `"DEPENDS_ON"`. (property annotation)
 * The next two lines declare the domain and range of `dependsOn` as `Project`.
 * `LargeProject` is a `Project` with an integer `teamSize` of at least 10. A facet
   bracket narrows a datatype, so the datatype it narrows comes first.
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

## Limitations

DLe is DL, so it says what DL says. Where OWL says more, some things do not survive the trip:

* **SWRL rules are out of scope.** They are a 2004 W3C Member Submission rather than part of
  OWL 2, and DL has no notation for them.
* **Almost every OWL 2 axiom type round-trips as the same axiom.** Three do not:
  * `DisjointUnion(A B C)` is written as its definition, `A ≡ B ⊔ C` with `B ⊑ ¬C`. DL has no
    single notation for it. Equivalent, but two axioms come back rather than one.
  * A bare `Declaration` becomes a kind statement — `Declaration(Class(:X))` is written
    `X ⊑ ⊤`. The entity survives; the axiom type does not, because DL does not declare.
  * `SubAnnotationPropertyOf` needs the document to identify one of its two names as an
    annotation property somewhere else — through `@ann`, a domain or a range. `n ⊑ o` is
    otherwise indistinguishable from a subsumption between classes, and the axiom is dropped
    rather than read as the wrong thing.
* **Converting to a third format is limited by that format, not by DLe.** Manchester syntax
  cannot express every axiom and silently drops what it cannot. RDF/XML cannot write a name
  that begins with a digit where the serialisation needs it as an XML element name, which
  DLe and Turtle both can. These are OWL API's serialisers — the same losses occur with DLe
  absent from the path.

## Status
This is an experimental language and tooling layer, evolving through practical use with LLMs.
