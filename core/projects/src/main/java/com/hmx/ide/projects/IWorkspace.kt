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

package com.hmx.ide.projects

import java.io.File

/**
 * Workspace represents everything related to the web project opened in the IDE.
 *
 * @author Akash Yadav
 */
interface IWorkspace {

  /**
   * Get the project directory for the workspace. This is usually the root project directory.
   */
  fun getProjectDir(): File

  /**
   * Get the root project model.
   */
  fun getRootProject(): WebProject

  /**
   * Get the projects included in the root project.
   */
  fun getSubProjects(): List<WebProject>

  /**
   * Finds the project by the given path.
   *
   * @return The project with the given path or `null` if no project is available with that path.
   */
  fun findProject(path: String): WebProject?

  /**
   * Get the project with the given project path.
   *
   * @param path The project path.
   * @return The project with the given path.
   * @throws ProjectNotFoundException If the project could not be found.
   */
  fun getProject(path: String): WebProject =
    findProject(path) ?: throw ProjectNotFoundException(path)

  /**
   * Thrown by [IWorkspace] if the project with a given path could not be found in the workspace.
   */
  class ProjectNotFoundException(path: String) :
    RuntimeException("Could not find project with path: $path")

  /**
   * Thrown by [IProjectManager] when trying to access the workspace and it is not configured yet.
   */
  class NotConfiguredException() : RuntimeException("Workspace not configured")
}