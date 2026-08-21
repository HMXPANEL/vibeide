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
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.hmx.webide.app.EdgeToEdgeIDEActivity
import com.hmx.webide.activities.editor.EditorActivityKt
import com.hmx.webide.databinding.ActivityMainBinding
import com.hmx.webide.knowledge.KnowledgeEngineImpl
import com.hmx.webide.projects.IProjectManager
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
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    binding.homeContent.setPadding(insets.left, insets.top, insets.right, insets.bottom)
  }

  override fun bindLayout(): View {
    _binding = ActivityMainBinding.inflate(layoutInflater)
    return binding.root
  }

  fun openDrawer() = binding.drawer.openDrawer(GravityCompat.START)

  fun closeDrawer() = binding.drawer.closeDrawer(GravityCompat.START)

  fun isDrawerOpen(): Boolean = binding.drawer.isDrawerOpen(GravityCompat.START)

  internal fun openProject(root: File) {
    IProjectManager.getInstance().openProject(root)
    lifecycleScope.launch(Dispatchers.IO) {
      KnowledgeEngineImpl.refresh(root)
      withContext(Dispatchers.Main) {
        startActivity(Intent(this@MainActivity, EditorActivityKt::class.java))
      }
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (isDrawerOpen()) {
      closeDrawer()
      return
    }
    super.onBackPressed()
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }
}
