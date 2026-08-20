/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hmx.webide.projects.internal

import com.google.auto.service.AutoService
import com.hmx.webide.eventbus.events.EventReceiver
import com.hmx.webide.eventbus.events.file.FileCreationEvent
import com.hmx.webide.eventbus.events.file.FileDeletionEvent
import com.hmx.webide.eventbus.events.file.FileRenameEvent
import com.hmx.webide.eventbus.events.project.ProjectInitializedEvent
import com.hmx.webide.projects.IProjectManager
import com.hmx.webide.projects.IWorkspace
import com.hmx.webide.projects.WebProject
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Internal implementation of [IProjectManager].
 *
 * A VibeIDE project is a plain directory of web files; opening it simply sets up
 * a workspace around that directory.
 *
 * @author Akash Yadav
 */
@AutoService(IProjectManager::class)
class ProjectManagerImpl : IProjectManager, EventReceiver {

  private var _workspace: WorkspaceImpl? = null
  private var _projectDir: File? = null

  override val projectDir: File
    get() = checkNotNull(_projectDir) {
      "Cannot get project directory. Path has not been set."
    }

  override fun getWorkspace(): IWorkspace? {
    return _workspace
  }

  override fun openProject(directory: File) {
    // IMP: Always use canonical path
    this._projectDir = directory.canonicalFile

    val project = WebProject(
      name = projectDir.name,
      description = "Web project",
      path = ":",
      projectDir = projectDir
    )

    this._workspace = WorkspaceImpl(projectDir, project, listOf(project))

    val workspace = getWorkspace() ?: return

    val event = ProjectInitializedEvent()
    event.put(IWorkspace::class.java, workspace)
    EventBus.getDefault().post(event)
  }

  override fun destroy() {
    log.info("Destroying project manager")

    this._workspace = null
    this._projectDir = null
  }

  override fun notifyFileCreated(file: File) {
    onFileCreated(FileCreationEvent(file))
  }

  override fun notifyFileDeleted(file: File) {
    onFileDeleted(FileDeletionEvent(file))
  }

  override fun notifyFileRenamed(from: File, to: File) {
    onFileRenamed(FileRenameEvent(from, to))
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileCreated(event: FileCreationEvent) {
    // no project-wide indexing for web projects
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileDeleted(event: FileDeletionEvent) {
    // no project-wide indexing for web projects
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileRenamed(event: FileRenameEvent) {
    // no project-wide indexing for web projects
  }

  companion object {
    private val log = LoggerFactory.getLogger(ProjectManagerImpl::class.java)

    @JvmStatic
    fun getInstance(): ProjectManagerImpl = IProjectManager.getInstance() as ProjectManagerImpl
  }
}