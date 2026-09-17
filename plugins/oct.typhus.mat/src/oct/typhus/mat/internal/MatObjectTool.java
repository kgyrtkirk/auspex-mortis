package oct.typhus.mat.internal;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.query.registry.CommandLine;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.snapshot.model.ObjectReference;

import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

import oct.typhus.mat.internal.OpenSnapshots.Dump;

/**
 * Reads one object out of an open heap dump: its class, its sizes, its fields and its
 * references, with array data by slice.
 */
public final class MatObjectTool extends SnapshotTool {

	private static final int DEFAULT_REFERENCE_LIMIT = 50;

	private static final int DEFAULT_ARRAY_LENGTH = 64;

	private static final int MAX_ARRAY_LENGTH = 65536;

	@Override
	public String getName() {
		return "mat_object";
	}

	@Override
	public String getDescription() {
		return "Reads one object of a heap dump that is ALREADY OPEN in the IDE: its class, shallow and retained size, the GC roots that hold it, every field with its value, the objects it points at and, for an array, a slice of its elements. Address in, structure out, so a walk down an object graph is one call per hop instead of a query, a result tab and an expand of a tree whose columns cannot be read. A reference field carries the address, the class and, where the target has one, the text MAT shows for it, which is what makes a String, a char[] or a boxed number readable without a second call. Sizes are bytes; a negative retained size is MAT's way of saying it only has an approximate minimum because the dominator tree was not computed for that object. Arrays come back by slice: 'arrayOffset' and 'arrayLength' say which part, because a million element array is not an answer. 'show' opens the object in the heap editor as MAT's own expandable tree as well, for the person sitting at the IDE to continue from; it is off by default so that a walk down a graph does not leave a pane behind at every hop. It never opens or parses a dump: an unknown one is an error listing what is open.";
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "address":       {"type":"string","description":"The object, as 0x… or a decimal address. Either this or objectId."},
				    "objectId":      {"type":"integer","minimum":0,"description":"The object by MAT's internal id, as other tools report it."},
				    %s,
				    "fields":        {"type":"boolean","default":true,"description":"Include the fields of the object, or the static fields when it is a class."},
				    "resolveRefs":   {"type":"boolean","default":true,"description":"Look up the class and text of every referenced object. One extra read per reference; turn it off for an object with hundreds of them."},
				    "outbound":      {"type":"boolean","default":true,"description":"Include the objects this one points at, named as MAT names them."},
				    "outboundLimit": {"type":"integer","default":50,"minimum":1,"maximum":10000},
				    "inbound":       {"type":"boolean","default":false,"description":"Include the objects that point at this one. Costs an index read over the whole dump."},
				    "inboundLimit":  {"type":"integer","default":50,"minimum":1,"maximum":10000},
				    "arrayOffset":   {"type":"integer","default":0,"minimum":0,"description":"First array element to return."},
				    "arrayLength":   {"type":"integer","default":64,"minimum":0,"maximum":65536,"description":"Array elements to return. 0 reports the length and no data."},
				    "show":          {"type":"boolean","default":true,"description":"Also open this object in the heap editor as MAT's expandable object tree, for the person at the IDE to carry on from. Pass false for a hop in a long walk, whose pane would only be noise."}
				  },
				  "additionalProperties": false
				}""".formatted(DUMP_PROPERTY);
	}

	@Override
	protected JsonObject run(Dump dump, Map<String, Object> arguments, IProgressMonitor monitor)
			throws SnapshotException, BadRequestException {
		ToolArguments args = ToolArguments.of(arguments);
		ISnapshot snapshot = dump.snapshot();
		IObject object = snapshot.getObject(objectId(snapshot, args));
		JsonObject result = describe(object, snapshot);
		if (args.getBoolean("fields", true)) {
			result.put("fields", fields(object, snapshot, args.getBoolean("resolveRefs", true)));
		}
		array(object, args, snapshot, result);
		if (args.getBoolean("outbound", true)) {
			result.put("outbound", outbound(object, snapshot, args.getInt("outboundLimit", DEFAULT_REFERENCE_LIMIT, 1, 10000)));
		}
		if (args.getBoolean("inbound", false)) {
			result.put("inbound", inbound(object, snapshot, args.getInt("inboundLimit", DEFAULT_REFERENCE_LIMIT, 1, 10000)));
		}
		if (args.getBoolean("show", true)) {
			// a second, one-object execution rather than a rendering of what is above:
			// what the person at the IDE wants to carry on from is MAT's own expandable
			// object tree, and list_objects is the query that produces it
			String address = Addresses.format(object.getObjectAddress());
			ResultPanes.show(dump, CommandLine.parse(dump.context(), "list_objects " + address)
					.execute(new MonitorListener(monitor)), paneTitle(object, address), result);
		}
		return result;
	}

	/** The simple class name and the address, because a tab full of package names says nothing. */
	private static String paneTitle(IObject object, String address) {
		String className = object.getClazz().getName();
		return className.substring(className.lastIndexOf('.') + 1) + " " + address;
	}

	private static int objectId(ISnapshot snapshot, ToolArguments args) throws BadRequestException {
		if (args.has("objectId")) {
			return args.getInt("objectId", 0, 0, Integer.MAX_VALUE);
		}
		String address = args.getString("address");
		if (address == null) {
			throw new BadRequestException("Either 'address' (0x…) or 'objectId' is required.");
		}
		try {
			return snapshot.mapAddressToId(Addresses.parse(address));
		} catch (SnapshotException e) {
			throw new BadRequestException(
					"No object at %s in this dump. Addresses come from mat_query rows or from another object's references.%n%s"
							.formatted(address, e.getMessage()));
		}
	}

	private static JsonObject describe(IObject object, ISnapshot snapshot) throws SnapshotException {
		IClass type = object.getClazz();
		JsonObject described = new JsonObject().put("address", Addresses.format(object.getObjectAddress()))
				.put("objectId", Integer.valueOf(object.getObjectId())).put("class", type.getName())
				.put("classAddress", Addresses.format(type.getObjectAddress()))
				.put("superClass", type.getSuperClass() == null ? null : type.getSuperClass().getName())
				.put("classLoader", Addresses.of(snapshot, type.getClassLoaderId()))
				.put("shallow", Long.valueOf(object.getUsedHeapSize()))
				.put("retained", Long.valueOf(object.getRetainedHeapSize()))
				.put("text", object.getClassSpecificName());
		GCRootInfo[] roots = object.getGCRootInfo();
		if (roots != null && roots.length > 0) {
			described.put("gcRoot", GCRootInfo.getTypeSetAsString(roots));
		}
		if (object instanceof IClass loaded) {
			described.put("instances", Integer.valueOf(loaded.getNumberOfObjects()))
					.put("instanceShallowSize", Long.valueOf(loaded.getHeapSizePerInstance()));
		}
		return described;
	}

	private static JsonArray fields(IObject object, ISnapshot snapshot, boolean resolve) {
		List<Field> fields = switch (object) {
		case IInstance instance -> instance.getFields();
		case IClass type -> type.getStaticFields();
		default -> List.of();
		};
		JsonArray described = new JsonArray();
		for (Field field : fields) {
			JsonObject rendered = new JsonObject().put("name", field.getName())
					.put("type", Types.name(field.getType()));
			value(field.getValue(), snapshot, resolve, rendered);
			described.add(rendered);
		}
		return described;
	}

	/**
	 * Adds the value of a field: a primitive as itself, a reference as the address plus
	 * what the target is, because a bare address costs another call to become an answer.
	 */
	private static void value(Object raw, ISnapshot snapshot, boolean resolve, JsonObject target) {
		switch (raw) {
		case null -> target.put("value", null);
		case ObjectReference reference -> {
			target.put("value", Addresses.format(reference.getObjectAddress()));
			if (resolve) {
				referent(reference.getObjectAddress(), snapshot, target);
			}
		}
		case Character character -> target.put("value", String.valueOf(character));
		case Number number -> target.put("value", number);
		case Boolean bool -> target.put("value", bool);
		default -> target.put("value", String.valueOf(raw));
		}
	}

	/** The class and the text of the object at an address, or the reason it could not be read. */
	private static void referent(long address, ISnapshot snapshot, JsonObject target) {
		if (address == 0) {
			target.put("value", null);
			return;
		}
		try {
			IObject referent = snapshot.getObject(snapshot.mapAddressToId(address));
			target.put("refClass", referent.getClazz().getName());
			String text = referent.getClassSpecificName();
			if (text != null) {
				target.put("refText", text);
			}
		} catch (SnapshotException e) {
			// a reference into an object the dump does not contain is data about the
			// dump, not a failure of the read
			target.put("refError", e.getMessage());
		}
	}

	private static void array(IObject object, ToolArguments args, ISnapshot snapshot, JsonObject result) {
		int offset = args.getInt("arrayOffset", 0, 0, Integer.MAX_VALUE);
		int wanted = args.getInt("arrayLength", DEFAULT_ARRAY_LENGTH, 0, MAX_ARRAY_LENGTH);
		switch (object) {
		case IPrimitiveArray primitives -> {
			int length = primitives.getLength();
			int returned = slice(length, offset, wanted);
			JsonArray values = new JsonArray();
			Object data = returned == 0 ? null : primitives.getValueArray(offset, returned);
			for (int index = 0; index < returned; index++) {
				values.add(primitive(Array.get(data, index)));
			}
			result.put("array", new JsonObject().put("componentType", Types.name(primitives.getType()))
					.put("length", Integer.valueOf(length)).put("offset", Integer.valueOf(offset))
					.put("returned", Integer.valueOf(returned)).put("values", values));
		}
		case IObjectArray references -> {
			int length = references.getLength();
			int returned = slice(length, offset, wanted);
			JsonArray elements = new JsonArray();
			long[] addresses = returned == 0 ? new long[0] : references.getReferenceArray(offset, returned);
			for (long address : addresses) {
				if (address == 0) {
					elements.add(null);
					continue;
				}
				JsonObject element = new JsonObject().put("address", Addresses.format(address));
				referent(address, snapshot, element);
				elements.add(element);
			}
			result.put("array", new JsonObject().put("componentType", "ref").put("length", Integer.valueOf(length))
					.put("offset", Integer.valueOf(offset)).put("returned", Integer.valueOf(returned))
					.put("elements", elements));
		}
		default -> {
			// not an array: nothing to slice
		}
		}
	}

	private static int slice(int length, int offset, int wanted) {
		return offset >= length ? 0 : Math.min(wanted, length - offset);
	}

	private static Object primitive(Object element) {
		return switch (element) {
		case Character character -> String.valueOf(character);
		case Number number -> number;
		case Boolean bool -> bool;
		case null, default -> null;
		};
	}

	private static JsonArray outbound(IObject object, ISnapshot snapshot, int limit) {
		JsonArray references = new JsonArray();
		for (NamedReference reference : object.getOutboundReferences()) {
			if (references.size() >= limit) {
				break;
			}
			JsonObject described = new JsonObject().put("name", reference.getName()).put("address",
					Addresses.format(reference.getObjectAddress()));
			referent(reference.getObjectAddress(), snapshot, described);
			references.add(described);
		}
		return references;
	}

	private static JsonArray inbound(IObject object, ISnapshot snapshot, int limit) throws SnapshotException {
		int[] ids = snapshot.getInboundRefererIds(object.getObjectId());
		JsonArray referers = new JsonArray();
		for (int index = 0; index < ids.length && index < limit; index++) {
			IObject referer = snapshot.getObject(ids[index]);
			referers.add(new JsonObject().put("address", Addresses.format(referer.getObjectAddress()))
					.put("class", referer.getClazz().getName())
					.put("shallow", Long.valueOf(referer.getUsedHeapSize())));
		}
		return referers;
	}
}
