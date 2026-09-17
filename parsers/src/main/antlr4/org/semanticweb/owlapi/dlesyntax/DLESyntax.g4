grammar DLESyntax;

// ── Entry point ──────────────────────────────────────────────────────────────

ontology
    : (prefixDecl | ontologyDecl | versionDecl | importDecl)* statement* EOF
    ;

// @prefix xsd: <http://www.w3.org/2001/XMLSchema#>
prefixDecl   : AT_PREFIX   PNAME_NS IRI ;
ontologyDecl : AT_ONTOLOGY iriRef       ;
versionDecl  : AT_VERSION  iriRef       ;
// An import may name its target as an IRI, a prefixed name, or a double-quoted string.
// The string form exists because a relative path is the natural way to refer to a file
// beside this one, and it cannot be written as an IRI: `file:x.dle` is opaque under
// RFC 3986 — no leading slash after the scheme — so it has no path and cannot be opened.
// A relative reference in either form is resolved against the document being parsed.
importDecl   : AT_IMPORT   (iriRef | STRING) ;

// An IRI reference: either a full angle-bracket IRI or a prefixed/bare name.
iriRef : IRI | name ;

statement
    : annotation
    | axiom
    ;

// ── Annotations ──────────────────────────────────────────────────────────────

annotation
    : AT_LABEL     name annotationString      # LabelAnnotation
    | AT_DOC       name annotationString      # DocAnnotation
    | AT_STORAGE   name annotationString      # StorageAnnotation
    | AT_DB        name annotationString?      # DbAnnotation
    | AT_ANN       name name annotationValue           # AnnAnnotation
    | name '(' name (',' name)* ')' DEFINED_AS_LINE   # PredicateDefinition
    | name DEFINED_AS_LINE                             # FolAnnotation
    ;

annotationValue
    : annotationString   # StringAnnotationValue
    | name               # IriAnnotationValue
    ;

// An annotation's value, with the datatype the writer puts on it.
//
// The writer quotes every annotation value and appends `^^<datatype>` whenever it is not
// xsd:string, so a dated or numeric annotation — `@doc A "2024-01-02"^^xsd:date` — was
// written and then refused by this same grammar with `extraneous input '^^'`. That is 71 of
// 98 literal shapes, in all five spellings, and any ontology with a dated or numeric
// annotation hit it. Shared by the four shorthands and by `@ann` so they cannot drift apart.
annotationString
    : STRING ('^^' name)?
    ;

// ── Axioms ───────────────────────────────────────────────────────────────────
//
// More specific alternatives (HasKeyAxiom, FunctionalPropertyAxiom,
// AnnProp*Axiom) are listed first.  ANTLR4 LL(*) disambiguates the remaining
// SubClassAxiom / EquivAxiom alternatives via adaptive lookahead.

