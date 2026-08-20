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

package com.hmx.webide.activities

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.Insets
import com.hmx.webide.activities.editor.EditorActivityKt
import com.hmx.webide.app.EdgeToEdgeIDEActivity
import com.hmx.webide.databinding.ActivityMainBinding
import androidx.lifecycle.lifecycleScope
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.projects.IProjectManager
import com.hmx.webide.knowledge.KnowledgeEngineImpl
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.DialogUtils
import com.hmx.webide.utils.flashInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivityMainBinding? = null

  private val binding: ActivityMainBinding
    get() = checkNotNull(_binding)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    openLastProject()
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    binding.fragmentContainersParent.setPadding(
      insets.left,
      0,
      insets.right,
      insets.bottom
    )
  }

  override fun bindLayout(): View {
    _binding = ActivityMainBinding.inflate(layoutInflater)
    return binding.root
  }

  private fun openLastProject() {
    binding.root.post { tryOpenLastProject() }
  }

  private fun tryOpenLastProject() {
    if (!GeneralPreferences.autoOpenProjects) {
      return
    }

    val openedProject = GeneralPreferences.lastOpenedProject
    if (GeneralPreferences.NO_OPENED_PROJECT == openedProject) {
      return
    }

    if (TextUtils.isEmpty(openedProject)) {
      app
      flashInfo(string.msg_opened_project_does_not_exist)
      return
    }

    val project = File(openedProject)
    if (!project.exists()) {
      flashInfo(string.msg_opened_project_does_not_exist)
      return
    }

    if (GeneralPreferences.confirmProjectOpen) {
      askProjectOpenPermission(project)
      return
    }

    openProject(project)
  }

  private fun askProjectOpenPermission(root: File) {
    val builder = DialogUtils.newMaterialDialogBuilder(this)
    builder.setTitle(string.title_confirm_open_project)
    builder.setMessage(getString(string.msg_confirm_open_project, root.absolutePath))
    builder.setCancelable(false)
    builder.setPositiveButton(string.yes) { _, _ -> openProject(root) }
    builder.setNegativeButton(string.no, null)
    builder.show()
  }

  internal fun openProject(root: File) {
    IProjectManager.getInstance().openProject(root)
    lifecycleScope.launch(Dispatchers.IO) {
      KnowledgeEngineImpl.refresh(root)
      withContext(Dispatchers.Main) {
        startActivity(Intent(this@MainActivity, EditorActivityKt::class.java))
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }
}