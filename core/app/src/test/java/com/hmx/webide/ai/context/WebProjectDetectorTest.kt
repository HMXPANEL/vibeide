package com.hmx.webide.ai.context

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebProjectDetectorTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun project(vararg files: Pair<String, String>): java.io.File {
    val dir = tmp.newFolder("proj")
    for ((name, content) in files) {
      val f = java.io.File(dir, name)
      f.parentFile?.mkdirs()
      f.writeText(content)
    }
    return dir
  }

  @Test
  fun `index.html alone is a static html project`() {
    val dir = project("index.html" to "<h1>hi</h1>")
    assertThat(WebProjectDetector.detect(dir)).isEqualTo(WebProjectType.STATIC_HTML)
  }

  @Test
  fun `no index html is not a web project`() {
    val dir = tmp.newFolder("empty")
    assertThat(WebProjectDetector.detect(dir)).isEqualTo(WebProjectType.UNKNOWN_WEB_PROJECT)
  }

  @Test
  fun `package json with scripts is a node project`() {
    val dir = project(
      "index.html" to "<h1>hi</h1>",
      "package.json" to """{"scripts":{"build":"node build.js"}}""",
    )
    assertThat(WebProjectDetector.detect(dir)).isEqualTo(WebProjectType.NODE_PROJECT)
  }

  @Test
  fun `package json with vite is a vite project`() {
    val dir = project(
      "index.html" to "<h1>hi</h1>",
      "package.json" to """{"devDependencies":{"vite":"^5.0.0"}}""",
    )
    assertThat(WebProjectDetector.detect(dir)).isEqualTo(WebProjectType.VITE_PROJECT)
  }

  @Test
  fun `package json with react is a react project`() {
    val dir = project(
      "index.html" to "<h1>hi</h1>",
      "package.json" to """{"dependencies":{"react":"^18.0.0","react-dom":"^18.0.0"}}""",
    )
    assertThat(WebProjectDetector.detect(dir)).isEqualTo(WebProjectType.REACT_PROJECT)
  }

  @Test
  fun `read package json parses dependency sections`() {
    val dir = project(
      "package.json" to """{"dependencies":{"react":"^18"},"devDependencies":{"vite":"^5"}}""",
    )
    val deps = WebProjectDetector.readPackageJson(dir)
    assertThat(deps).isNotNull()
    assertThat(deps!!["react"]).isEqualTo("^18")
    assertThat(deps["vite"]).isEqualTo("^5")
  }
}