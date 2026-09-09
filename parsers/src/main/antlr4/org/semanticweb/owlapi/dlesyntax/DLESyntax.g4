grammar DLESyntax;

// ── Entry point ──────────────────────────────────────────────────────────────

ontology
    : (prefixDecl | ontologyDecl | versionDecl | importDecl)* statement* EOF
    ;

// @prefix xsd: <http://www.w3.org/2001/XMLSchema#>
prefixDecl   : AT_PREFIX   PNAME_NS IRI ;
ontologyDecl : AT_ONTOLOGY iriRef       ;
versionDecl  : AT_VERSION  iriRef       ;
importDecl   : AT_IMPORT   iriRef       ;

// An IRI reference: either a full angle-bracket IRI or a prefixed/bare name.
iriRef : IRI | name ;

statement
    : annotation
    | axiom
    ;

// ── Annotations ──────────────────────────────────────────────────────────────

annotation
    : AT_LABEL     name STRING                # LabelAnnotation
    | AT_DOC       name STRING                # DocAnnotation
    | AT_STORAGE   name STRING                # StorageAnnotation
    | AT_DB        name STRING?                # DbAnnotation
    | AT_ANN       name name annotationValue           # AnnAnnotation
    | name '(' name (',' name)* ')' DEFINED_AS_LINE   # PredicateDefinition
    | name DEFINED_AS_LINE                             # FolAnnotation
    ;

annotationValue
    : STRING   # StringAnnotationValue
    | name     # IriAnnotationValue
    ;

// ── Axioms ───────────────────────────────────────────────────────────────────
//
// More specific alternatives (HasKeyAxiom, FunctionalPropertyAxiom,
// AnnProp*Axiom) are listed first.  ANTLR4 LL(*) disambiguates the remaining
// SubClassAxiom / EquivAxiom alternatives via adaptive lookahead.

axiom
    : classExpr SUBCLASS keyExpr                    # HasKeyAxiom
    | cardSymbol NUMBER propertyExpr DOT classExpr  # FunctionalPropertyAxiom
    | TRANS '(' name ')'                            # TransitiveRoleAxiom
    | FUNC  '(' name ')'                            # FunctionalRoleAxiom
    | REF   '(' name ')'                            # ReflexiveRoleAxiom
    | IRREF '(' name ')'                            # IrreflexiveRoleAxiom
    | SYM   '(' name ')'                            # SymmetricRoleAxiom
    | ASYM  '(' name ')'                            # AsymmetricRoleAxiom
    | DISJ  '(' name (',' name)+ ')'               # DisjointRoleAxiom
    | name DOMAIN name                              # AnnPropDomainAxiom
    | name RANGE  name                              # AnnPropRangeAxiom
    | chainExpr SUBCLASS name                       # SubPropertyChainAxiom
    | name EQUIV chainExpr                          # PropertyChainEquivAxiom
    | propertyExpr EQUIV propertyExpr SUBCLASS propertyExpr # ChainedEquivSubAxiom
    | classExpr SUBCLASS classExpr                  # SubClassAxiom
    | classExpr EQUIV    classExpr                  # EquivAxiom
    ;

// A property chain: q ∘ r⁻ (∘ is U+2218 RING OPERATOR).
chainExpr
    : propertyExpr (CHAIN propertyExpr)+
    ;

keyExpr
    : 'key' '(' name (',' name)* ')'
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

literal
    : STRING   # StringLiteral
    | NUMBER   # NumberLiteral
    | BOOL     # BoolLiteral
    ;

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
EXACT        : '='      ;   //    (exactly n)
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

// Strings — may span multiple lines (annotation values often do)
STRING : '"' (~["\\] | '\\' .)* '"' ;

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
// Only digits are admitted after the colon. Anything a bare NAME can already spell
// keeps its bare form, so this adds no second spelling for an existing name.
// Longest-match keeps it apart from PNAME_NS: `:1` matches two characters here and
// one there, while the `:` of an `@prefix :` declaration is followed by a space and
// cannot match this at all.
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

