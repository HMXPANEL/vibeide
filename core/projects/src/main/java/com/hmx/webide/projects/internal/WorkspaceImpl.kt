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

import com.google.common.collect.ImmutableList
import com.hmx.webide.projects.IWorkspace
import com.hmx.webide.projects.WebProject
import java.io.File

/**
 * Model for representing the whole web project that is opened in the IDE.
 *
 * @property projectDir The project directory.
 * @property rootProject The root web project.
 * @property subProjects List of all projects included in the project.
 */
class WorkspaceImpl(
  private val projectDir: File,
  private val rootProject: WebProject,
  private val subProjects: List<WebProject>
) : IWorkspace {

  override fun getProjectDir(): File {
    return this.projectDir
  }

  override fun getRootProject(): WebProject {
    return this.rootProject
  }

  override fun getSubProjects(): List<WebProject> {
    return ImmutableList.copyOf(this.subProjects)
  }

  override fun findProject(path: String): WebProject? {
    return this.subProjects.find { it.path == path }
  }
}