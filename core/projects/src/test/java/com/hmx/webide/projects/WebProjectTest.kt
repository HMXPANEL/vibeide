package com.hmx.webide.projects

import com.google.common.truth.Truth.assertThat
import com.hmx.webide.projects.internal.ProjectManagerImpl
import com.hmx.webide.projects.internal.WorkspaceImpl
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WebProjectTest {

  @get:Rule
  val tmp = TemporaryFolder()

  // --- WebProject: create/open/root/entry/metadata ---

  @Test
  fun `project exposes name description path and projectDir`() {
    val dir = tmp.newFolder("site")
    val project = WebProject(
      name = "my site",
      description = "A static site",
      path = ":",
      projectDir = dir,
    )

    assertThat(project.name).isEqualTo("my site")
    assertThat(project.description).isEqualTo("A static site")
    assertThat(project.path).isEqualTo(":")
    assertThat(project.projectDir).isEqualTo(dir)
  }

  @Test
  fun `project tracks the active file metadata`() {
    val project = WebProject("site", "desc", ":", tmp.newFolder("site"))
    assertThat(project.activeFile).isNull()

    project.activeFile = "index.html"
    assertThat(project.activeFile).isEqualTo("index.html")
  }

  @Test
  fun `open project configures a workspace rooted at the directory`() {
    val dir = tmp.newFolder("site")
    val manager = ProjectManagerImpl()

    manager.openProject(dir)
    val workspace = manager.getWorkspace()
    assertThat(workspace).isNotNull()
    assertThat(workspace!!.getProjectDir()).isEqualTo(dir.canonicalFile)
    assertThat(workspace.getRootProject().projectDir).isEqualTo(dir.canonicalFile)
    assertThat(workspace.getRootProject().path).isEqualTo(":")
    assertThat(manager.projectDir).isEqualTo(dir.canonicalFile)

    manager.destroy()
    assertThat(manager.getWorkspace()).isNull()
  }

  // --- Workspace: get/getProjects/active/close ---

  @Test
  fun `workspace returns root project and subprojects`() {
    val dir = tmp.newFolder("site")
    val root = WebProject("root", "desc", ":", dir)
    val sub = WebProject("sub", "desc", ":sub", File(dir, "sub"))
    val workspace = WorkspaceImpl(dir, root, listOf(root, sub))

    assertThat(workspace.getProjectDir()).isEqualTo(dir)
    assertThat(workspace.getRootProject()).isEqualTo(root)
    assertThat(workspace.getSubProjects()).containsExactly(root, sub)
    assertThat(workspace.findProject(":sub")).isEqualTo(sub)
    assertThat(workspace.findProject(":missing")).isNull()
    assertThat(workspace.getProject(":sub")).isEqualTo(sub)
  }

  @Test
  fun `getProject throws when project path is unknown`() {
    val dir = tmp.newFolder("site")
    val root = WebProject("root", "desc", ":", dir)
    val workspace = WorkspaceImpl(dir, root, listOf(root))

    val ex = assertThrows(IWorkspace.ProjectNotFoundException::class.java) {
      workspace.getProject(":nope")
    }
    assertThat(ex).hasMessageThat().contains(":nope")
  }

  private fun <T : Throwable> assertThrows(type: Class<T>, body: () -> Unit): T {
    return try {
      body()
      throw AssertionError("Expected ${type.simpleName} but no exception was thrown")
    } catch (e: Throwable) {
      if (type.isInstance(e)) type.cast(e) else throw e
    }
  }
}