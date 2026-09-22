package hu.rxd.auspex.mortis.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What can be told about a dump before MAT opens it.
 * <p>
 * Both answers are given to the caller before a parse that costs tens of minutes, so both are
 * worth a test that costs none: MAT's index naming, and the gzip comment that decides whether
 * a compressed dump is read where it lies or unpacked in full.
 */
class DumpFileTest {

	@TempDir
	Path directory;

	@Test
	void theIndexSitsBesideTheDumpUnderMatsName() {
		assertEquals("foo.index", indexNameOf("foo.hprof"));
		assertEquals("broker-2026-09-15-02-02-52.index", indexNameOf("broker-2026-09-15-02-02-52.hprof.gz"));
		assertEquals("dump.index", indexNameOf("dump"));
	}

	@Test
	void indexesAreReusedOnlyWhenTheyAreNoOlderThanTheDump() throws IOException {
		File dump = write("foo.hprof", new byte[] { 1 });
		assertFalse(new DumpFile(dump).indexesReusable(), "no index");

		File index = write("foo.index", new byte[] { 1 });
		index.setLastModified(dump.lastModified() + 1000L);
		assertTrue(new DumpFile(dump).indexesReusable(), "index younger than the dump");

		index.setLastModified(dump.lastModified() - 1000L);
		assertFalse(new DumpFile(dump).indexesReusable(), "index older than the dump");
	}

	@Test
	void aChunkedDumpIsRecognisedByItsGzipComment() throws IOException {
		assertTrue(new DumpFile(gzipWithComment("HPROF BLOCKSIZE=1048576")).chunked());
		assertFalse(new DumpFile(gzipWithComment("compressed by hand")).chunked());
	}

	@Test
	void aPlainGzipDumpIsNotChunked() throws IOException {
		File dump = new File(directory.toFile(), "plain.hprof.gz");
		try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(dump.toPath()))) {
			out.write(new byte[] { 1, 2, 3 });
		}
		assertTrue(new DumpFile(dump).compressed());
		assertFalse(new DumpFile(dump).chunked());
	}

	@Test
	void aDumpThatIsNotCompressedAtAllIsNotChunked() throws IOException {
		assertFalse(new DumpFile(write("foo.hprof", new byte[] { 1, 2, 3 })).chunked());
	}

	private String indexNameOf(String dumpName) {
		return new DumpFile(new File(directory.toFile(), dumpName)).indexFile().getName();
	}

	private File write(String name, byte[] content) throws IOException {
		File file = new File(directory.toFile(), name);
		Files.write(file.toPath(), content);
		return file;
	}

	/** A gzip header carrying {@code comment}, which is all the chunk size check reads. */
	private File gzipWithComment(String comment) throws IOException {
		byte[] text = comment.getBytes(StandardCharsets.UTF_8);
		byte[] header = new byte[10 + text.length + 1];
		header[0] = (byte) 0x1f;
		header[1] = (byte) 0x8b;
		header[2] = 8;
		header[3] = 1 << 4;
		header[9] = (byte) 0xff;
		System.arraycopy(text, 0, header, 10, text.length);
		return write("chunked.hprof.gz", header);
	}
}