// ── Assertions about individuals (ABox) ──────────────────────────────────────
//
// The spelling is the textbook one, from Introduction to Description Logic:
//
//     a:C          C(a)              a is a C
//     (a,b):r      r(a,b)            a is related to b by r
//     ¬(a,b):r     ¬r(a,b)           a is not related to b by r
//     (a,v):d      d(a,v)            a's d is the value v
//     ¬(a,v):d     ¬d(a,v)
//
// The colon is a separator here, not part of a name, which is why these need care.
// `PNAME_NS` is `NameChar* ':'` and so matches a bare `:` — the tuple forms need no new
// token at all. Two consequences are easy to miss:
//
//   * A digit-initial property in the default namespace is spelled `:116676008`, so the
//     whole statement carries two colons: `(a,b)::116676008`. That lexes as
//     `PNAME_NS DEFAULT_NAME`, and `name` already admits `DEFAULT_NAME`, so it is covered.
//     `(a,b):116676008` is NOT the same thing and is correctly rejected: `116676008` bare
//     is a NUMBER, and a number cannot be a property.
//
//   * `PNAME_NS` is greedy, so the unspaced `a::116676008` lexes as `PNAME_NS("a:")`
//     followed by `DEFAULT_NAME` — two tokens, not three — and so does NOT match the
//     class-assertion rule below. Write that one spaced: `a : :116676008`. An alternative
//     beginning with a bare `PNAME_NS` would accept it, but `:` alone is also a PNAME_NS,
//     so `:A ⊑ ⊤` would then parse as an assertion with no individual and fail on the `⊑`
//     — losing the diagnostic that explains why `:A` needs a digit after the colon. A
//     semantic predicate could require a non-empty prefix, but this grammar is shared with
//     a Python implementation and cannot carry target-language code.
//
// These alternatives come before the subsumption ones because a statement may already
// begin with `(` — `(A ⊓ B) ⊑ C` — and with `¬`. What separates them is the comma at
// depth one, which no parenthesised class or property expression admits, so ANTLR's
// adaptive lookahead settles it.
axiom
    : '(' name ',' name ')'    PNAME_NS name        # ObjectAssertionAxiom
    | '(' name ',' literal ')' PNAME_NS name        # DataAssertionAxiom
    | COMPLEMENT '(' name ',' name ')'    PNAME_NS name  # NegativeObjectAssertionAxiom
    | COMPLEMENT '(' name ',' literal ')' PNAME_NS name  # NegativeDataAssertionAxiom
    | classExpr SUBCLASS keyExpr                    # HasKeyAxiom
    | cardSymbol NUMBER propertyExpr DOT classExpr  # FunctionalPropertyAxiom
    // propertyExpr, not name: OWL permits an object property expression in every one of
    // these, and the writer emits `r⁻` into all of them. The reader took a bare name, so
    // each was a save-then-fail-to-load — `Func(r⁻)` came out of any ontology with an
    // inverse-functional or inverse-characteristic axiom and would not read back.
    //
    // A data property has no inverse in OWL, so the semantic layer refuses one where the
    // characteristic has both forms.
    | TRANS '(' propertyExpr ')'                    # TransitiveRoleAxiom
    | FUNC  '(' propertyExpr ')'                    # FunctionalRoleAxiom
    | REF   '(' propertyExpr ')'                    # ReflexiveRoleAxiom
    | IRREF '(' propertyExpr ')'                    # IrreflexiveRoleAxiom
    | SYM   '(' propertyExpr ')'                    # SymmetricRoleAxiom
    | ASYM  '(' propertyExpr ')'                    # AsymmetricRoleAxiom
    | DISJ  '(' propertyExpr (',' propertyExpr)+ ')' # DisjointRoleAxiom
    | name DOMAIN name                              # AnnPropDomainAxiom
    | name RANGE  name                              # AnnPropRangeAxiom
    | chainExpr SUBCLASS propertyExpr               # SubPropertyChainAxiom
    | name EQUIV chainExpr                          # PropertyChainEquivAxiom
    | propertyExpr EQUIV propertyExpr SUBCLASS propertyExpr # ChainedEquivSubAxiom
    | classExpr SUBCLASS classExpr                  # SubClassAxiom
    // Chained, because the writer chains it: `EquivalentClasses(:A :B :C)` is written
    // `A \u2261 B \u2261 C`, and while this rule took exactly two operands that document
    // could not be read back. The identity operators `=` and `\u2260` are chained for the
    // same reason, and leaving `\u2261` out made the inconsistency internal to the syntax.
    | classExpr EQUIV    classExpr (EQUIV classExpr)*  # EquivAxiom
    // Last, so that everything with a distinguishing operator is tried first. A lone name
    // has never been a legal statement, which is what leaves room for these: `a:C` arrives
    // as a single PREFIXED_NAME, and whether that is an assertion or a prefixed name is
    // decided after lexing, from the prefixes the document declares. See
    // DLESyntaxAxiomVisitor#classAssertionOf.
    | name EXACT     name (EXACT     name)*         # SameIndividualAxiom
    | name NOT_EQUAL name (NOT_EQUAL name)*         # DifferentIndividualsAxiom
    | name PNAME_NS classExpr                       # ClassAssertionAxiom
    | PREFIXED_NAME                                 # PrefixedClassAssertionAxiom
    ;

// a = b   and   a ≠ b   — identity and distinctness of individuals. Both are n-ary in OWL
// and are written operator-separated, so `a = b = c` names one individual three ways and
// `a ≠ b ≠ c` says all three are pairwise distinct.
//
// `=` is spelled with the EXACT token, which reads oddly here: that token exists for the
// cardinality operator in `=n r.C`. It is reused rather than duplicated because two lexer
// rules matching `=` would be a conflict, and the first would silently win. There is no
// parser ambiguity — a cardinality begins with the operator and this begins with a name.
//
// `≠` needed a token of its own; nothing in the language used it before.

// A property chain: q ∘ r⁻ (∘ is U+2218 RING OPERATOR).
chainExpr
    : propertyExpr (CHAIN propertyExpr)+
    ;

