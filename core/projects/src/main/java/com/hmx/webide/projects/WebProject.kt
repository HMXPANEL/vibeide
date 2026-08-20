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

package com.hmx.webide.projects

import java.io.File

/**
 * A web project model. A VibeIDE project is simply a directory of web files
 * (HTML, CSS, JavaScript, JSON, Markdown, assets). There is no build system,
 * no modules, no classpaths and no variants.
 *
 * @param name The display name of the project.
 * @param description The project description.
 * @param path The project path. The root project is always represented by path `:`.
 * @param projectDir The project directory.
 * @author Akash Yadav
 */
open class WebProject(
  val name: String,
  val description: String,
  val path: String,
  val projectDir: File
) {

  /**
   * The path of the file that is currently active in the editor, relative to [projectDir].
   */
  var activeFile: String? = null
}