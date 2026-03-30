package io.github.quoll.owltx;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.dlesyntax.DLESyntaxStorer;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.io.FileDocumentTarget;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * owltx: OWL syntax converter.
 *
 * Usage: owltx [--format <fmt>] <input-file> [<output-file>]
 *
 * If no output file is given, writes to stdout.
 * If no format is given, it is inferred from the output file extension,
 * or defaults to DL Syntax.
 */
public class Main {

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

    public static void main(String[] args) throws Exception {
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
                    System.exit(0);
                    break;
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
            System.exit(1);
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

        // For DLE files, bypass OWLAPI's auto-detection (the Turtle parser
        // would otherwise claim the file by matching its @prefix lines).
        OWLOntology ontology;
        String inputExt = extension(inputFile);
        if ("dle".equalsIgnoreCase(inputExt)) {
            try {
                ontology = manager.createOntology();
                OWLDocumentFormat dleFormat = new org.semanticweb.owlapi.dlesyntax.DLEOntologyParser().parse(
                    new org.semanticweb.owlapi.io.FileDocumentSource(input),
                    ontology,
                    manager.getOntologyLoaderConfiguration());
                manager.setOntologyFormat(ontology, dleFormat);
            } catch (Exception e) {
                die("parsing DLE file: " + e.getMessage());
                return; // unreachable, but satisfies compiler
            }
        } else {
            ontology = manager.loadOntologyFromOntologyDocument(input);
        }

        // Copy prefix mappings from the source format to the output format so
        // that output syntaxes that support prefixes (OFN, Manchester, Turtle, …)
        // use short-form names instead of full IRIs.
        OWLDocumentFormat sourceFormat = ontology.getFormat();
        if (sourceFormat instanceof PrefixDocumentFormat
                && outputFormat instanceof PrefixDocumentFormat) {
            ((PrefixDocumentFormat) sourceFormat).getPrefixName2PrefixMap()
                .forEach(((PrefixDocumentFormat) outputFormat)::setPrefix);
        }

        // Write output
        if (outputFile != null) {
            try (OutputStream out = new FileOutputStream(outputFile)) {
                manager.saveOntology(ontology, outputFormat, new StreamDocumentTarget(out));
            } catch (Exception e) {
                die("writing to " + outputFile + ": " + e.getMessage());
            }
        } else {
            manager.saveOntology(ontology, outputFormat, new StreamDocumentTarget(System.out));
        }
    }

    private static OWLDocumentFormat resolveFormat(String formatName, String outputFile) {
        // 1. Explicit format option
        if (formatName != null) {
            OWLDocumentFormat fmt = FORMAT_BY_NAME.get(formatName.toLowerCase());
            if (fmt == null) {
                die("Unknown format: " + formatName
                    + "\nKnown formats: " + String.join(", ", FORMAT_BY_NAME.keySet()));
            }
            return fmt;
        }

        // 2. Output file extension
        if (outputFile != null) {
            String ext = extension(outputFile);
            if (ext != null) {
                OWLDocumentFormat fmt = FORMAT_BY_EXT.get(ext.toLowerCase());
                if (fmt != null) {
                    return fmt;
                }
            }
        }

        // 3. Default: DL Syntax
        return new DLSyntaxDocumentFormat();
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
        System.err.println("  dle, dlesyntax       DLE Syntax — extended DL with annotations");
        System.err.println("  dl, dlsyntax         DL Syntax (default)");
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

    private static void die(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
    }
}
