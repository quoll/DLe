package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every way the command can fail, reached through the code that fails.
 *
 * <p>These paths were unreachable from a test until {@link Main#run} was split out of
 * {@code main}: {@code die} called {@code System.exit}, so a test that got to one would have
 * ended the test JVM. The consequence was an inverted suite — the message formatters were
 * tested in detail while the guards that call them had no coverage at all, and reverting the
 * guard around an unloadable input left the module green.
 *
 * <p>Each test asserts the exit status and the text a person would read. A stack trace in any
 * of them is the defect: the reader cannot act on a frame of OWL API internals.
 */
class CliFailureTest {

    /** Runs the command, capturing stderr, and returns the exit request it raised. */
    private static Result run(String... args) throws Exception {
        PrintStream saved = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
        try {
            Main.run(args);
            return new Result(0, captured.toString(StandardCharsets.UTF_8.name()), null);
        } catch (Main.ExitRequest e) {
            return new Result(e.status, captured.toString(StandardCharsets.UTF_8.name()),
                e.getMessage());
        } finally {
            System.setErr(saved);
        }
    }

    private static final class Result {
        final int status;
        final String stderr;
        final String message;

        Result(int status, String stderr, String message) {
            this.status = status;
            this.stderr = stderr;
            this.message = message;
        }

        /** Everything the user saw: the captured stream plus the message main would print. */
        String everything() {
            return stderr + (message == null ? "" : "Error: " + message);
        }
    }

    private static void assertNoStackTrace(Result r) {
        assertFalse(r.everything().contains("\tat "),
            () -> "a stack trace reached the user:\n" + r.everything());
        assertFalse(r.everything().contains("Exception in thread"),
            () -> "an uncaught throwable reached the user:\n" + r.everything());
    }

    /**
     * A directory is not an input.
     *
     * <p>It reached the parsers, which found nothing in it and handed back an empty ontology:
     * exit 0, no axioms, nothing on stderr, byte-identical to converting {@code /dev/null}.
     * {@code owltx somedir out.dle} silently wrote an empty document.
     */
    @Test
    void aDirectoryIsRefused(@TempDir Path dir) throws Exception {
        Result r = run(dir.toString(), "--format", "functional");
        assertEquals(1, r.status, () -> "a directory must not convert: " + r.everything());
        assertNotNull(r.message);
        assertTrue(r.message.contains(dir.toString()),
            () -> "the message must name the path: " + r.message);
    }

    /** A missing input still names what it looked for. */
    @Test
    void aMissingInputIsRefused(@TempDir Path dir) throws Exception {
        Path absent = dir.resolve("nope.dle");
        Result r = run(absent.toString());
        assertEquals(1, r.status);
        assertTrue(r.message != null && r.message.contains("nope.dle"),
            () -> "the message must name the missing file: " + r.message);
    }

    /**
     * An unloadable non-DLe input is reduced, not dumped.
     *
     * <p>This is the guard whose absence left the module green: the reduction was tested by
     * calling it directly, and nothing tested that anything called it. Reverting it printed
     * 583 lines with a trace on the end.
     */
    @Test
    void anUnloadableInputIsReducedToItsParserComplaints(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("broken.ofn");
        Files.write(broken, "this is not an ontology <<<>>>\n".getBytes(StandardCharsets.UTF_8));
        Result r = run(broken.toString(), "--format", "functional");
        assertEquals(1, r.status, () -> "an unloadable input must fail: " + r.everything());
        assertNoStackTrace(r);
        assertTrue(r.everything().contains("could not be parsed as an ontology"),
            () -> "expected the reduced form, got:\n" + r.everything());
        assertTrue(r.everything().split("\n").length <= Main.PARSER_ERRORS_SHOWN + 4,
            () -> "the reduction must stay short, got " + r.everything().split("\n").length
                + " lines:\n" + r.everything());
    }

    /**
     * A broken DLe input names the file it was reading.
     *
     * <p>It used to say only "parsing DLE file", and used {@code getMessage()} where the
     * neighbouring branch used the {@code rootMessage} helper written for exactly this.
     */
    @Test
    void aBrokenDleInputNamesTheFile(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("broken.dle");
        Files.write(broken, "A ⊑ ⊑ ⊑ B\n".getBytes(StandardCharsets.UTF_8));
        Result r = run(broken.toString(), "--format", "functional");
        assertEquals(1, r.status);
        assertNoStackTrace(r);
        assertTrue(r.message != null && r.message.contains("broken.dle"),
            () -> "the message must name the file: " + r.message);
    }

    /**
     * A format with no storer fails the same way to stdout as to a file.
     *
     * <p>The stdout branch was the one unguarded save in the command, so {@code --format
     * krss} — advertised in {@code --help}, with no storer behind it — reached the user as a
     * raw {@code OWLStorerNotFoundException} trace, while the identical failure to a file
     * produced a clean line.
     */
    @Test
    void aFormatWithNoStorerFailsCleanlyToStdout(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("in.dle");
        Files.write(source, ("@prefix : <http://example.org/o#>\nA ⊑ B\n")
            .getBytes(StandardCharsets.UTF_8));
        Result r = run(source.toString(), "--format", "krss");
        assertEquals(1, r.status, () -> "an unusable format must fail: " + r.everything());
        assertNoStackTrace(r);
        assertTrue(r.message != null && r.message.contains("standard output"),
            () -> "the message must say where it was writing: " + r.message);
    }

    /**
     * A document nested past the stack depth is reported, not dumped.
     *
     * <p>{@code StackOverflowError} is an {@code Error}, so every {@code catch (Exception)}
     * in the command let it out: 1024 frames of OWL API internals, on every path and in every
     * format. The depth is the only thing the reader can act on.
     */
    @Test
    void aDocumentNestedTooDeeplyIsReported(@TempDir Path dir) throws Exception {
        int depth = 4000;
        StringBuilder document = new StringBuilder("@prefix : <http://example.org/o#>\nA ⊑ ");
        for (int i = 0; i < depth; i++) document.append("∃r.(");
        document.append('B');
        for (int i = 0; i < depth; i++) document.append(')');
        Path source = dir.resolve("deep.dle");
        Files.write(source, document.append('\n').toString().getBytes(StandardCharsets.UTF_8));

        Result r = run(source.toString(), "--format", "functional");
        assertEquals(1, r.status, () -> "it must fail rather than crash: " + r.everything());
        assertNoStackTrace(r);
        assertTrue(r.message != null && r.message.contains("deeply"),
            () -> "the message must say what was wrong with the document: " + r.message);
    }

    /** {@code --help} is a success, and says something. */
    @Test
    void helpSucceeds() throws Exception {
        Result r = run("--help");
        assertEquals(0, r.status, "--help is not a failure");
        assertNull(r.message);
    }

    /** An unknown option names the option. */
    @Test
    void anUnknownOptionIsRefused() throws Exception {
        Result r = run("--bogus");
        assertEquals(1, r.status);
        assertTrue(r.message != null && r.message.contains("--bogus"),
            () -> "the message must name the option: " + r.message);
    }

    /** {@link Main#describe} supplies a message for the throwables that carry none. */
    @Test
    void aThrowableWithNoMessageStillDescribesItself() {
        assertTrue(Main.describe(new StackOverflowError()).contains("deeply"),
            "a stack overflow must be described by what the document did");
        assertFalse(Main.describe(new StackOverflowError()).isEmpty());
        assertEquals("no reason given", Main.describe(null));
    }

    /** {@link Main#unparsableCause} finds a wrapped parser failure, which an import carries. */
    @Test
    void aWrappedParserFailureIsFound() {
        org.semanticweb.owlapi.io.UnparsableOntologyException inner =
            new org.semanticweb.owlapi.io.UnparsableOntologyException(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/o"),
                new java.util.LinkedHashMap<org.semanticweb.owlapi.io.OWLParser,
                    org.semanticweb.owlapi.io.OWLParserException>(),
                new org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration());
        assertSame(inner, Main.unparsableCause(inner));
        assertSame(inner, Main.unparsableCause(new RuntimeException("wrapper", inner)));
        assertNull(Main.unparsableCause(new RuntimeException("nothing in here")));
        assertNull(Main.unparsableCause(null));
    }
}
