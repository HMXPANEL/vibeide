/*
 *  This file is part of HMX IDE.
 *
 *  HMX IDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  HMX IDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with HMX IDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hmx.ide.utils

import java.io.File

/**
 * A VibeIDE project is any directory of web files (HTML, CSS, JavaScript, assets).
 * There is no build system to validate, so any existing directory is a project.
 */
object ProjectValidator {

  fun isSupportedProject(dir: File?): Boolean {
    return dir != null && dir.isDirectory
  }
}