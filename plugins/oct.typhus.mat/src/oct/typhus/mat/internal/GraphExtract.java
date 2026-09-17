package oct.typhus.mat.internal;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.SetInt;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.ObjectReference;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Walks the object graph from one object and writes what it finds to a flat file, so that
 * a plain JVM can rebuild the data and experiments can stop going through the dump.
 * <p>
 * <strong>File format, version 1.</strong> Big endian, {@link DataOutputStream} encoding,
 * strings in Java modified UTF-8:
 *
 * <pre>
 * "TYPHUS01"                 8 ASCII bytes
 * int    formatVersion       1
 * utf    dumpPath
 * long   rootAddress
 * record*                    until the end tag
 * byte   0                   end tag
 * int    objectCount         the number of records, to check the file is whole
 * </pre>
 *
 * A record is
 *
 * <pre>
 * byte   1                   record tag
 * long   address
 * utf    className
 * byte   kind                1 instance, 2 object array, 3 primitive array, 4 class, 5 unrecoverable
 * long   shallowSize
 * long   retainedSize
 * </pre>
 *
 * followed, by kind, by
 *
 * <pre>
 * 1, 4:  int fieldCount, then per field: utf name, byte type, value
 *        value is a reference address (long, 0 for null) for type 2, and the primitive
 *        itself otherwise: byte for 4 and 8, char for 5, float 6, double 7, short 9,
 *        int 10, long 11
 * 2:     int length, int written, then 'written' addresses (long, 0 for null)
 * 3:     byte componentType, int length, int written, then 'written' raw values
 * 5:     utf reason
 * </pre>
 *
 * {@code written} is smaller than {@code length} when the array was longer than
 * {@code maxArrayLength}: a reader that treats the two as equal is reading a lie, which is
 * why both are in the file.
 */
final class GraphExtract {

	private static final byte TAG_END = 0;

	private static final byte TAG_RECORD = 1;

	private static final byte KIND_INSTANCE = 1;

	private static final byte KIND_OBJECT_ARRAY = 2;

	private static final byte KIND_PRIMITIVE_ARRAY = 3;

	private static final byte KIND_CLASS = 4;

	private static final byte KIND_UNRECOVERABLE = 5;

	private static final int REPORTED_CLASSES = 30;

	private static final int REPORTED_UNRECOVERABLE = 50;

	/** What to walk and where to stop. */
	record Options(long rootAddress, int maxDepth, int maxObjects, long maxBytes, int maxArrayLength,
			List<Pattern> exclude) {
	}

	/** How much of one class travelled. */
	private static final class Tally {

		private int count;

		private long bytes;
	}

	private record Visit(int id, int depth) {
	}

	private final ISnapshot snapshot;

	private final Options options;

	private final IProgressMonitor monitor;

	private final SetInt visited = new SetInt();

	private final Map<String, Tally> written = new LinkedHashMap<>();

	private final Map<String, Tally> excluded = new LinkedHashMap<>();

	private final List<JsonObject> unrecoverable = new ArrayList<>();

	private int objects;

	private long bytes;

	private int deepest;

	private int truncatedArrays;

	private int dangling;

	private String stoppedBy;

	GraphExtract(ISnapshot snapshot, Options options, IProgressMonitor monitor) {
		this.snapshot = snapshot;
		this.options = options;
		this.monitor = monitor;
	}

	/**
	 * Walks the graph and writes it.
	 *
	 * @param out where the records go; a stream over {@link java.io.OutputStream#nullOutputStream()}
	 *            makes the run a dry one that still reports the size the file would have
	 * @return what travelled, what did not, and why the walk stopped
	 */
	JsonObject run(DataOutputStream out) throws SnapshotException, IOException {
		int rootId = snapshot.mapAddressToId(options.rootAddress());
		out.writeBytes("TYPHUS01");
		out.writeInt(1);
		out.writeUTF(snapshot.getSnapshotInfo().getPath());
		out.writeLong(options.rootAddress());

		Deque<Visit> queue = new ArrayDeque<>();
		queue.add(new Visit(rootId, 0));
		visited.add(rootId);
		while (!queue.isEmpty()) {
			if (monitor.isCanceled()) {
				stoppedBy = "cancelled";
				break;
			}
			if (objects >= options.maxObjects()) {
				stoppedBy = "maxObjects";
				break;
			}
			if (bytes >= options.maxBytes()) {
				stoppedBy = "maxBytes";
				break;
			}
			Visit visit = queue.remove();
			deepest = Math.max(deepest, visit.depth());
			record(out, snapshot.getObject(visit.id()), visit.depth(), queue);
		}
		out.writeByte(TAG_END);
		out.writeInt(objects);
		out.flush();
		return summary(out.size(), queue.size());
	}

