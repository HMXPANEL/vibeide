package com.hmx.webide.ai

import com.hmx.webide.ai.models.ToolCall
import com.hmx.webide.ai.tools.ProjectFileOps
import com.hmx.webide.ai.tools.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase2ToolSystemTest {

  private fun tempProject(): File {
    val dir = File.createTempFile("vibeide-test", "").apply { delete(); mkdirs() }
    File(dir, "index.html").writeText("<h1>hi</h1>")
    File(dir, "package.json").writeText("{}")
    return dir
  }

  @Test fun registryHasFiveTools() {
    val tools = ToolRegistry.tools()
    assertEquals(5, tools.size)
    assertTrue(tools.any { it.name == "write_file" })
    assertTrue(tools.any { it.name == "read_file" })
    assertTrue(tools.any { it.name == "run_command" })
  }

  @Test fun registryUnknownToolIsAbsent() {
    assertNull(ToolRegistry.get("terminal"))
    assertNull(ToolRegistry.get("git_commit"))
  }

  @Test fun missingRequiredArgumentIsInvalid() {
    val def = ToolRegistry.get("write_file")!!
    val err = ToolRegistry.validateArguments(def, JSONObject("""{"path":"a"}"""))
    assertNotNull(err)
    assertEquals("INVALID_ARGUMENT", err!!.code)
  }

  @Test fun unknownToolDispatchReturnsUnknownTool() {
    val ops = ProjectFileOps(tempProject())
    val r = ops.dispatch(ToolCall(name = "terminal", arguments = "{}"))
    assertTrue(r.isError)
    assertEquals("UNKNOWN_TOOL", r.code)
  }

  @Test fun protectedWriteIsDeniedByGate() {
    val ops = ProjectFileOps(tempProject())
    val r = ops.dispatch(ToolCall(name = "write_file", arguments = """{"path":"package.json","content":"{}"}"""))
    assertTrue(r.isError)
    assertEquals("DENIED", r.code)
  }

  @Test fun normalVibeWriteIsAllowed() {
    val ops = ProjectFileOps(tempProject())
    val r = ops.dispatch(ToolCall(name = "write_file", arguments = """{"path":"style.css","content":"x"}"""))
    assertFalse(r.isError)
    assertEquals(1, ops.changedFiles)
  }

  @Test fun legacyWriteFallbackRoutedThroughGate() {
    val ops = ProjectFileOps(tempProject())
    val r = ops.applyEdit("package.json", "{}")
    assertTrue(r.isError)
    assertEquals("DENIED", r.code)
    val ok = ops.applyEdit("app.js", "console.log(1)")
    assertFalse(ok.isError)
  }

  @Test fun outOfProjectPathDenied() {
    val ops = ProjectFileOps(tempProject())
    val r = ops.dispatch(ToolCall(name = "write_file", arguments = """{"path":"../escape.txt","content":"x"}"""))
    assertTrue(r.isError)
    assertEquals("INVALID_PATH", r.code)
  }
}
