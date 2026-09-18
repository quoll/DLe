package io.github.quoll.owltx;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.dlesyntax.DLESyntaxStorer;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import javax.annotation.Nullable;
import org.semanticweb.owlapi.io.UnparsableOntologyException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * owltx: OWL syntax converter.
 *
 * Usage: {@code owltx [--format <fmt>] <input-file> [<output-file>]}
 *
 * If no output file is given, writes to stdout.
 * If no format is given, it is inferred from the output file extension,
 * or defaults to DLE Syntax.
 */
public class Main {

    /** Not instantiated. */
    private Main() {}

    // Maps format name aliases (lowercase) to OWLDocumentFormat instances
    private static final Map<String, OWLDocumentFormat> FORMAT_BY_NAME = new HashMap<>();

    // Maps file extensions (lowercase, without dot) to OWLDocumentFormat instances
    private static final Map<String, OWLDocumentFormat> FORMAT_BY_EXT = new HashMap<>();

    static {
        OWLDocumentFormat dle         = new DLESyntaxDocumentFormat();
        OWLDocumentFormat dl          = new DLSyntaxDocumentFormat();
        OWLDocumentFormat dlhtml      = new DLSyntaxHTMLDocumentFormat();
        OWLDocumentFormat functional  = new FunctionalSyntaxDocumentFormat();
        OWLDocumentFormat manchester  = new ManchesterSyntaxDocumentFormat();
        OWLDocumentFormat owlxml      = new OWLXMLDocumentFormat();
        OWLDocumentFormat rdfxml      = new RDFXMLDocumentFormat();
        OWLDocumentFormat turtle      = new TurtleDocumentFormat();
        OWLDocumentFormat krss        = new KRSSDocumentFormat();
        OWLDocumentFormat krss2       = new KRSS2DocumentFormat();
        OWLDocumentFormat latex       = new LatexDocumentFormat();

        // Format name aliases
        FORMAT_BY_NAME.put("dle",           dle);
        FORMAT_BY_NAME.put("dlesyntax",     dle);
        FORMAT_BY_NAME.put("dl",            dl);
        FORMAT_BY_NAME.put("dlsyntax",      dl);
        FORMAT_BY_NAME.put("dlhtml",        dlhtml);
        FORMAT_BY_NAME.put("dlsyntaxhtml",  dlhtml);
        FORMAT_BY_NAME.put("functional",    functional);
        FORMAT_BY_NAME.put("ofn",           functional);
        FORMAT_BY_NAME.put("manchester",    manchester);
        FORMAT_BY_NAME.put("omn",           manchester);
        FORMAT_BY_NAME.put("owlxml",        owlxml);
        FORMAT_BY_NAME.put("owx",           owlxml);
        FORMAT_BY_NAME.put("rdfxml",        rdfxml);
        FORMAT_BY_NAME.put("rdf",           rdfxml);
        FORMAT_BY_NAME.put("owl",           rdfxml);
        FORMAT_BY_NAME.put("turtle",        turtle);
        FORMAT_BY_NAME.put("ttl",           turtle);
        FORMAT_BY_NAME.put("krss",          krss);
        FORMAT_BY_NAME.put("krss2",         krss2);
        FORMAT_BY_NAME.put("latex",         latex);
        FORMAT_BY_NAME.put("tex",           latex);

        // File extension mappings
        FORMAT_BY_EXT.put("dle",    dle);
        FORMAT_BY_EXT.put("dl",     dl);
        FORMAT_BY_EXT.put("html",   dlhtml);
        FORMAT_BY_EXT.put("htm",    dlhtml);
        FORMAT_BY_EXT.put("ofn",    functional);
        FORMAT_BY_EXT.put("omn",    manchester);
        FORMAT_BY_EXT.put("owx",    owlxml);
        FORMAT_BY_EXT.put("rdf",    rdfxml);
        FORMAT_BY_EXT.put("owl",    rdfxml);
        FORMAT_BY_EXT.put("xml",    rdfxml);
        FORMAT_BY_EXT.put("ttl",    turtle);
        FORMAT_BY_EXT.put("krss",   krss);
        FORMAT_BY_EXT.put("krss2",  krss2);
        FORMAT_BY_EXT.put("latex",  latex);
        FORMAT_BY_EXT.put("tex",    latex);
    }