	private void record(DataOutputStream out, IObject object, int depth, Deque<Visit> queue)
			throws SnapshotException, IOException {
		String className = object.getClazz().getName();
		long shallow = object.getUsedHeapSize();
		out.writeByte(TAG_RECORD);
		out.writeLong(object.getObjectAddress());
		out.writeUTF(className);
		String lost = offHeapReason(object);
		if (lost != null) {
			out.writeByte(KIND_UNRECOVERABLE);
			sizes(out, object, shallow);
			out.writeUTF(lost);
			if (unrecoverable.size() < REPORTED_UNRECOVERABLE) {
				unrecoverable.add(new JsonObject().put("address", Addresses.format(object.getObjectAddress()))
						.put("class", className).put("reason", lost));
			}
		} else {
			switch (object) {
			case IPrimitiveArray primitives -> {
				out.writeByte(KIND_PRIMITIVE_ARRAY);
				sizes(out, object, shallow);
				primitiveArray(out, primitives);
			}
			case IObjectArray references -> {
				out.writeByte(KIND_OBJECT_ARRAY);
				sizes(out, object, shallow);
				objectArray(out, references, depth, queue);
			}
			case IClass type -> {
				out.writeByte(KIND_CLASS);
				sizes(out, object, shallow);
				fields(out, type.getStaticFields(), depth, queue);
			}
			case IInstance instance -> {
				out.writeByte(KIND_INSTANCE);
				sizes(out, object, shallow);
				fields(out, instance.getFields(), depth, queue);
			}
			default -> {
				out.writeByte(KIND_UNRECOVERABLE);
				sizes(out, object, shallow);
				out.writeUTF("MAT models this object as " + object.getClass().getName() + ", which has no field walk");
			}
			}
		}
		objects++;
		bytes += shallow;
		tally(written, className, shallow);
	}

	private static void sizes(DataOutputStream out, IObject object, long shallow) throws IOException {
		out.writeLong(shallow);
		out.writeLong(object.getRetainedHeapSize());
	}

	private void fields(DataOutputStream out, List<Field> fields, int depth, Deque<Visit> queue)
			throws SnapshotException, IOException {
		out.writeInt(fields.size());
		for (Field field : fields) {
			out.writeUTF(field.getName());
			out.writeByte(field.getType());
			Object value = field.getValue();
			if (field.getType() == IObject.Type.OBJECT) {
				long address = value instanceof ObjectReference reference ? reference.getObjectAddress() : 0;
				out.writeLong(address);
				enqueue(queue, address, depth + 1);
			} else {
				primitive(out, field.getType(), value);
			}
		}
	}

	private void objectArray(DataOutputStream out, IObjectArray references, int depth, Deque<Visit> queue)
			throws SnapshotException, IOException {
		int length = references.getLength();
		int count = Math.min(length, options.maxArrayLength());
		out.writeInt(length);
		out.writeInt(count);
		if (count < length) {
			truncatedArrays++;
		}
		long[] addresses = count == 0 ? new long[0] : references.getReferenceArray(0, count);
		for (long address : addresses) {
			out.writeLong(address);
			enqueue(queue, address, depth + 1);
		}
	}

	private void primitiveArray(DataOutputStream out, IPrimitiveArray primitives) throws IOException {
		int length = primitives.getLength();
		int count = Math.min(length, options.maxArrayLength());
		out.writeByte(primitives.getType());
		out.writeInt(length);
		out.writeInt(count);
		if (count < length) {
			truncatedArrays++;
		}
		if (count == 0) {
			return;
		}
		Object data = primitives.getValueArray(0, count);
		for (int index = 0; index < count; index++) {
			primitive(out, primitives.getType(), Array.get(data, index));
		}
	}

