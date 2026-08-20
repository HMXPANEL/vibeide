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

package com.hmx.webide.models

import androidx.annotation.DrawableRes
import com.blankj.utilcode.util.ImageUtils
import com.hmx.webide.resources.R
import java.io.File

/**
 * Info about file extensions in the file tree view.
 *
 * @author Akash Yadav
 */
enum class FileExtension(val extension: String, @DrawableRes val icon: Int) {
  HTML("html", R.drawable.ic_file_txt),
  HTM("htm", R.drawable.ic_file_txt),
  CSS("css", R.drawable.ic_file_txt),
  JS("js", R.drawable.ic_file_txt),
  MJS("mjs", R.drawable.ic_file_txt),
  TS("ts", R.drawable.ic_file_txt),
  MD("md", R.drawable.ic_file_txt),
  JSON("json", R.drawable.ic_language_json),
  TXT("txt", R.drawable.ic_file_txt),
  DIRECTORY("", R.drawable.ic_folder),
  IMAGE("", R.drawable.ic_file_image),
  UNKNOWN("", R.drawable.ic_file_unknown);

  /** Factory class for getting [FileExtension] instances. */
  class Factory {
    companion object {

      /** Get [FileExtension] for the given file. */
      @JvmStatic
      fun forFile(file: File?): FileExtension {
        return if (file?.isDirectory == true) DIRECTORY
          else if (ImageUtils.isImage(file)) IMAGE
          else forExtension(file?.extension)
      }

      /** Get [FileExtension] for the given extension. */
      @JvmStatic
      fun forExtension(extension: String?): FileExtension {
        // To not assign IMAGE, GRADLEW and DIRECTORY in case of an empty extension,
        // we check if an extension is empty here
        if (extension.isNullOrEmpty()) {
          return UNKNOWN
        }
        
        for (value in entries) {
          if (value.extension == extension) {
            return value
          }
        }

        return UNKNOWN
      }
    }
  }
}
