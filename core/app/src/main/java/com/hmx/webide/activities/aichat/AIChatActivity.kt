package com.hmx.webide.activities.aichat

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.hmx.webide.ai.AiFactory
import com.hmx.webide.ai.context.ContextCache
import com.hmx.webide.ai.context.PromptBuilder
import com.hmx.webide.ai.engine.ChatEngine
import com.hmx.webide.app.BaseIDEActivity
import com.hmx.webide.databinding.ActivityAiChatBinding
import com.hmx.webide.fragments.HomeActionsSheet
import com.hmx.webide.projects.IProjectManager
import com.hmx.webide.R
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.DialogUtils
import com.hmx.webide.utils.flashError
import com.hmx.webide.utils.flashSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AIChatActivity : BaseIDEActivity() {

  companion object {
    const val EXTRA_CURRENT_FILE = "current_file"
    const val EXTRA_INITIAL_MESSAGE = "initial_message"
    const val EXTRA_MODE = "mode"
  }

  private lateinit var binding: ActivityAiChatBinding
  private val adapter = AIChatAdapter()
  private val scope = CoroutineScope(Dispatchers.Main)

  private var projectDir: File? = null
  private var currentFile: String? = null
  private var pendingEdits = linkedMapOf<String, String>()

  private var mode = "build"

  private val chatEngine by lazy { ChatEngine(AiFactory.engine()) }

  /**
   * System prompt derived from the project index. Built silently in the background; never
   * surfaced as chat metadata.
   */
  private var systemPrompt: String? = null

  override fun bindLayout(): View {
    binding = ActivityAiChatBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    projectDir = runCatching { IProjectManager.getInstance().projectDir }.getOrNull()
    currentFile = intent.getStringExtra(EXTRA_CURRENT_FILE)
    if (intent.getStringExtra(EXTRA_MODE) == "plan") {
      mode = "plan"
    }

    binding.messages.adapter = adapter
    binding.messages.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }

    binding.toolbar.setNavigationOnClickListener { finish() }
    binding.toolbar.setOnMenuItemClickListener { item ->
      if (item.itemId == R.id.action_config) {
        showConfigDialog()
        true
      } else false
    }

    binding.plusButton.setOnClickListener {
      HomeActionsSheet().show(supportFragmentManager, HomeActionsSheet.TAG)
    }

    binding.modeSwitch.setOnClickListener { showModeMenu() }
    binding.sendButton.setOnClickListener { sendMessage() }
    updateModeUi()

    val initialMessage = intent.getStringExtra(EXTRA_INITIAL_MESSAGE)
    if (!initialMessage.isNullOrBlank()) {
      binding.messageInput.setText(initialMessage)
    }

    if (projectDir != null) {
      startProjectContext(projectDir!!)
    } else {
      adapter.add(ChatMessage("assistant",
        getString(string.msg_ai_chat_project_required)))
    }
  }

  /** Compact Build/Plan switcher anchored to the selector button. */
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
    binding.modeSwitch.setText(if (mode == "plan") string.home_plan else string.home_build)
  }

  /**
   * Resolves the system prompt from cached index data. A full scan is started only when neither
   * [ContextCache] nor the knowledge engine hold an index; nothing is rendered meanwhile.
   */
  private fun startProjectContext(root: File) {
    val path = root.absolutePath
    val currentFileRel = currentFile?.let { f ->
      runCatching { File(f).toRelativeString(root) }.getOrDefault(f)
    }

    val cached = ContextCache.getSummary(path)
    scope.launch {
      val index = withContext(Dispatchers.IO) {
        if (cached.hasContext) {
          ContextCache.getOrAnalyze(path)
        } else {
          ContextCache.getOrAnalyze(path) { /* silent */ }
        }
      }
      systemPrompt = PromptBuilder.build(index, currentFileRel ?: currentFile)
    }
  }

  private fun sendMessage() {
    val text = binding.messageInput.text?.toString()?.trim().orEmpty()
    if (text.isBlank()) return
    binding.messageInput.text?.clear()

    val isAnalysis = text.lowercase().startsWith("analyze")
    adapter.add(ChatMessage("user", text))

    if (isAnalysis && projectDir != null) {
      scope.launch {
        adapter.add(ChatMessage("assistant", "…"))
        binding.sendButton.isEnabled = false
        val analysis = withContext(Dispatchers.IO) {
          val idx = ContextCache.getOrAnalyze(projectDir!!.absolutePath)
          PromptBuilder.buildAnalysis(idx)
        }
        adapter.setLastContent(analysis)
        binding.sendButton.isEnabled = true
      }
      return
    }

    adapter.add(ChatMessage("assistant", "…"))
    binding.sendButton.isEnabled = false

    scope.launch(Dispatchers.Main) {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          val engine = AiFactory.engine()
          val providerId = engine.activeProvider().providerId
          val model = AiFactory.storage().getModel(providerId)
          // Provider-agnostic: the same request shape is used for every provider; only the
          // optional system prompt varies.
          val response = chatEngine.send(model, text, systemPrompt)
          response.message.content
        }
      }
      binding.sendButton.isEnabled = true
      result.onSuccess { content ->
        adapter.setLastContent(content)
        collectEdits(content)
      }.onFailure { err ->
        adapter.setLastContent("⚠ ${err.message}")
        flashError(getString(string.msg_ai_chat_error, err.message))
      }
    }
  }

  private fun collectEdits(content: String) {
    pendingEdits.clear()
    val regex = Regex("\\[\\[WRITE:(.+?)\\]\\](.*?)\\[\\[END\\]\\]", RegexOption.DOT_MATCHES_ALL)
    regex.findAll(content).forEach {
      pendingEdits[it.groupValues[1].trim()] = it.groupValues[2].trim('\n', '\r')
    }
    binding.applyChanges.visibility = if (pendingEdits.isNotEmpty()) View.VISIBLE else View.GONE
    binding.applyChanges.setOnClickListener { applyEdits() }
  }

  private fun applyEdits() {
    val root = projectDir ?: return
    val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return
    var count = 0
    pendingEdits.forEach { (rel, content) ->
      runCatching {
        val file = File(root, rel)
        val canonicalFile = file.canonicalPath
        if (!canonicalFile.startsWith(canonicalRoot + File.separator) && canonicalFile != canonicalRoot) {
          return@forEach
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        count++
      }
    }
    pendingEdits.clear()
    binding.applyChanges.visibility = View.GONE
    flashSuccess(getString(string.msg_ai_chat_applied, count))
  }

  private fun showConfigDialog() {
    val bindingInput = com.hmx.webide.preferences.databinding.LayoutDialogTextInputBinding.inflate(
      layoutInflater)
    val builder = DialogUtils.newMaterialDialogBuilder(this)
    builder.setTitle(string.title_ai_chat_config)
    val provider = AiFactory.engine().activeProvider()
    val model = AiFactory.storage().getModel(provider.providerId).ifBlank { "(not configured)" }
    builder.setMessage("Provider: ${provider.displayName}\n\nModel: $model\n\nChange model:")
    builder.setView(bindingInput.root)
    builder.setPositiveButton(android.R.string.ok) { _, _ ->
      val input = bindingInput.name.editText?.text?.toString()?.trim()
      if (!input.isNullOrBlank()) {
        AiFactory.storage().setModel(provider.providerId, input)
      }
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }
}
