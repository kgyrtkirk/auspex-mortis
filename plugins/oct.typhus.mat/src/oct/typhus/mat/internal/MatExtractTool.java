package oct.typhus.mat.internal;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.SnapshotException;

import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import oct.typhus.mat.internal.GraphExtract.Options;
import oct.typhus.mat.internal.OpenSnapshots.Dump;

/**
 * Writes the object graph below one object of an open dump to a file.
 */
public final class MatExtractTool extends SnapshotTool {

	private static final int DEFAULT_MAX_DEPTH = 8;

	private static final int DEFAULT_MAX_OBJECTS = 100_000;

	private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

	private static final int DEFAULT_MAX_ARRAY_LENGTH = 1_048_576;

	@Override
	public String getName() {
		return "mat_extract";
	}

	@Override
	public String getDescription() {
		return "WRITES A FILE: walks the object graph below one object of a heap dump that is ALREADY OPEN in the IDE and puts its data into a flat binary, so that the analysis can continue in a plain JVM with a debugger and a test instead of a 50 GB dump. Defaults to a DRY RUN that walks and reports but writes nothing; pass dryRun false with 'out' to actually write. Use it when a question stops being about sizes and starts needing experiments on the data; a query answers everything else more cheaply. The walk follows field and array references only: classes and class loaders are never entered, because every object points at its class and from there the walk would reach most of the heap. 'excludeClasses' takes regular expressions matched against the whole class name and stops the walk at them, which is how a lazily built cache is left behind - in the case this was built for, one such cache cost more than the data it indexed. A direct or mapped ByteBuffer is recorded as unrecoverable rather than as zeros, because its bytes are not in the dump at all and an extract that restores and lies is worse than one that stops. maxDepth, maxObjects, maxBytes and maxArrayLength all bound the walk and the answer says which one stopped it and how much was left unwalked. The report is the actual diagnostic: bytes per class, what was excluded, what could not travel. It is written next to the binary as <out>.json and returned either way. The binary layout is documented in the class comment of GraphExtract and a reader has to be written for the types being restored; there is deliberately no generic restorer, because reflectively rebuilding a class whose fields have drifted since the dump fails silently.";
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "address":        {"type":"string","description":"The root object, as 0x… or a decimal address."},
				    "out":            {"type":"string","description":"Absolute path of the binary to write. Required unless this is a dry run; the report goes next to it as <out>.json."},
				    "dryRun":         {"type":"boolean","default":true,"description":"Walk and report without writing anything. The reported fileBytes is the size the file would have."},
				    %s,
				    "maxDepth":       {"type":"integer","default":8,"minimum":0,"maximum":1000,"description":"References to follow from the root. The root is depth 0."},
				    "maxObjects":     {"type":"integer","default":100000,"minimum":1,"maximum":10000000},
				    "maxBytes":       {"type":"integer","default":268435456,"minimum":1,"description":"Stop once the shallow sizes written reach this."},
				    "maxArrayLength": {"type":"integer","default":1048576,"minimum":0,"description":"Elements written per array. The file records the real length next to the written count, so truncation stays visible."},
				    "excludeClasses": {"type":"array","items":{"type":"string"},"description":"Regular expressions, matched against the whole class name. The walk stops at a match and the report counts what was left behind, e.g. ['.*\\\\$Lazy.*','java\\\\.util\\\\.concurrent\\\\..*']."}
				  },
				  "required": ["address"],
				  "additionalProperties": false
				}""".formatted(DUMP_PROPERTY);
	}

	@Override
	protected JsonObject run(Dump dump, Map<String, Object> arguments, IProgressMonitor monitor)
			throws McpToolException, SnapshotException, BadRequestException {
		ToolArguments args = ToolArguments.of(arguments);
		String address = args.getString("address");
		if (address == null) {
			throw new BadRequestException("'address' is required: the object to walk from, as 0x….");
		}
		boolean dryRun = args.getBoolean("dryRun", true);
		Path out = outputPath(args.getString("out"), dryRun);
		Options options = new Options(Addresses.parse(address), args.getInt("maxDepth", DEFAULT_MAX_DEPTH, 0, 1000),
				args.getInt("maxObjects", DEFAULT_MAX_OBJECTS, 1, 10_000_000), maxBytes(arguments),
				args.getInt("maxArrayLength", DEFAULT_MAX_ARRAY_LENGTH, 0, Integer.MAX_VALUE), excludes(arguments));
		JsonObject summary = walk(dump, options, monitor, out);
		summary.put("dryRun", Boolean.valueOf(dryRun));
		if (out == null) {
			return summary.put("note", "Nothing was written. Pass dryRun false and 'out' to write the file.");
		}
		report(out, summary);
		return summary.put("out", out.toString()).put("report", out + ".json");
	}

	private static JsonObject walk(Dump dump, Options options, IProgressMonitor monitor, Path out)
			throws McpToolException, SnapshotException {
		try (OutputStream sink = out == null ? OutputStream.nullOutputStream()
				: new BufferedOutputStream(Files.newOutputStream(out));
				DataOutputStream stream = new DataOutputStream(sink)) {
			return new GraphExtract(dump.snapshot(), options, monitor).run(stream);
		} catch (IOException e) {
			throw new McpToolException("Could not write the extract to " + out, e);
		}
	}

	/**
	 * Writes the report beside the binary.
	 * <p>
	 * The report is the summary as it is returned here: one representation, readable by
	 * the person who finds the file next week and by the program that reads it back.
	 */
	private static void report(Path out, JsonObject summary) throws McpToolException {
		Path report = Path.of(out + ".json");
		try {
			Files.writeString(report, summary.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new McpToolException("Wrote the extract but not its report at " + report, e);
		}
	}

	private static Path outputPath(String out, boolean dryRun) throws BadRequestException {
		if (dryRun) {
			return null;
		}
		if (out == null) {
			throw new BadRequestException("'out' is required unless this is a dry run: where to write the extract.");
		}
		Path path;
		try {
			path = Path.of(out);
		} catch (InvalidPathException e) {
			throw new BadRequestException("'%s' is not a usable path: %s".formatted(out, e.getMessage()));
		}
		if (!path.isAbsolute()) {
			throw new BadRequestException("'out' has to be absolute; the IDE's working directory is not yours.");
		}
		if (Files.exists(path)) {
			throw new BadRequestException("%s exists. Extracts are not overwritten; delete it or name another file."
					.formatted(path));
		}
		if (path.getParent() == null || !Files.isDirectory(path.getParent())) {
			throw new BadRequestException("The directory of %s does not exist.".formatted(path));
		}
		return path;
	}

	/** Read from the raw arguments because a byte budget does not fit in an int. */
	private static long maxBytes(Map<String, Object> arguments) throws BadRequestException {
		Object value = arguments.get("maxBytes");
		if (value == null) {
			return DEFAULT_MAX_BYTES;
		}
		long bytes;
		try {
			bytes = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			throw new BadRequestException("'maxBytes' is not a number: " + value);
		}
		if (bytes <= 0) {
			throw new BadRequestException("'maxBytes' has to be positive.");
		}
		return bytes;
	}

	private static List<Pattern> excludes(Map<String, Object> arguments) throws BadRequestException {
		List<Pattern> patterns = new ArrayList<>();
		if (arguments.get("excludeClasses") instanceof List<?> raw) {
			for (Object element : raw) {
				String expression = String.valueOf(element);
				try {
					patterns.add(Pattern.compile(expression));
				} catch (PatternSyntaxException e) {
					throw new BadRequestException("'%s' is not a regular expression: %s".formatted(expression, e.getMessage()));
				}
			}
		}
		return patterns;
	}
}
