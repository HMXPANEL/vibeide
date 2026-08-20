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

package com.hmx.webide.actions.editor

import android.content.Context
import androidx.core.content.ContextCompat
import com.hmx.webide.R
import com.hmx.webide.actions.ActionData
import com.hmx.webide.actions.EditorActivityAction
import com.hmx.webide.activities.PreviewActivity
import com.hmx.webide.projects.IProjectManager

/**
 * Opens the live preview of the current web project.
 *
 * @author Akash Yadav
 */
class PreviewProjectAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.editor.previewProject"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.action_preview)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_website)
  }

  override suspend fun execAction(data: ActionData): Any {
    val activity = data.requireActivity()
    val projectDir = IProjectManager.getInstance().projectDir
    if (projectDir.isDirectory) {
      activity.startActivity(PreviewActivity.newIntent(activity, projectDir))
    }
    return true
  }
}