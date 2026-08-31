package com.hmx.webide.ai

import com.hmx.webide.ai.models.ToolCall
import com.hmx.webide.ai.tools.ProjectFileOps
import com.hmx.webide.ai.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase3TerminalRuntimeTest {

  private fun tempProject(): File {
    val dir = File.createTempFile("vibeide-test-", "").apply { delete(); mkdirs() }
    File(dir, "index.html").writeText("<h1>hi</h1>")
    File(dir, "package.json").writeText("{}")
    return dir
  }

  // ── ToolRegistry ──────────────────────────────────────────────────

  @Test fun registryIncludesRunCommand() {
    val def = ToolRegistry.get("run_command")
    assertNotNull(def)
    assertEquals("run_command", def!!.name)
    assertEquals(1, def.parameters.size)
    assertEquals("command", def.parameters[0].name)
    assertTrue(def.parameters[0].required)
  }

  @Test fun runCommandRequiresCommandArg() {
    val def = ToolRegistry.get("run_command")!!
    val err = ToolRegistry.validateArguments(def, org.json.JSONObject("{}"))
    assertNotNull(err)
    assertEquals("INVALID_ARGUMENT", err!!.code)
  }

  @Test fun runCommandWithValidArgsPassesValidation() {
    val def = ToolRegistry.get("run_command")!!
    val err = ToolRegistry.validateArguments(def, org.json.JSONObject("""{"command":"echo hello"}"""))
    assertNull(err)
  }

  private fun assertNull(x: Any?) { org.junit.Assert.assertNull(x) }

  // ── ProjectFileOps dispatch ───────────────────────────────────────

  @Test fun runCommandDispatches() {
    val ops = ProjectFileOps(tempProject())
    val r = ops.dispatch(ToolCall(name = "run_command", arguments = """{"command":"echo hello"}"""))
    // May fail if RuntimeManager not initialized, but should NOT be UNKNOWN_TOOL or INTERNAL
    assertFalse("Should not be UNKNOWN_TOOL", r.code == "UNKNOWN_TOOL")
    assertFalse("Should not be INTERNAL", r.code == "INTERNAL")
  }

  @Test fun runCommandOnInvalidDirFails() {
    val dir = File.createTempFile("vibeide-test-", "").apply { delete() } // not a directory
    val ops = ProjectFileOps(dir)
    val r = ops.dispatch(ToolCall(name = "run_command", arguments = """{"command":"echo hi"}"""))
    assertTrue(r.isError)
    assertEquals("INVALID_PROJECT", r.code)
  }

  // ── ToolRegistry schema completeness ──────────────────────────────

  @Test fun allFiveToolsArePresent() {
    val names = ToolRegistry.tools().map { it.name }.toSet()
    assertEquals(setOf("read_file", "write_file", "list_files", "delete_file", "run_command"), names)
  }

  @Test fun runCommandIsMarkedDangerous() {
    val def = ToolRegistry.get("run_command")!!
    assertTrue(def.dangerous)
  }

  @Test fun runCommandIsNotReadOnly() {
    val def = ToolRegistry.get("run_command")!!
    assertFalse(def.readOnly)
  }
}
