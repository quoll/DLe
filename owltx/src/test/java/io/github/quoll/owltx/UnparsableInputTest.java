package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.UnparsableOntologyException;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.io.OWLParserException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the user is told when nothing can parse the input.
 *
 * <p>The DLe load path was guarded and the other one was not, so an unloadable input threw
 * {@code UnparsableOntologyException} out of {@code main}. That exception's own message
 * embeds the log of every parser that was tried, so the user was shown 583 lines with a Java
 * stack trace on the end — for a file that simply was not an ontology.
 */
class UnparsableInputTest {

    /** An exception carrying the parser failures given, in order. */
    private static UnparsableOntologyException exceptionWith(Map<String, String> failures) {
        Map<OWLParser, OWLParserException> byParser = new LinkedHashMap<>();
        failures.forEach((parser, message) ->
            byParser.put(new NamedParser(parser), new OWLParserException(message)));
        return new UnparsableOntologyException(IRI.create("file:/tmp/x.ttl"), byParser,
            new OWLOntologyLoaderConfiguration());
    }

    /**
     * A stub parser. Its class name is what appears in the message — {@code
     * shortParserName} reads {@code getClass().getSimpleName()} — so every instance here
     * reports as {@code NamedParser} however it is constructed, which is what makes the
     * collapsing testable: it is the (name, message) pair that has to be distinct.
     */
    private static class NamedParser implements OWLParser {
        private final String name;
        NamedParser(String name) { this.name = name; }
        @Override public String toString() { return name; }
        @Override public org.semanticweb.owlapi.model.OWLDocumentFormat parse(
                org.semanticweb.owlapi.io.OWLOntologyDocumentSource source,
                org.semanticweb.owlapi.model.OWLOntology ontology,
                org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration config) {
            throw new UnsupportedOperationException();
        }
        @Override public org.semanticweb.owlapi.model.OWLDocumentFormatFactory
                getSupportedFormat() {
            throw new UnsupportedOperationException();
        }
    }

    /** A multi-line message becomes one line, which is most of the 583. */
    @Test
    void aMultiLineParserMessageIsReducedToItsFirstLine() {
        String sprawling = "Encountered unexpected token: \"this\"\n"
            + "    at line 1, column 1.\n"
            + "Was expecting one of:\n    <EOF>\n    \"Prefix\"\n    \"Ontology\"\n";
        assertEquals("Encountered unexpected token: \"this\"", Main.firstLine(sprawling));
    }

    /** An empty or absent message still says something. */
    @Test
    void anEmptyMessageStillSaysSomething() {
        assertEquals("could not parse it", Main.firstLine(null));
        assertEquals("could not parse it", Main.firstLine("   \n  "));
    }

    /** Repeated parsers are collapsed: ten of OWL API's twenty-two are RDF dialects. */
    @Test
    void identicalParserComplaintsAreCollapsed() {
        Map<String, String> failures = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            failures.put("RioParserImpl" + i, "Expected ':', found ' ' [line 1]");
        }
        List<String> lines = Main.describeUnparsable("x.ttl", exceptionWith(failures));
        long complaints = lines.stream().filter(l -> l.startsWith("  NamedParser")).count();
        assertEquals(1, complaints,
            () -> "ten parsers failing the same way is one thing to say: " + lines);
    }

    /** And the list is capped, with the remainder counted rather than dropped silently. */
    @Test
    void theListIsCappedAndTheRemainderCounted() {
        Map<String, String> failures = new LinkedHashMap<>();
        for (int i = 0; i < 22; i++) {
            failures.put("Parser" + i, "failure number " + i);
        }
        List<String> lines = Main.describeUnparsable("x.ttl", exceptionWith(failures));
        assertEquals(Main.PARSER_ERRORS_SHOWN,
            lines.stream().filter(l -> l.startsWith("  NamedParser")).count(),
            () -> "only the first few are shown: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("and 16 more parsers")),
            () -> "and the rest are counted, not dropped in silence: " + lines);
    }

    /** The whole message stays short, and names the file and the way out. */
    @Test
    void theMessageIsShortAndActionable() {
        Map<String, String> failures = new LinkedHashMap<>();
        for (int i = 0; i < 22; i++) {
            failures.put("Parser" + i, "failure " + i + "\n  at some.Frame(File.java:1)\n");
        }
        List<String> lines = Main.describeUnparsable("bad.ttl", exceptionWith(failures));
        assertTrue(lines.size() <= 10,
            () -> "583 lines was the defect; this is " + lines.size() + ": " + lines);
        assertTrue(lines.get(0).contains("bad.ttl"), () -> "names the file: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("--format")),
            () -> "and says what to try: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.trim().startsWith("at ")),
            () -> "no stack frames may survive: " + lines);
    }
}
