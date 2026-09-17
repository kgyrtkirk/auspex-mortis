package oct.typhus.mat.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.json.Json;

/**
 * What every tool promises the server before it is ever called.
 * <p>
 * A schema that does not parse is only found when a client tries to use the tool, and the
 * names are the addresses clients call: both are worth a test that runs in a second.
 */
class ToolContractTest {

	private static final List<IMcpTool> TOOLS = List.of(new MatQueryTool(), new MatObjectTool(), new MatExtractTool());

	@Test
	void theNamesAreTheOnesClientsCall() {
		assertEquals(List.of("mat_query", "mat_object", "mat_extract"), TOOLS.stream().map(IMcpTool::getName).toList());
	}

	@Test
	void everySchemaIsAnObjectSchemaThatParses() {
		for (IMcpTool tool : TOOLS) {
			Map<?, ?> schema = schemaOf(tool);
			assertEquals("object", schema.get("type"), tool.getName());
			assertInstanceOf(Map.class, schema.get("properties"), tool.getName());
			assertEquals(Boolean.FALSE, schema.get("additionalProperties"), tool.getName());
		}
	}

	@Test
	void everyRequiredArgumentIsDeclared() {
		for (IMcpTool tool : TOOLS) {
			Map<?, ?> schema = schemaOf(tool);
			Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
			List<?> required = schema.get("required") instanceof List<?> declared ? declared : List.of();
			for (Object argument : required) {
				assertTrue(properties.containsKey(argument), "%s declares '%s' required but does not describe it"
						.formatted(tool.getName(), argument));
			}
		}
	}

	/** Every tool works on an open dump, so every tool has to let the caller say which one. */
	@Test
	void everyToolTakesADump() {
		for (IMcpTool tool : TOOLS) {
			Map<?, ?> properties = (Map<?, ?>) schemaOf(tool).get("properties");
			assertTrue(properties.containsKey("dump"), tool.getName());
		}
	}

	/**
	 * Both reading tools put their result in front of the person at the IDE by default.
	 * <p>
	 * The point of running through the API was never to keep the answer to ourselves: an
	 * agent that leaves no pane behind leaves nobody anything to carry on from.
	 */
	@Test
	void theReadingToolsShowTheirResultByDefault() {
		Map<?, ?> query = (Map<?, ?>) ((Map<?, ?>) schemaOf(new MatQueryTool()).get("properties")).get("show");
		Map<?, ?> object = (Map<?, ?>) ((Map<?, ?>) schemaOf(new MatObjectTool()).get("properties")).get("show");
		assertEquals(Boolean.TRUE, query.get("default"));
		assertEquals(Boolean.TRUE, object.get("default"));
	}

	/** Ordering is the first thing anybody asks of a histogram. */
	@Test
	void aQueryCanBeOrdered() {
		Map<?, ?> properties = (Map<?, ?>) schemaOf(new MatQueryTool()).get("properties");
		assertTrue(properties.containsKey("sortBy"));
		assertTrue(properties.containsKey("desc"));
	}

	/** The description is the only place the model learns what a tool costs and refuses to do. */
	@Test
	void everyToolExplainsItself() {
		for (IMcpTool tool : TOOLS) {
			assertNotNull(tool.getDescription(), tool.getName());
			assertTrue(tool.getDescription().length() > 200, tool.getName());
		}
	}

	/** The one tool that writes anything says so where the model reads. */
	@Test
	void theWritingToolAnnouncesIt() {
		assertTrue(new MatExtractTool().getDescription().startsWith("WRITES A FILE"));
	}

	private static Map<?, ?> schemaOf(IMcpTool tool) {
		return assertInstanceOf(Map.class, Json.parse(tool.getInputSchema()), tool.getName());
	}
}
