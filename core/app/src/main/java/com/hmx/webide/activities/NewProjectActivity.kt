package com.hmx.webide.activities

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.graphics.Insets
import androidx.lifecycle.lifecycleScope
import com.hmx.webide.activities.aichat.AIChatActivity
import com.hmx.webide.app.EdgeToEdgeIDEActivity
import com.hmx.webide.databinding.FragmentMainBinding
import com.hmx.webide.fragments.HomeActionsSheet
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.projects.IProjectManager
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.Environment
import com.hmx.webide.utils.flashError
import com.hmx.webide.web.WebProjectTemplates
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Create-new-project entry: Home-style AI prompt. No name/template dialogs; the project is
 * derived from the first prompt and the chat workspace opens immediately.
 */
class NewProjectActivity : EdgeToEdgeIDEActivity() {

  private var _binding: FragmentMainBinding? = null
  private val binding get() = checkNotNull(_binding)

  private var mode = "build"

  override fun bindLayout(): View {
    _binding = FragmentMainBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    binding.root.setPadding(insets.left, insets.top, insets.right, insets.bottom)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding.menuButton.contentDescription = getString(string.home_open_menu)
    binding.menuButton.setOnClickListener { finish() }

    binding.plusButton.contentDescription = getString(string.home_actions_title)
    binding.plusButton.setOnClickListener {
      HomeActionsSheet().show(supportFragmentManager, HomeActionsSheet.TAG)
    }

    binding.modeSwitch.setOnClickListener { showModeMenu() }

    binding.sendButton.contentDescription = getString(string.title_ai_chat_send)
    binding.sendButton.setOnClickListener { createAndOpenChat() }

    updateModeUi()
  }

  private fun showModeMenu() {
    val items = arrayOf(getString(string.home_build), getString(string.home_plan))
    AlertDialog.Builder(this)
      .setSingleChoiceItems(items, if (mode == "plan") 1 else 0) { dialog, which ->
        dialog.dismiss()
        mode = if (which == 1) "plan" else "build"
        updateModeUi()
      }
      .show()
  }

  private fun updateModeUi() {
    val heading = if (mode == "plan") string.home_plan_heading else string.home_welcome
    val hint = if (mode == "plan") string.home_hint_plan else string.home_hint_build
    binding.headingText.setText(heading)
    binding.messageInput.setHint(hint)
    binding.modeSwitch.setText(if (mode == "plan") string.home_plan else string.home_build)
  }

  /** Slug from the first words of the prompt; unique-ified against existing dirs. */
  private fun deriveProjectName(prompt: String): String {
    val base = prompt.lowercase()
      .split(Regex("[^a-z0-9]+"))
      .filter { it.isNotBlank() }
      .take(4)
      .joinToString("-")
      .take(24)
      .ifBlank { "project" }
    var name = base
    var n = 2
    while (File(Environment.PROJECTS_DIR, name).exists()) {
      name = "$base-${n++}"
    }
    return name
  }

  private fun createAndOpenChat() {
    val prompt = binding.messageInput.text?.toString()?.trim().orEmpty()
    if (prompt.isBlank()) {
      flashError(getString(string.msg_empty_search_query))
      return
    }
    binding.sendButton.isEnabled = false

    lifecycleScope.launch {
      val dir = File(Environment.PROJECTS_DIR, deriveProjectName(prompt))
      val created =
        withContext(Dispatchers.IO) {
          runCatching {
            dir.mkdirs()
            WebProjectTemplates.all.first().files(dir.name).forEach { (path, content) ->
              val file = File(dir, path)
              file.parentFile?.mkdirs()
              file.writeText(content)
            }
          }.isSuccess
        }
      if (!created) {
        flashError(getString(string.msg_project_create_failed))
        binding.sendButton.isEnabled = true
        return@launch
      }

      GeneralPreferences.addRecentProject(dir.absolutePath)
      IProjectManager.getInstance().openProject(dir)

      startActivity(
        Intent(this@NewProjectActivity, AIChatActivity::class.java).apply {
          putExtra(AIChatActivity.EXTRA_INITIAL_MESSAGE, prompt)
          putExtra(AIChatActivity.EXTRA_MODE, mode)
        })
      finish()
    }
  }
}
