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

package com.hmx.webide.actions.filetree

import android.content.Context
import android.view.LayoutInflater
import com.blankj.utilcode.util.FileIOUtils
import com.hmx.webide.actions.ActionData
import com.hmx.webide.actions.requireFile
import com.hmx.webide.adapters.viewholders.FileTreeViewHolder
import com.hmx.webide.eventbus.events.file.FileCreationEvent
import com.hmx.webide.preferences.databinding.LayoutDialogTextInputBinding
import com.hmx.webide.resources.R
import com.hmx.webide.utils.DialogUtils
import com.hmx.webide.utils.flashError
import com.hmx.webide.utils.flashSuccess
import com.unnamed.b.atv.model.TreeNode
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory
import java.io.File

/**
 * File tree action to create a new file.
 *
 * @author Akash Yadav
 */
class NewFileAction(context: Context, override val order: Int) :
  BaseDirNodeAction(
    context = context,
    labelRes = R.string.new_file,
    iconRes = R.drawable.ic_new_file
  ) {

  override val id: String = "ide.editor.fileTree.newFile"

  private companion object {
    private val log = LoggerFactory.getLogger(NewFileAction::class.java)
  }

  override suspend fun execAction(data: ActionData) {
    val file = data.requireFile()
    val node = data.getTreeNode()
    try {
      createNewEmptyFile(context, node, file)
    } catch (e: Exception) {
      log.error("Failed to create new file", e)
      flashError(e.cause?.message ?: e.message)
    }
  }

  private fun createNewEmptyFile(context: Context, node: TreeNode?, file: File) {
    createNewFileWithContent(context, node, file, "")
  }

  private fun createNewFileWithContent(
    context: Context,
    node: TreeNode?,
    file: File,
    content: String
  ) {
    val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(context))
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    binding.name.editText!!.setHint(R.string.file_name)
    builder.setTitle(R.string.new_file)
    builder.setMessage(
      context.getString(R.string.msg_can_contain_slashes) +
          "\n\n" +
          context.getString(R.string.msg_newfile_dest, file.absolutePath)
    )
    builder.setView(binding.root)
    builder.setCancelable(false)
    builder.setPositiveButton(R.string.text_create) { dialogInterface, _ ->
      dialogInterface.dismiss()
      val name = binding.name.editText!!.text.toString().trim()
      if (name.isBlank()) {
        flashError(R.string.msg_invalid_name)
        return@setPositiveButton
      }

      try {
        createFile(context, node, file, name, content)
      } catch (e: Exception) {
        log.error("Failed to create file", e)
        flashError(e.cause?.message ?: e.message)
      }
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.create().show()
  }

  private fun createFile(
    context: Context,
    node: TreeNode?,
    directory: File,
    name: String,
    content: String
  ): Boolean {
    if (name.length !in 1..40 || name.startsWith("/")) {
      flashError(R.string.msg_invalid_name)
      return false
    }

    val newFile = File(directory, name)
    if (newFile.exists()) {
      flashError(R.string.msg_file_exists)
      return false
    }
    if (!FileIOUtils.writeFileFromString(newFile, content)) {
      flashError(R.string.msg_file_creation_failed)
      return false
    }

    notifyFileCreated(newFile, context)
    flashSuccess(R.string.msg_file_created)
    if (node != null) {
      val newNode = TreeNode(newFile)
      newNode.viewHolder = FileTreeViewHolder(context)
      node.addChild(newNode)
      requestExpandNode(node)
    } else {
      requestFileListing()
    }

    return true
  }

  private fun notifyFileCreated(file: File, context: Context) {
    EventBus.getDefault().post(FileCreationEvent(file).putData(context))
  }
}