// A key may name an inverse: OWL permits any object property expression here.
keyExpr
    : 'key' '(' propertyExpr (',' propertyExpr)* ')'
    ;

// A property expression: a name, an inverse (r⁻), or either in parentheses.
//
// ⁻ is a postfix operator over the whole expression rather than over a name, so
// r⁻, (r⁻), (r)⁻ and r⁻⁻ are all accepted. Parentheses are transparent — they
// group and carry no meaning of their own — and a doubled ⁻ cancels out.
//
// Redundant parentheses are accepted because generators produce them in role
// positions whether or not they are needed, and the structure has to be parsed
// before anything can tell which ones are droppable.
propertyExpr
    : propertyExpr INVERSE  # InversePropertyExpr
    | '(' propertyExpr ')'  # ParenPropertyExpr
    | name                  # SimplePropertyExpr
    ;

// ── Class / data-range expressions ───────────────────────────────────────────
//
// Precedence (loosest to tightest): union > intersection > primary

classExpr
    : classExpr UNION intersectionExpr        # UnionOf
    | intersectionExpr                        # IntersectionWrap
    ;

intersectionExpr
    : intersectionExpr INTERSECTION primary   # IntersectionOf
    | primary                                 # PrimaryWrap
    ;

primary
    : COMPLEMENT primary                                        # Complement
    | EXISTS propertyExpr (',' propertyExpr)+ DOT predicateRef # MultiRoleSomeValuesFrom
    | FORALL propertyExpr (',' propertyExpr)+ DOT predicateRef # MultiRoleAllValuesFrom
    | EXISTS propertyExpr DOT primary                          # SomeValuesFrom
    | FORALL propertyExpr DOT primary                          # AllValuesFrom
    | cardSymbol NUMBER propertyExpr DOT primary       # CardinalityRestriction
    | cardSymbol NUMBER propertyExpr                   # UnqualifiedCardinalityRestriction
    | propertyExpr DOT primary                         # ImplicitSomeValuesFrom
    | atom                                             # AtomWrap
    ;

// The filler of a predicate restriction: `∃r₁,…,rₙ.P`. Parenthesised for the
// same reason roles are — generators add parentheses that carry no meaning.
predicateRef
    : '(' predicateRef ')'
    | name
    ;

// A cardinality symbol is one of ≥ ≤ =
cardSymbol
    : MIN | MAX | EXACT
    ;

atom
    : name '[' numericFacet (INTERSECTION numericFacet)* ']'  # NumericDataRangeAtom
    // A property expression used where a class expression is expected, as in
    // `contains ≡ locatedIn⁻`. Delegating to propertyExpr rather than matching
    // `name INVERSE` is what gives class positions the same parenthesis and
    // double-inverse transparency that role positions have. The trailing INVERSE
    // is required: without it this would collide with NameAtom.
    | propertyExpr INVERSE                # InversePropertyAtom
    | name                                # NameAtom
    | TOP                                 # TopAtom
    | BOTTOM                              # BottomAtom
    | SELF                                # SelfAtom
    | '{' oneOfList '}'                   # OneOfAtom
    | '(' classExpr ')'                   # ParenAtom
    | '(' ')'                             # EmptyAtom
    | '[' datatypeRestriction ']'         # DataRangeAtom
    ;

// Compact numeric facet: ≥1, ≤5, >0, <10  (inclusive/exclusive bounds)
numericFacet
    : (MIN | MAX | LT | GT) NUMBER
    ;

// A datatype restriction: base type plus one or more facet constraints.
// Example: [xsd:string ⊓ [matches "..."]]
datatypeRestriction
    : name (INTERSECTION '[' facet ']')+
    ;

// A single facet constraint.  The keyword is either a bare name (e.g. matches,
// length, min, max) or a prefixed IRI (e.g. xsd:pattern, xsd:maxLength).
//
// The value may be given with or without parentheses: `matches "[A-Z]{3}"` and
// `matches("[A-Z]{3}")` mean the same thing.  A facet is a function of one argument, and
// every other syntax that has them — XML Schema, SPARQL, SHACL — writes them that way, so
// the call form is what people and generators reach for.
//
// Two spellings of one construct, not a second construct: a facet only ever appears inside
// the brackets of a datatype restriction, so the parentheses here cannot be confused with a
// grouped class expression or with a predicate definition's argument list.
facet
    : name (literal | '(' literal ')')
    ;

