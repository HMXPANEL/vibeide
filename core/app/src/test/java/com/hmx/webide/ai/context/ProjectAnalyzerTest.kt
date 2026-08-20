package com.hmx.webide.ai.context

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectAnalyzerTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun webProject(vararg files: Pair<String, String>): File {
    val dir = tmp.newFolder("site")
    for ((name, content) in files) {
      val f = File(dir, name)
      f.parentFile?.mkdirs()
      f.writeText(content)
    }
    return dir
  }

  @Test
  fun `scan and analyze produce web oriented context`() {
    val dir = webProject(
      "index.html" to "<h1>hello</h1><script src=\"app.js\"></script>",
      "style.css" to ".hero { color: red; }",
      "app.js" to "function greet() {}\nimport { x } from \"./mod.js\";",
      "package.json" to """{"name":"site","scripts":{"serve":"npx serve"}}""",
    )

    val scan = ProjectScanner.scan(dir)
    val index = ProjectAnalyzer.analyze(dir, scan)

    assertThat(index.context.projectType).isEqualTo(WebProjectType.NODE_PROJECT)
    assertThat(index.context.languages).containsExactly(
      WebLanguage.HTML,
      WebLanguage.CSS,
      WebLanguage.JAVASCRIPT,
      WebLanguage.JSON,
    )
    assertThat(index.context.entryFile).isEqualTo("index.html")
    assertThat(index.context.hasPackageJson).isTrue()
    assertThat(index.context.fileCount).isEqualTo(4)
    assertThat(index.totalFiles).isAtLeast(4)
  }

  @Test
  fun `scanner collects js imports and declarations`() {
    val dir = webProject(
      "app.js" to "import { greet } from \"./lib.js\";\nfunction helper() {}\nconst value = 42;",
    )

    val scan = ProjectScanner.scan(dir)
    val info = scan.fileInfos.single { it.relativePath == "app.js" }

    assertThat(info.imports).containsExactly("./lib.js")
    assertThat(info.classes).containsAtLeast("helper", "value")
  }

  @Test
  fun `scan skips node_modules and build dirs`() {
    val dir = webProject("index.html" to "<h1>hi</h1>")
    File(dir, "node_modules/pkg/index.js").also { it.parentFile.mkdirs() }
      .writeText("function x() {}")
    File(dir, "dist/main.js").also { it.parentFile.mkdirs() }
      .writeText("function y() {}")

    val scan = ProjectScanner.scan(dir)

    assertThat(scan.allSourceFiles.map { it.relativeTo(dir).path })
      .doesNotContain("node_modules/pkg/index.js")
    assertThat(scan.allSourceFiles.map { it.relativeTo(dir).path })
      .doesNotContain("dist/main.js")
  }

  @Test
  fun `context cache tracks web entry files for staleness`() {
    val dir = webProject("index.html" to "<h1>one</h1>")
    val path = dir.absolutePath
    ContextCache.invalidate(path)

    val index = ProjectAnalyzer.analyze(dir, ProjectScanner.scan(dir))
    ContextCache.set(path, index)
    assertThat(ContextCache.get(path)).isNotNull()
    assertThat(ContextCache.getContext(path)!!.entryFile).isEqualTo("index.html")

    val entry = File(dir, "index.html")
    entry.writeText("<h1>two</h1>")
    entry.setLastModified(System.currentTimeMillis() + 2000)
    assertThat(ContextCache.get(path)).isNull()

    ContextCache.invalidate(path)
  }
}