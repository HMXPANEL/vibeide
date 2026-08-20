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

package com.hmx.webide.activities.editor

import android.view.Gravity
import android.app.AlertDialog
import androidx.annotation.GravityInt
import com.hmx.webide.R.string
import com.hmx.webide.databinding.LayoutSearchProjectBinding
import com.hmx.webide.fragments.sheets.ProgressSheet
import com.hmx.webide.lsp.IDELanguageClientImpl
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.projects.internal.ProjectManagerImpl
import com.hmx.webide.utils.DialogUtils.newMaterialDialogBuilder
import com.hmx.webide.utils.RecursiveFileSearcher
import com.hmx.webide.utils.flashError
import java.io.File
import java.util.regex.Pattern

/** @author Akash Yadav */
abstract class ProjectHandlerActivity : BaseEditorActivity() {

  protected var mSearchingProgress: ProgressSheet? = null
  protected var mFindInProjectDialog: AlertDialog? = null

  abstract fun doCloseAll(runAfter: () -> Unit)

  abstract fun saveOpenedFiles()

  override fun doDismissSearchProgress() {
    if (mSearchingProgress?.isShowing == true) {
      mSearchingProgress!!.dismiss()
    }
  }

  override fun doConfirmProjectClose() {
    confirmProjectClose()
  }

  override fun preDestroy() {
    if (isDestroying) {
      closeProject(false)
    }

    if (IDELanguageClientImpl.isInitialized()) {
      IDELanguageClientImpl.shutdown()
    }

    super.preDestroy()
  }

  fun setStatus(status: CharSequence) {
    setStatus(status, Gravity.CENTER)
  }

  fun setStatus(status: CharSequence, @GravityInt gravity: Int) {
    doSetStatus(status, gravity)
  }

  open fun createFindInProjectDialog(): AlertDialog? {
    val manager = ProjectManagerImpl.getInstance()
    if (manager.getWorkspace() == null) {
      log.warn("No root project model found.")
      flashError(getString(string.msg_project_not_initialized))
      return null
    }

    return createFindInProjectDialog(listOf(manager.projectDir))
  }

  protected open fun createFindInProjectDialog(searchDirs: List<File>): AlertDialog? {
    val binding = LayoutSearchProjectBinding.inflate(layoutInflater)

    val builder = newMaterialDialogBuilder(this)
    builder.setTitle(string.menu_find_project)
    builder.setView(binding.root)
    builder.setCancelable(false)
    builder.setPositiveButton(string.menu_find) { dialog, _ ->
      val text = binding.input.editText!!.text.toString().trim()
      if (text.isEmpty()) {
        flashError(string.msg_empty_search_query)
        return@setPositiveButton
      }

      val extensions = binding.filter.editText!!.text.toString().trim()
      val extensionList = mutableListOf<String>()
      if (extensions.isNotEmpty()) {
        if (extensions.contains("|")) {
          for (str in
          extensions
            .split(Pattern.quote("|").toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()) {
            if (str.trim().isEmpty()) {
              continue
            }
            extensionList.add(str)
          }
        } else {
          extensionList.add(extensions)
        }
      }

      dialog.dismiss()

      getProgressSheet(string.msg_searching_project)?.apply {
        show(supportFragmentManager, "search_in_project_progress")
      }

      RecursiveFileSearcher.searchRecursiveAsync(text, extensionList, searchDirs) { results ->
        handleSearchResults(results)
      }
    }

    builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
    mFindInProjectDialog = builder.create()
    return mFindInProjectDialog
  }

  private fun closeProject(manualFinish: Boolean) {
    if (manualFinish) {
      // if the user is manually closing the project,
      // save the opened files cache
      // this is needed because in this case, the opened files cache will be empty
      // when onPause will be called.
      saveOpenedFiles()

      // reset the lastOpenedProject if the user explicitly chose to close the project
      GeneralPreferences.lastOpenedProject = GeneralPreferences.NO_OPENED_PROJECT
    }

    // Make sure we close files
    // This will make sure that file contents are not erased.
    doCloseAll {
      if (manualFinish) {
        finish()
      }
    }
  }

  private fun confirmProjectClose() {
    val builder = newMaterialDialogBuilder(this)
    builder.setTitle(string.title_confirm_project_close)
    builder.setMessage(string.msg_confirm_project_close)
    builder.setNegativeButton(string.no, null)
    builder.setPositiveButton(string.yes) { dialog, _ ->
      dialog.dismiss()
      closeProject(true)
    }
    builder.show()
  }

  open fun getProgressSheet(msg: Int): ProgressSheet? {
    doDismissSearchProgress()

    mSearchingProgress =
      ProgressSheet().also {
        it.isCancelable = false
        it.setMessage(getString(msg))
        it.setSubMessageEnabled(false)
      }

    return mSearchingProgress
  }
}

val android.app.Activity.findInProjectDialog: AlertDialog?
  get() = (this as? ProjectHandlerActivity)?.createFindInProjectDialog()