// Elements of a { … } enumeration are either individual/value names or
// string / numeric / boolean literals.  The semantic layer decides which.
oneOfList
    : oneOfElem (',' oneOfElem)*
    ;

oneOfElem
    : name     # IndividualElem
    | literal  # LiteralElem
    ;

// ── Literals ─────────────────────────────────────────────────────────────────

// A datatype may be named after a string, as Turtle spells it: `"2024-01-01"^^xsd:date`.
// Only after a string: a number or a boolean already says what it is, and `^^` on one would
// be a second, contradictory answer.
//
// `xsd:string` is implicit, so a plain string needs nothing and the writer never emits it —
// but `"hello"^^xsd:string` written out is still accepted, because a generator may produce
// it and it says exactly what a plain string says.
//
// A datatype and a language tag cannot both appear: a tagged string is rdf:langString by
// definition, so a datatype beside the tag either repeats it or contradicts it. The tag
// lives inside the STRING token, so this is checked in the semantic layer rather than here.
literal
    : STRING ('^^' name)?   # StringLiteral
    | NUMBER                # NumberLiteral
    | BOOL                  # BoolLiteral
    ;

// The case of a name's local part carries meaning: a name whose local part begins
// with a lower-case letter is a role, and one beginning upper-case is a class.
// This is long-standing DL practice, and DLe relies on it because `a ⊑ b` alone
// cannot say which hierarchy the pair belongs to.
//
// Identifiers that carry no case signal — SNOMED CT's numeric ones, for instance —
// and identifiers that are punned as both a class and a role state their kind with
// existing vocabulary rather than new syntax:
//
//     X ⊑ ⊤                      X is a class
//     X ⊑ owl:topObjectProperty   X is an object property
//     X ⊑ owl:topDataProperty     X is a data property
//
// Both forms are ordinary subsumptions that were always legal and always true, so
// nothing here extends the grammar; a name carrying both is punned.
//
// A name is either a bare local name or a prefix:local CURIE.
// DOMAIN, RANGE and role-axiom keywords are also valid as entity names.
name
    : NAME
    | PREFIXED_NAME
    | DEFAULT_NAME
    | DOMAIN
    | RANGE
    | TRANS | FUNC | REF | IRREF | SYM | ASYM | DISJ
    ;

// ── Lexer tokens ─────────────────────────────────────────────────────────────

// DL Unicode operators
EXISTS       : '\u2203' ;   // ∃
CHAIN        : '\u2218' ;   // ∘  (ring operator, property composition)
SELF         : 'Self'   ;   // ObjectHasSelf filler
FORALL       : '\u2200' ;   // ∀
TOP          : '\u22A4' ;   // ⊤
BOTTOM       : '\u22A5' ;   // ⊥
SUBCLASS     : '\u2291' ;   // ⊑
EQUIV        : '\u2261' ;   // ≡
UNION        : '\u2294' | '\u2A06' ;   // ⊔ or ⨆ (N-ARY SQUARE UNION)
INTERSECTION : '\u2293' | '\u2A05' ;   // ⊓ or ⨅ (N-ARY SQUARE INTERSECTION)
COMPLEMENT   : '\u00AC' ;   // ¬
MIN          : '\u2265' ;   // ≥  (at least n)
MAX          : '\u2264' ;   // ≤  (at most n)
EXACT        : '='      ;   //    (exactly n), and identity of individuals: a = b
NOT_EQUAL    : '\u2260' ;   // ≠  distinctness of individuals: a ≠ b
INVERSE      : '\u207B' ;   // ⁻  (superscript minus, inverse property)
DOT          : '.'      ;   // restriction filler separator
// ≝ followed by the rest of the line — captured as a single token so the FOL
// body (which may contain DL symbols) is not re-tokenised.
DEFINED_AS_LINE : '\u225D' ~[\r\n]* ;

// Annotation and prefix keywords — matched before NAME
AT_LABEL      : '@label'     ;
AT_DOC        : '@doc'       ;
AT_STORAGE    : '@storage'   ;
AT_DB         : '@db'        ;
AT_ANN        : '@ann'       ;
AT_PREFIX     : '@prefix'    ;
AT_ONTOLOGY   : '@ontology'  ;
AT_VERSION    : '@version'   ;
AT_IMPORT     : '@import'    ;

// Context-sensitive keywords; included in `name` so entities can use them
DOMAIN : 'domain' ;
RANGE  : 'range'  ;

