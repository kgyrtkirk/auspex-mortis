package oct.typhus.mat.internal;

import org.eclipse.mat.snapshot.model.IObject;

/**
 * The names of MAT's field and array element type codes.
 */
final class Types {

	private Types() {
	}

	/** The Java name of a type code, or {@code ref} for a reference. */
	static String name(int type) {
		return switch (type) {
		case IObject.Type.OBJECT -> "ref";
		case IObject.Type.BOOLEAN -> "boolean";
		case IObject.Type.CHAR -> "char";
		case IObject.Type.FLOAT -> "float";
		case IObject.Type.DOUBLE -> "double";
		case IObject.Type.BYTE -> "byte";
		case IObject.Type.SHORT -> "short";
		case IObject.Type.INT -> "int";
		case IObject.Type.LONG -> "long";
		default -> "type" + type;
		};
	}
}
