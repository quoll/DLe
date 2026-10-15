package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which stream the usage text goes to, which depends on why it is being shown.
 *
 * <p>The same text serves two situations and they are not alike. Asked for with
 * {@code --help} it is an answer, and belongs on stdout: a reader can page it, redirect it,
 * grep it. Shown because no input file was given it is a diagnostic, and belongs on stderr
 * with every other error — landing it on stdout would feed a page of usage text into
 * whatever the command was piped into, which is how a help message ends up inside somebody's
 * ontology.
 *
 * <p>Both were on stderr before, which made {@code owltx --help | less} print nothing.
 */
class UsageStreamTest {

    /** Runs the command, capturing both streams, and returns them with the exit status. */
    private static Result run(String... args) throws Exception {
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8.name()));
        try {
            Main.run(args);
            return new Result(0, out.toString(StandardCharsets.UTF_8.name()),
                err.toString(StandardCharsets.UTF_8.name()));
        } catch (Main.ExitRequest e) {
            return new Result(e.status, out.toString(StandardCharsets.UTF_8.name()),
                err.toString(StandardCharsets.UTF_8.name()));
        } finally {
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    private static final class Result {
        final int status;
        final String stdout;
        final String stderr;

        Result(int status, String stdout, String stderr) {
            this.status = status;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    @Test
    void helpThatWasAskedForGoesToStandardOutput() throws Exception {
        Result r = run("--help");
        assertEquals(0, r.status, "asking for help is not a failure");
        assertTrue(r.stdout.startsWith("Usage: owltx"), "expected usage on stdout: " + r.stdout);
        assertEquals("", r.stderr, "nothing belongs on stderr when help was requested");
    }

    @Test
    void theShortFormIsTheSame() throws Exception {
        assertEquals(run("--help").stdout, run("-h").stdout);
    }

    /** The whole text, not a first line: a reader needs the formats and the extensions too. */
    @Test
    void helpCarriesTheWholeText() throws Exception {
        String help = run("--help").stdout;
        assertTrue(help.contains("--format"), help);
        assertTrue(help.contains("--version"), help);
        assertTrue(help.contains("Formats (name aliases):"), help);
        assertTrue(help.contains("File extensions are also used"), help);
        assertTrue(help.contains("output goes to stdout"), help);
    }

    @Test
    void usageShownBecauseNothingWasGivenGoesToStandardError() throws Exception {
        Result r = run();
        assertEquals(1, r.status, "being given nothing to do is a failure");
        assertTrue(r.stderr.startsWith("Usage: owltx"), "expected usage on stderr: " + r.stderr);
        assertEquals("", r.stdout, "a diagnostic must not pollute the output stream");
    }

    /**
     * The point of the split, stated as the thing a user would notice: the text is identical,
     * so the only thing distinguishing a question from a complaint is where it was sent.
     */
    @Test
    void bothSituationsShowTheSameText() throws Exception {
        assertEquals(run("--help").stdout, run().stderr);
    }
}
