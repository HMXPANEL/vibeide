package com.hmx.webide.actions.editor

import android.content.Context
import android.content.Intent
import com.hmx.webide.R
import com.hmx.webide.actions.ActionData
import com.hmx.webide.actions.EditorActivityAction
import com.hmx.webide.activities.aichat.AIChatActivity

class AIChatAction(context: Context, override val order: Int) : EditorActivityAction() {

  init {
    label = context.getString(R.string.title_ai_chat)
  }

  override val id: String = "ide.editor.aiChat"

  override suspend fun execAction(data: ActionData): Any {
    val activity = data.requireActivity()
    val currentFile = activity.getCurrentEditor()?.editor?.file?.absolutePath
    val intent = Intent(activity, AIChatActivity::class.java)
    if (currentFile != null) {
      intent.putExtra(AIChatActivity.EXTRA_CURRENT_FILE, currentFile)
    }
    activity.startActivity(intent)
    return true
  }
}
