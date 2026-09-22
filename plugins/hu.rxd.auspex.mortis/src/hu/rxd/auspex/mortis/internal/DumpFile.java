package hu.rxd.auspex.mortis.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A heap dump as it lies on disk: what can be told about it before Memory Analyzer opens it.
 */
record DumpFile(File file) {

	private static final int GZIP_MAGIC_FIRST = 0x1f;

	private static final int GZIP_MAGIC_SECOND = 0x8b;

	private static final int DEFLATE = 8;

	private static final int FEXTRA = 1 << 2;

	private static final int FNAME = 1 << 3;

	private static final int FCOMMENT = 1 << 4;

	/** Bytes of the gzip header between the flags and the optional fields. */
	private static final int MTIME_XFL_OS = 6;

	/** What the JVM writes into the gzip comment when it compresses a dump in seekable chunks. */
	private static final String BLOCK_SIZE = "HPROF BLOCKSIZE=";

	private static final int MAX_COMMENT = 1024;

	private static final String GZ = ".gz";

	/**
	 * Whether MAT will reuse the indexes beside the dump instead of parsing it again.
	 * <p>
	 * By MAT's own rule — the index is there and the dump is no newer than it — because a
	 * second rule that disagreed with MAT's would be worse than no answer at all.
	 */
	boolean indexesReusable() {
		File index = indexFile();
		return index.isFile() && file.lastModified() <= index.lastModified();
	}

	/** The master index MAT keeps beside the dump, whether or not it has been written yet. */
	File indexFile() {
		String name = file.getName();
		if (compressed()) {
			name = name.substring(0, name.length() - GZ.length());
		}
		int extension = name.lastIndexOf('.');
		return new File(file.getParentFile(), (extension < 0 ? name + "." : name.substring(0, extension + 1)) + "index");
	}

	boolean compressed() {
		return file.getName().endsWith(GZ);
	}

	/**
	 * Whether a compressed dump names the chunk size that lets MAT read it where it lies.
	 * <p>
	 * {@code jcmd GC.heap_dump -gz=N} writes it and plain {@code gzip} does not. Without it MAT
	 * falls back to decompressing the whole dump, into memory or onto disk, which is a bill the
	 * caller has to see before the parse rather than after.
	 */
	boolean chunked() throws IOException {
		try (DataInputStream header = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			if (header.readUnsignedByte() != GZIP_MAGIC_FIRST || header.readUnsignedByte() != GZIP_MAGIC_SECOND
					|| header.readUnsignedByte() != DEFLATE) {
				return false;
			}
			int flags = header.readUnsignedByte();
			header.skipNBytes(MTIME_XFL_OS);
			if ((flags & FEXTRA) != 0) {
				header.skipNBytes(Short.toUnsignedInt(Short.reverseBytes(header.readShort())));
			}
			if ((flags & FNAME) != 0) {
				zeroTerminated(header);
			}
			return (flags & FCOMMENT) != 0 && zeroTerminated(header).startsWith(BLOCK_SIZE);
		} catch (EOFException e) {
			return false;
		}
	}

	private static String zeroTerminated(InputStream in) throws IOException {
		ByteArrayOutputStream text = new ByteArrayOutputStream();
		for (int read = in.read(); read > 0 && text.size() < MAX_COMMENT; read = in.read()) {
			text.write(read);
		}
		return text.toString(StandardCharsets.UTF_8);
	}
}
