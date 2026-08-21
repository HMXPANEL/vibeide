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
package com.hmx.webide.utils;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.FileUtils;

import java.io.File;

@SuppressLint("SdCardPath")
public final class Environment {

  public static final String PROJECTS_FOLDER = "HMX WEB IDE";
  public static File ROOT;
  public static File HOME;
  public static File ANDROIDIDE_HOME;
  public static File ANDROIDIDE_UI;
  public static File PROJECTS_DIR;

  public static void init(Context context) {
    ROOT = context.getFilesDir();
    HOME = mkdirIfNotExits(new File(ROOT, "home"));
    ANDROIDIDE_HOME = mkdirIfNotExits(new File(HOME, ".androidide"));
    ANDROIDIDE_UI = mkdirIfNotExits(new File(ANDROIDIDE_HOME, "ui"));
    PROJECTS_DIR = mkdirIfNotExits(new File(FileUtil.getExternalStorageDir(), PROJECTS_FOLDER));

    System.setProperty("user.home", HOME.getAbsolutePath());
  }

  public static File mkdirIfNotExits(File in) {
    if (in != null && !in.exists()) {
      FileUtils.createOrExistsDir(in);
    }

    return in;
  }

  public static File getProjectCacheDir(File projectDir) {
    return new File(projectDir, ".androidide");
  }
}