	/**
	 * Writes a primitive in the width its type code names.
	 * <p>
	 * A null value is written as zero of that width: a primitive field the parser could
	 * not read is a hole in the dump, not a reason to abandon the extract, and the record
	 * has to stay the length the reader computes from the type.
	 */
	private static void primitive(DataOutputStream out, int type, Object value) throws IOException {
		Number number = value instanceof Number parsed ? parsed : null;
		switch (type) {
		case IObject.Type.BOOLEAN -> out.writeByte(Boolean.TRUE.equals(value) ? 1 : 0);
		case IObject.Type.BYTE -> out.writeByte(number == null ? 0 : number.byteValue());
		case IObject.Type.CHAR -> out.writeChar(value instanceof Character character ? character.charValue() : 0);
		case IObject.Type.SHORT -> out.writeShort(number == null ? 0 : number.shortValue());
		case IObject.Type.INT -> out.writeInt(number == null ? 0 : number.intValue());
		case IObject.Type.LONG -> out.writeLong(number == null ? 0 : number.longValue());
		case IObject.Type.FLOAT -> out.writeFloat(number == null ? 0 : number.floatValue());
		case IObject.Type.DOUBLE -> out.writeDouble(number == null ? 0 : number.doubleValue());
		default -> out.writeLong(number == null ? 0 : number.longValue());
		}
	}

	/**
	 * Queues the object at an address unless a rule stops it there.
	 * <p>
	 * Classes and class loaders are never followed: every instance points at its class,
	 * and from a class the graph reaches the loader, its other classes and, through them,
	 * most of the heap.
	 */
	private void enqueue(Deque<Visit> queue, long address, int depth) {
		if (address == 0 || depth > options.maxDepth()) {
			return;
		}
		try {
			int id = snapshot.mapAddressToId(address);
			if (snapshot.isClass(id) || snapshot.isClassLoader(id)) {
				tally(excluded, "<class or class loader>", 0);
				return;
			}
			String className = snapshot.getClassOf(id).getName();
			if (options.exclude().stream().anyMatch(pattern -> pattern.matcher(className).matches())) {
				tally(excluded, className, snapshot.getHeapSize(id));
				return;
			}
			if (visited.add(id)) {
				queue.add(new Visit(id, depth));
			}
		} catch (SnapshotException e) {
			// an address the dump does not resolve is data about the dump: the reference
			// is written, the object behind it simply is not there
			dangling++;
		}
	}

	/**
	 * Why an object's bytes cannot travel, or {@code null} when they can.
	 * <p>
	 * A direct or mapped buffer has no backing array in the heap, so its content is not in
	 * the dump at all. Writing zeros for it would produce an extract that restores, runs
	 * and lies.
	 */
	private static String offHeapReason(IObject object) throws SnapshotException {
		if (!(object instanceof IInstance) || !object.getClazz().doesExtend("java.nio.Buffer")) {
			return null;
		}
		if (object.resolveValue("hb") != null) {
			return null;
		}
		Object capacity = object.resolveValue("capacity");
		return "direct or mapped buffer: hb is null, so its %s bytes are outside the heap and not in this dump"
				.formatted(capacity == null ? "?" : capacity);
	}

	private static void tally(Map<String, Tally> tallies, String className, long size) {
		Tally tally = tallies.computeIfAbsent(className, name -> new Tally());
		tally.count++;
		tally.bytes += size;
	}

	private JsonObject summary(int fileBytes, int queued) {
		JsonObject summary = new JsonObject().put("root", Addresses.format(options.rootAddress()))
				.put("objects", Integer.valueOf(objects)).put("shallowBytes", Long.valueOf(bytes))
				.put("fileBytes", Integer.valueOf(fileBytes)).put("depthReached", Integer.valueOf(deepest))
				.put("truncatedArrays", Integer.valueOf(truncatedArrays))
				.put("danglingReferences", Integer.valueOf(dangling))
				.put("stoppedBy", stoppedBy == null ? "exhausted" : stoppedBy)
				.put("notWalked", Integer.valueOf(queued)).put("byClass", tallies(written))
				.put("excluded", tallies(excluded));
		if (!unrecoverable.isEmpty()) {
			JsonArray lost = new JsonArray();
			unrecoverable.forEach(lost::add);
			summary.put("unrecoverable", lost);
		}
		return summary;
	}

	/** The biggest classes first, because the tail of a class histogram explains nothing. */
	private static JsonArray tallies(Map<String, Tally> tallies) {
		JsonArray reported = new JsonArray();
		tallies.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Tally> entry) -> entry.getValue().bytes)
				.reversed()).limit(REPORTED_CLASSES)
				.forEach(entry -> reported.add(new JsonObject().put("class", entry.getKey())
						.put("count", Integer.valueOf(entry.getValue().count))
						.put("bytes", Long.valueOf(entry.getValue().bytes))));
		return reported;
	}
}
