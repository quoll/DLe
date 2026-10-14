package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The version option, and the thing that makes it worth having: that what it says is true.
 *
 * <p>A version string is the one piece of output nobody checks by eye, because it looks right
 * whatever it says. So these tests do not assert a literal — that would need editing at every
 * release, and an assertion edited at every release is an assertion nobody reads. They compare
 * the reported version against the {@code VERSION} file, which the build writes from the POM
 * and the release workflow checks against the git tag. Chained, that means the number the
 * command prints is the number on the tag.
 */
class VersionOptionTest {

    /** Runs the command, capturing stdout and stderr separately, and returns both. */
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
    void theLongFormReportsTheVersionAndSucceeds() throws Exception {
        Result r = run("--version");
        assertEquals(0, r.status, "asking for the version is not a failure");
        assertEquals("owltx " + expected() + System.lineSeparator(), r.stdout);
    }

    @Test
    void theShortFormIsTheSame() throws Exception {
        assertEquals(run("--version").stdout, run("-V").stdout);
    }

    /**
     * stdout, not stderr. {@code --help} goes to stderr because it doubles as the message
     * when no input file is given; this is an answer to a question that was asked, and a
     * script reading it expects stdout.
     */
    @Test
    void theVersionGoesToStandardOutput() throws Exception {
        Result r = run("--version");
        assertTrue(r.stdout.contains("owltx"), "expected the version on stdout, got: " + r.stdout);
        assertEquals("", r.stderr, "nothing belongs on stderr here");
    }

    /**
     * The version is asked for and answered before anything else is attempted, so a bad
     * argument alongside it cannot turn the answer into an error. Reporting "Input file not
     * found" to someone who asked for a version number would be absurd.
     */
    @Test
    void theVersionIsReportedWithoutTouchingTheRestOfTheCommandLine() throws Exception {
        Result r = run("--version", "no-such-file.dle");
        assertEquals(0, r.status);
        assertTrue(r.stdout.startsWith("owltx "), r.stdout);
        assertEquals("", r.stderr);
    }

    /**
     * Filtering actually happened. Without it the resource reaches the jar holding the literal
     * {@code ${project.version}}, which prints without complaint and is wrong every time.
     */
    @Test
    void theVersionIsAnActualNumberAndNotAnUnexpandedProperty() {
        String reported = Main.version();
        assertFalse(reported.contains("$"), "the resource was not filtered: " + reported);
        assertFalse(reported.contains("missing") || reported.contains("unset"),
            "the version resource did not reach the classpath: " + reported);
        assertTrue(reported.matches("owltx \\d+\\.\\d+.*"), "unexpected shape: " + reported);
    }

    /** What the build wrote for the launch script, and what the release workflow checks. */
    private static String expected() throws Exception {
        Path version = Path.of("VERSION");
        assertTrue(Files.exists(version),
            "expected the build to have written VERSION in the module root");
        return Files.readString(version, StandardCharsets.UTF_8).trim();
    }
}
