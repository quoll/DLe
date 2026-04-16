import tiktoken

# Define the three strings separately
prose = "The criticality property is restricted to integers between 1 and 5 (called NumericRange). Only Applications can have criticality values, and each Application can have at most one criticality value. Applications with a criticality of 4 or higher are classified as HighCriticality."

precise_prose = """NumericRange is defined as integers between 1 and 5 inclusive.
If something has a criticality property, then it must be an Application.
All criticality values must be NumericRange values (integers from 1 to 5).
Things can have at most one criticality value.
HighCriticality is defined as things that have a criticality value of 4 or higher."""

ofn = """DatatypeDefinition( :NumericRange
  DatatypeRestriction( xsd:integer
    xsd:minInclusive "1"^^xsd:integer
    xsd:maxInclusive "5"^^xsd:integer
  )
)

Declaration( DataProperty( :criticality ) )
FunctionalDataProperty( :criticality )
DataPropertyDomain( :criticality :Application )
DataPropertyRange( :criticality :NumericRange )

EquivalentClasses(
  :HighCriticality
  DataSomeValuesFrom( :criticality
    DatatypeRestriction( xsd:integer
      xsd:minInclusive "4"^^xsd:integer
    )
  )
)"""

ttl = """:NumericRange a rdfs:Datatype ;
              owl:equivalentClass [ a rdfs:Datatype ;
                                    owl:onDatatype xsd:integer ;
                                    owl:withRestrictions ( [xsd:minInclusive 1] [xsd:maxInclusive 5] ) ] .

:criticality a owl:DatatypeProperty ;
             a owl:FunctionalProperty ;
             rdfs:domain :Application ;
             rdfs:range :NumericRange  .

:HighCriticality a owl:Class ;
                 rdfs:subClassOf :Application ;
                 owl:equivalentClass [ a owl:Restriction ;
                                       owl:onProperty :criticality ;
                                       owl:someValuesFrom [ a rdfs:Datatype ;
                                                            owl:onDatatype xsd:integer ;
                                                            owl:withRestrictions ( [xsd:minInclusive 4] ) ] ] ;
                 rdfs:label "High Criticality" ."""

dl = """NumericRange ≡ xsd:integer[≥1 ⊓ ≤5]
∃criticality.⊤ ⊑ Application
⊤ ⊑ ∀criticality.NumericRange
≤1 criticality.⊤
HighCriticality ≡ ∃criticality.xsd:integer[≥4]"""

# Put them in a list of name/string pairs for evaluation
strings = [
    ("prose", prose),
    ("precise_prose", precise_prose),
    ("ofn", ofn),
    ("ttl", ttl),
    ("dl", dl)
]

# Initialize the tokenizer (using cl100k_base which is used by GPT-4 and GPT-3.5-turbo)
encoding = tiktoken.get_encoding("cl100k_base")

# Tokenize and display results
print("Token Counts:")
print("-" * 40)
for name, text in strings:
    tokens = encoding.encode(text)
    print(f"{name}: {len(tokens)} tokens")