    /**
     * Entry point. Parses arguments and converts the input ontology to the requested format.
     *
     * @param args command-line arguments
     * @throws Exception if the ontology cannot be loaded or written
     */
    /**
     * The command line entry point.
     *
     * <p>Thin on purpose. {@link #run} does the work and signals the end of the command by
     * throwing {@link ExitRequest}, so every failure path can be reached from a test. None of
     * them could be before: {@code die} called {@code System.exit}, so a test that reached one
     * would have taken the test JVM with it. That is why the message formatters here were
     * thoroughly tested and the guards that invoke them were not tested at all — reverting the
     * guard around an unloadable input left the whole module green.
     */
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (ExitRequest e) {
            if (e.getMessage() != null) {
                System.err.println("Error: " + e.getMessage());
            }
            System.exit(e.status);
        }
    }

    /**
     * The command is over, with the status the process should exit with.
     *
     * <p>No stack trace and no suppression: it is a control signal, not a fault, and the
     * trace would be of this class rather than of anything that went wrong.
     */
    static class ExitRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** The process exit status. */
        final int status;

        ExitRequest(int status, @Nullable String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    /** Everything {@link #main} does, with the exits raised rather than taken. */
    static void run(String[] args) throws Exception {
        String formatName  = null;
        String inputFile   = null;
        String outputFile  = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-f":
                case "--format":
                    if (i + 1 >= args.length) {
                        die("Option " + args[i] + " requires an argument");
                    }
                    formatName = args[++i];
                    break;
                case "-h":
                case "--help":
                    printUsage();
                    throw new ExitRequest(0, null);
                default:
                    if (args[i].startsWith("-")) {
                        die("Unknown option: " + args[i]);
                    }
                    if (inputFile == null) {
                        inputFile = args[i];
                    } else if (outputFile == null) {
                        outputFile = args[i];
                    } else {
                        die("Unexpected argument: " + args[i]);
                    }
            }
        }

        if (inputFile == null) {
            printUsage();
            throw new ExitRequest(1, null);
        }

        // Determine output format: 1. CLI option  2. output file extension  3. default dl
        OWLDocumentFormat outputFormat = resolveFormat(formatName, outputFile);

        // Load ontology
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getOntologyStorers().add(new org.semanticweb.owlapi.dlesyntax.DLESyntaxStorerFactory());
        manager.getOntologyParsers().add(new org.semanticweb.owlapi.dlesyntax.DLEOntologyParserFactory());
        File input = new File(inputFile);
        if (!input.exists()) {
            die("Input file not found: " + inputFile);
        }
        // A directory reached the parsers, which found nothing in it and returned an empty
        // ontology: exit 0, no axioms, nothing on stderr. Indistinguishable from converting
        // /dev/null, and `owltx somedir out.dle` silently wrote an empty document.
        if (!input.isFile()) {
            die("Not a file: " + inputFile);
        }

        // For DLE files, bypass OWLAPI's auto-detection (the Turtle parser
        // would otherwise claim the file by matching its @prefix lines).
        OWLOntology ontology;
        String inputExt = extension(inputFile);
        // An import that cannot be loaded is reported, not fatal. A conversion tool should
        // convert what it was given: the alternative is that an unreachable remote import,
        // or one file in a set that does not parse, takes the whole document down.
        manager.addMissingImportListener(event -> {
            String target = "<" + event.getImportedOntologyURI() + ">";
            // Through the same reduction the top-level load path uses. An import that does
            // not parse carries an UnparsableOntologyException, whose message embeds the log
            // of every parser that was tried — 634 lines and 54 KB for one bad import, and
            // at exit 0, since a missing import is a warning here rather than a failure.
            UnparsableOntologyException unparsable =
                unparsableCause(event.getCreationException());
            if (unparsable != null) {
                System.err.println("warning: could not load import " + target + ":");
                describeUnparsable(target, unparsable).stream().skip(1)
                    .forEach(line -> System.err.println("  " + line));
            } else {
                System.err.println("warning: could not load import " + target + ": "
                    + describe(event.getCreationException()));
            }
        });
        OWLOntologyLoaderConfiguration loaderConfig = manager.getOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        manager.setOntologyLoaderConfiguration(loaderConfig);

        if ("dle".equalsIgnoreCase(inputExt)) {
            try {
                ontology = manager.createOntology();
                var dleParser = new org.semanticweb.owlapi.dlesyntax.DLEOntologyParser();
                OWLDocumentFormat dleFormat = dleParser.parse(
                    new org.semanticweb.owlapi.io.FileDocumentSource(input),
                    ontology,
                    loaderConfig);
                dleParser.getWarnings().forEach(w -> System.err.println("warning: " + w));
                manager.setOntologyFormat(ontology, dleFormat);
                // Tell the manager where this came from. It is true, and it is what lets the
                // storer keep a relative @import relative when the output has no location of
                // its own — writing to stdout, where otherwise an absolute local path would
                // appear and `owltx in.dle > out.dle` would disagree with `owltx in.dle out.dle`.
                manager.setOntologyDocumentIRI(ontology, IRI.create(input));
            } catch (Exception | StackOverflowError e) {
                die("parsing " + input + ": " + describe(e));
                return; // unreachable, but satisfies compiler
            }
        } else {
            // Guarded, as the DLE branch above already is. An unloadable input threw
            // UnparsableOntologyException out of main, and because that exception's message
            // embeds the log of every parser that was tried, the user was shown 583 lines
            // with a Java stack trace on the end. The parsers' own complaints are the useful
            // part, so they are kept, one line each, and the trace is not.
            try {
                ontology = manager.loadOntologyFromOntologyDocument(input);
            } catch (UnparsableOntologyException e) {
                describeUnparsable(input.toString(), e).forEach(System.err::println);
                throw new ExitRequest(1, null);
            } catch (Exception | StackOverflowError e) {
                die("loading " + input + ": " + describe(e));
                return; // unreachable, but satisfies the compiler
            }
        }

        // Copy prefix mappings from the source format to the output format so
        // that output syntaxes that support prefixes (OFN, Manchester, Turtle, …)
        // use short-form names instead of full IRIs.
        //
        // Except a prefix that rebinds a conventional label to some other namespace. Those
        // writers abbreviate every IRI they can against the map they are given, so handing
        // them `rdfs:` bound elsewhere made them write the *real* RDFS vocabulary under it:
        // `C ⊑ D` came out as `:C rdfs:subClassOf :D` against the rebound namespace, and
        // reading that back gave an annotation assertion on a foreign property with the
        // subsumption gone. Exit 0, and the document's only logical axiom lost.
        //
        // The DLe storer handles the same document correctly — it mints a fresh prefix for
        // the real namespace — so the rebinding is kept for DLe output, where it round-trips,
        // and dropped for the formats that cannot express it. A dropped prefix costs
        // readability, never meaning: the writer falls back to the full IRI.
        OWLDocumentFormat sourceFormat = ontology.getFormat();
        if (sourceFormat instanceof PrefixDocumentFormat
                && outputFormat instanceof PrefixDocumentFormat) {
            boolean dleOutput = outputFormat instanceof DLESyntaxDocumentFormat;
            ((PrefixDocumentFormat) sourceFormat).getPrefixName2PrefixMap()
                .forEach((label, namespace) -> {
                    if (!dleOutput && org.semanticweb.owlapi.dlesyntax.DLESyntaxStorerBase
                            .rebindsConventionalPrefix(label, namespace)) {
                        return;
                    }
                    ((PrefixDocumentFormat) outputFormat).setPrefix(label, namespace);
                });
        }

        // Write output
        if (outputFile != null) {
            try {
                writeToFile(manager, ontology, outputFormat, outputFile);
            } catch (Exception | StackOverflowError e) {
                die("writing to " + outputFile + ": " + describe(e));
            }
        } else {
            // Guarded, as the file branch above already is. This was the one unguarded save,
            // so `--format krss` — advertised in --help, with no storer behind it — reached
            // the user as a raw OWLStorerNotFoundException trace, while the same failure to a
            // file produced a clean one-line message.
            try {
                manager.saveOntology(ontology, outputFormat,
                    new StreamDocumentTarget(System.out));
            } catch (Exception | StackOverflowError e) {
                die("writing to standard output: " + describe(e));
            }
        }
    }

    /**
     * The innermost {@link UnparsableOntologyException} in a chain of causes, if there is one.
     *
     * <p>A failed import wraps it, so the message the user would otherwise see is the
     * embedded parser log rather than the reduced form.
     */
    @Nullable
    static UnparsableOntologyException unparsableCause(@Nullable Throwable t) {
        for (Throwable at = t; at != null && at.getCause() != at; at = at.getCause()) {
            if (at instanceof UnparsableOntologyException) {
                return (UnparsableOntologyException) at;
            }
        }
        return null;
    }

    /**
     * A message for a person, including for the failures that carry none of their own.
     *
     * <p>{@link StackOverflowError} has a null message, and it is an {@code Error} rather
     * than an {@code Exception}, so every guard here used to let it out: a document nesting
     * expressions past about a thousand deep printed 1024 frames of OWL API internals. The
     * depth is the useful fact, and the only thing the reader can act on.
     */
    static String describe(@Nullable Throwable t) {
        if (t instanceof StackOverflowError) {
            return "the document nests expressions too deeply to process. Flatten the most"
                + " deeply nested expression, or give the JVM a larger stack with -Xss.";
        }
        return t == null ? "no reason given" : rootMessage(t);
    }

    /**
     * The innermost message, for a diagnostic a person will read.
     *
     * <p>{@code OWLOntologyStorageException.getMessage()} returns its cause's
     * {@code toString()}, which put {@code java.io.FileNotFoundException:} in front of
     * perfectly good text like "Permission denied".
     */
    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message != null ? message : root.getClass().getSimpleName();
    }

    /**
     * Writes an ontology to a named file.
     *
     * <p>Package-private and separate from {@code main} so a test can exercise the real
     * write path. That matters more than it looks: the first attempt at guarding the
     * encoding here asserted on {@code saveOntology(o, format, IRI)} called from the test
     * itself, which is OWL API's code and always UTF-8 — so the guard passed no matter what
     * this method did, and a deliberately broken version of it still went green.
     *
     * <p>Saving by IRI rather than through a stream, because the storer needs the document's
     * location to write an import back as the relative path it came in as. It also encodes
     * UTF-8, which {@code FileDocumentTarget} does not: that target's writer is
     * {@code new FileWriter(file)}, using the platform default charset, which turns every
     * DLe operator into a question mark wherever the default is not UTF-8.
     *
     * <p>The parent directory must already exist. Saving by IRI would otherwise create it —
     * {@code AbstractOWLStorer} calls {@code mkdirs()} — so a mistyped output path would
     * silently materialise a directory tree instead of failing.
     */
    static void writeToFile(OWLOntologyManager manager, OWLOntology ontology,
                            OWLDocumentFormat outputFormat, String outputFile)
            throws OWLOntologyStorageException {
        java.io.File target = new java.io.File(outputFile);
        java.io.File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            // Say which of the two things is wrong. Saving by IRI would create a missing
            // parent, so this has to be pre-empted rather than reported afterwards — but
            // a parent that exists and is a plain file is a different mistake.
            throw new OWLOntologyStorageException(outputFile + " ("
                + (parent.exists() ? "Not a directory" : "No such file or directory") + ")");
        }
        manager.saveOntology(ontology, outputFormat, IRI.create(target));
    }

    /** A DLE format instance; the default when nothing else determines one. */
    private static OWLDocumentFormat newDefaultFormat() {
        return new DLESyntaxDocumentFormat();
    }

    /**
     * Resolves the output format: an explicit {@code --format}, then the output
     * file's extension, then DLE.
     *
     * <p>Package-private so the defaulting can be tested. The stdout default was
     * wrong for a long time precisely because nothing checked it.
     *
     * <p>Every branch returns a fresh instance. The lookup tables hold one
     * instance per format, and {@code main} copies the source document's prefixes
     * into whatever this returns — so handing back the table's own instance would
     * leave those prefixes on it for the life of the JVM, and a second conversion
     * would render short names against the first document's namespaces.
     */
    static OWLDocumentFormat resolveFormat(String formatName, String outputFile) {
        // 1. Explicit format option
        if (formatName != null) {
            OWLDocumentFormat fmt = FORMAT_BY_NAME.get(formatName.toLowerCase());
            if (fmt == null) {
                die("Unknown format: " + formatName
                    + "\nKnown formats: " + String.join(", ", FORMAT_BY_NAME.keySet()));
            }
            return freshCopyOf(fmt);
        }

        // 2. Output file extension
        if (outputFile != null) {
            String ext = extension(outputFile);
            if (ext != null) {
                OWLDocumentFormat fmt = FORMAT_BY_EXT.get(ext.toLowerCase());
                if (fmt != null) {
                    return freshCopyOf(fmt);
                }
            }
        }

        // 3. Default: DLE syntax. This returned plain DL syntax, so
        //    `owltx doc.dle` with no output file and no --format silently
        //    produced a different language: no @prefix declarations, no header,
        //    `self` where DLe requires `Self`, and no line breaks at all. The
        //    class javadoc and the usage text said DL Syntax and have been
        //    corrected too — the default is DLe because that is what this tool
        //    is for.
        return newDefaultFormat();
    }

    /**
     * A new instance of the same format class as {@code prototype}.
     *
     * <p>The lookup tables are built once and shared, but a format is mutable —
     * {@code main} writes prefixes into it — so callers must not receive the
     * shared instance. Every format here has a public no-argument constructor;
     * if one ever does not, the prototype is returned rather than failing the
     * conversion, since sharing is only a hazard across repeated use in one JVM.
     */
    private static OWLDocumentFormat freshCopyOf(OWLDocumentFormat prototype) {
        try {
            return prototype.getClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return prototype;
        }
    }

    /** Returns the extension of a filename (without the dot), or null if none. */
    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1);
    }

    private static void printUsage() {
        System.err.println("Usage: owltx [--format <fmt>] <input-file> [<output-file>]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  -f, --format <fmt>   Output format (overrides file extension)");
        System.err.println("  -h, --help           Show this help");
        System.err.println();
        System.err.println("Formats (name aliases):");
        System.err.println("  dle, dlesyntax       DLE Syntax — extended DL with annotations (default)");
        System.err.println("  dl, dlsyntax         DL Syntax — plain DL, no DLe extensions");
        System.err.println("  dlhtml, dlsyntaxhtml DL Syntax HTML");
        System.err.println("  functional, ofn      OWL Functional Syntax");
        System.err.println("  manchester, omn      Manchester OWL Syntax");
        System.err.println("  owlxml, owx          OWL/XML");
        System.err.println("  rdfxml, rdf, owl     RDF/XML");
        System.err.println("  turtle, ttl          Turtle");
        System.err.println("  krss                 KRSS");
        System.err.println("  krss2                KRSS2");
        System.err.println("  latex, tex           LaTeX");
        System.err.println();
        System.err.println("File extensions are also used for format detection:");
        System.err.println("  .dle .dl .html .htm .ofn .omn .owx .rdf .owl .xml .ttl .krss .krss2 .latex .tex");
        System.err.println();
        System.err.println("If no output file is given, output goes to stdout.");
    }

    /** How many parser complaints are worth reading before they stop adding anything. */
    static final int PARSER_ERRORS_SHOWN = 6;

    /**
     * What to tell the user when nothing could parse the input.
     *
     * <p>Separate from the printing so it can be tested, and because the raw exception is
     * not usable as a message: its own text embeds the log of every parser that was tried,
     * so letting it reach the top printed 583 lines with a Java stack trace on the end.
     *
     * <p>Three things make it readable. Each parser gets one line, because some of them
     * report the token, the position, and then an enumeration of everything they would have
     * accepted instead. Identical lines are collapsed, because OWL API tries twenty-two
     * parsers and ten of them share the name {@code RioParserImpl} — they are RDF dialects,
     * and they mostly fail the same way. And the list is capped, because after half a dozen
     * the rest add nothing.
     */
    static List<String> describeUnparsable(String input, UnparsableOntologyException e) {
        List<String> lines = new ArrayList<>();
        lines.add("Error: " + input + " could not be parsed as an ontology.");
        Set<String> distinct = new LinkedHashSet<>();
        e.getExceptions().forEach((parser, cause) ->
            distinct.add(shortParserName(parser) + ": " + firstLine(rootMessage(cause))));
        distinct.stream().limit(PARSER_ERRORS_SHOWN).forEach(line -> lines.add("  " + line));
        if (distinct.size() > PARSER_ERRORS_SHOWN) {
            lines.add("  ... and " + (distinct.size() - PARSER_ERRORS_SHOWN)
                + " more parsers, all of which also failed.");
        }
        lines.add("Use --format to name the syntax if it was not detected from the file name.");
        return lines;
    }

    /**
     * The first line of a message, trimmed.
     *
     * <p>Some parsers report a token, the position, and then an enumeration of everything
     * they would have accepted instead — dozens of lines, and a stack trace after it. One
     * line each keeps all of them readable side by side, which is the point of listing them.
     */
    static String firstLine(String message) {
        String text = message == null ? "" : message.trim();
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline).trim();
        return line.isEmpty() ? "could not parse it" : line;
    }

    /** The parser's class name alone, since the package adds nothing a reader needs. */
    static String shortParserName(Object parser) {
        String name = parser.getClass().getSimpleName();
        return name.isEmpty() ? parser.getClass().getName() : name;
    }

    /** Ends the command with a message, as a failure. */
    private static void die(String message) {
        throw new ExitRequest(1, message);
    }
}