// Textbook role-axiom keywords — must precede NAME; full-word forms accepted too
TRANS : 'Trans'       | 'Transitive'  ;
FUNC  : 'Func'        | 'Functional'  ;
REF   : 'Ref'         | 'Reflexive'   ;
IRREF : 'Irref'       | 'Irreflexive' ;
SYM   : 'Sym'         | 'Symmetric'   ;
ASYM  : 'Asym'        | 'Asymmetric'  ;
DISJ  : 'Disj'  ;

// Boolean literals — must precede NAME
BOOL : 'true' | 'false' ;

// Strings — may span multiple lines (annotation values often do), and may carry a
// language tag as Turtle spells it: "Neoplasm"@en, "nordfriisk"@frr, "Hanzi"@zh-Hant.
//
// The tag is part of this token rather than a token of its own, which settles two problems
// at once. A separate `'@' [a-zA-Z]+ …` rule would collide with every annotation keyword —
// @label, @doc, @db — and `doc` and `ann` are real ISO 639-3 codes, so the collision is not
// hypothetical. And a tag must abut its string: `"x"@en` is a tagged literal, while a "x"
// ending one line and a @label opening the next are two separate things. A token is a run
// of contiguous characters by definition, so including the tag here gives that for free,
// whereas a separate token would need a lexer mode to know where it may appear.
STRING : '"' (~["\\] | '\\' .)* '"' ('@' [a-zA-Z]+ ('-' [a-zA-Z0-9]+)*)? ;

// IRI in angle brackets  <http://example.org/>
IRI : '<' (~[<>"{}|^`\\\u0000-\u0020])* '>' ;

// Exclusive numeric comparators — defined after IRI so IRI always wins on <...>
LT : '<' ;   // strictly less than    (maxExclusive)
GT : '>' ;   // strictly greater than (minExclusive)

// Prefix label ending with colon: xsd:  or  :  (default prefix)
// Longest-match ensures xsd:integer is still PREFIXED_NAME (longer).
PNAME_NS : NameChar* ':' ;

// Numeric literals (covers integers and floats; negative numbers allowed)
NUMBER : '-'? [0-9]+ ('.' [0-9]+)? ;

// Prefixed name (xsd:integer, owl:Thing, sct:116676008, …) — longer than PNAME_NS so wins.
// The local part follows Turtle CURIE rules: may start with a digit (unlike XML NCName).
PREFIXED_NAME : NameStart NameChar* ':' (NameStart | [0-9]) NameChar* ;

// A name in the default namespace whose local part begins with a digit, written
// with the default prefix stated: :762705008
//
// It needs its own token because neither existing form can spell it. NAME requires
// a NameStart, which excludes digits, so the bare `762705008` lexes as a NUMBER;
// and PREFIXED_NAME requires a NameStart before the colon, so the prefix cannot be
// empty. Turtle permits a digit-initial local part, so such names do arrive.
//
// A digit is required immediately after the colon; the rest is ordinary name characters.
// A bare NAME must start with NameStart, which excludes digits, so the two token languages
// are disjoint and this adds no second spelling for a name that already had one.
//
// It cannot collide with PNAME_NS. Longest-match settles it wherever the two meet: at a
// colon followed by a digit this matches two characters or more and PNAME_NS matches one,
// so this wins. That is what makes `(a,b)::116676008` work — the first colon is the
// separator, taken by PNAME_NS, and the second begins the name. (PNAME_NS used to appear
// in prefixDecl alone, where the colon is always followed by whitespace or `<`; the
// assertion forms above now use it too, which is why the argument is by longest match
// rather than by where the token occurs.)
DEFAULT_NAME : ':' [0-9] NameChar* ;

// Bare local name
NAME : NameStart NameChar* ;

fragment NameStart : [a-zA-Z_] | '\u00C0'..'\u02FF' | '\u0370'..'\u037D'
                   | '\u037F'..'\u1FFF' | '\u200C'..'\u200D'
                   | '\u2070'..'\u207A' | '\u207C'..'\u218F'   // excludes U+207B (⁻ INVERSE)
                   | '\u2C00'..'\u2FEF'
                   | '\u3001'..'\uD7FF' | '\uF900'..'\uFDCF'
                   | '\uFDF0'..'\uFFFD' ;

fragment NameChar : NameStart | [0-9] | '-' ;

// Comments and whitespace
LINE_COMMENT : '#' ~[\r\n]* -> channel(HIDDEN) ;
WS           : [ \t\r\n]+   -> skip ;

