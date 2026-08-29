package com.hmx.webide.activities.aichat

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.hmx.webide.R
import com.hmx.webide.activities.PreviewActivity
import com.hmx.webide.ai.AiFactory
import com.hmx.webide.ai.context.ContextCache
import com.hmx.webide.ai.context.PromptBuilder
import com.hmx.webide.ai.engine.ChatEngine
import com.hmx.webide.ai.engine.retryOnceOnRateLimit
import com.hmx.webide.ai.errors.AiException
import com.hmx.webide.ai.errors.AuthenticationException
import com.hmx.webide.ai.errors.ModelNotFoundException
import com.hmx.webide.ai.errors.NetworkException
import com.hmx.webide.ai.errors.ProviderConfigurationException
import com.hmx.webide.ai.errors.ProviderException
import com.hmx.webide.ai.errors.QuotaException
import com.hmx.webide.ai.errors.RateLimitException
import com.hmx.webide.ai.models.Capability
import com.hmx.webide.ai.models.Tool
import com.hmx.webide.ai.models.ToolParameter
import com.hmx.webide.ai.tools.ProjectFileOps
import com.hmx.webide.app.BaseIDEActivity
import com.hmx.webide.databinding.ActivityAiChatBinding
import com.hmx.webide.fragments.AttachmentListener
import com.hmx.webide.fragments.HomeActionsSheet
import com.hmx.webide.projects.IProjectManager
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.flashError
import com.hmx.webide.utils.flashSuccess
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.SocketTimeoutException

class AIChatActivity : BaseIDEActivity(), AttachmentListener {

  companion object {
    const val EXTRA_CURRENT_FILE = "current_file"
    const val EXTRA_INITIAL_MESSAGE = "initial_message"
    const val EXTRA_MODE = "mode"
    const val EXTRA_PROJECT_DIR = "project_dir"
  }

  private lateinit var binding: ActivityAiChatBinding
  private val adapter = AIChatAdapter()

  /** Project resolved for this chat session; re-checked on every send. */
  private var projectDir: File? = null
  private var currentFile: String? = null
  private var pendingEdits = linkedMapOf<String, String>()
  private val attachments = mutableListOf<Uri>()

  /** Active AI task for this project (null when idle/completed). */
  private var activeTask: ChatTask? = null

  private var mode = "build"

  /** File-operation tools exposed to the AI. The app executes them, not a text parser. */
  private val tools = listOf(
    Tool(
      "read_file",
      "Read a project file by relative path to inspect existing code.",
      listOf(ToolParameter("path", "string", "Relative path, e.g. src/main.js", true)),
    ),
    Tool(
      "write_file",
      "Create or overwrite a project file with the given content. Use this to build or edit the project.",
      listOf(
        ToolParameter("path", "string", "Relative path, e.g. index.html or src/style.css", true),
        ToolParameter("content", "string", "Full new file content", true),
      ),
    ),
    Tool(
      "list_files",
      "List files in a project directory to understand the layout.",
      listOf(ToolParameter("path", "string", "Relative directory path, defaults to project root", false)),
    ),
    Tool(
      "delete_file",
      "Delete a project file when explicitly required.",
      listOf(ToolParameter("path", "string", "Relative path of the file to delete", true)),
    ),
  )

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

    currentFile = intent.getStringExtra(EXTRA_CURRENT_FILE)
    if (intent.getStringExtra(EXTRA_MODE) == "plan") {
      mode = "plan"
    }
    resolveProject()

    binding.messages.adapter = adapter
    binding.messages.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }

    binding.toolbar.setNavigationOnClickListener { finish() }
    binding.toolbar.setOnMenuItemClickListener { item ->
      if (item.itemId == R.id.action_preview) {
        val dir = resolveProject() ?: return@setOnMenuItemClickListener true
        startActivity(PreviewActivity.newIntent(this, dir))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        true
      } else false
    }

    binding.plusButton.setOnClickListener { openAttachSheet() }
    // The chat-input options button ("...") opens the same options as "+".
    binding.moreButton.setOnClickListener { openAttachSheet() }

    binding.modeSwitch.setOnClickListener { showModeMenu() }
    binding.sendButton.setOnClickListener { sendMessage() }
    binding.continueButton.setOnClickListener { continueTask() }
    updateModeUi()

    val initialMessage = intent.getStringExtra(EXTRA_INITIAL_MESSAGE)
    if (!initialMessage.isNullOrBlank()) {
      binding.messageInput.setText(initialMessage)
    }

    restorePersistedHistory()
    startProjectContext()
  }

  /** Loads this project's saved conversation into the screen and the engine context. */
  private fun restorePersistedHistory() {
    val dir = projectDir ?: return
    val history = ChatHistoryStore.load(dir)
    if (history.isNotEmpty()) {
      adapter.addAll(history.map {
        ChatMessage(
          if (it.role == com.hmx.webide.ai.models.Role.user) "user" else "assistant",
          it.content)
      })
      chatEngine.restoreHistory(history)
    }

    // Restore an in-flight / failed task so the user can continue it.
    val task = ChatTaskStore.load(dir)
    if (task != null && task.status != ChatTask.Status.COMPLETED) {
      // A RUNNING/WORKING task with no live coroutine (app was killed) is treated as interrupted.
      val restored = if (task.status == ChatTask.Status.RUNNING || task.status == ChatTask.Status.WORKING) {
        task.copy(status = ChatTask.Status.INTERRUPTED)
      } else task
      activeTask = restored
      ChatTaskStore.save(dir, restored)
      val content = when (restored.status) {
        ChatTask.Status.FAILED ->
          "⚠ ${restored.error ?: getString(string.msg_ai_chat_error, "task failed")}\n\n${getString(string.msg_ai_task_continue)} to retry this task."
        ChatTask.Status.INTERRUPTED ->
          "⏸ ${getString(string.msg_ai_task_interrupted)}"
        else -> restored.partial.ifBlank { getString(string.msg_ai_task_running) }
      }
      adapter.add(ChatMessage("assistant", content))
      if (restored.status == ChatTask.Status.FAILED || restored.status == ChatTask.Status.INTERRUPTED) {
        binding.continueButton.visibility = View.VISIBLE
      }
    }
  }

  private fun persistHistory() {
    projectDir?.let { ChatHistoryStore.save(it, chatEngine.history()) }
  }

  /**
   * Resolves the active project for this chat session.
   *
   * Preference order: explicit [EXTRA_PROJECT_DIR] -> project manager state. If the intent
   * carries a dir but the manager lost it (e.g. after activity recreation), the manager is
   * re-opened so the session stays valid no matter how long the user waits on this screen.
   */
  private fun resolveProject(): File? {
    val fromIntent = intent.getStringExtra(EXTRA_PROJECT_DIR)?.let(::File)
    val fromManager = runCatching { IProjectManager.getInstance().projectDir }.getOrNull()

    if (fromIntent != null) {
      val same =
        fromManager != null &&
          runCatching { fromManager.canonicalPath == fromIntent.canonicalPath }.getOrDefault(false)
      if (!same) {
        IProjectManager.getInstance().openProject(fromIntent)
      }
      projectDir = fromIntent
      return fromIntent
    }

    projectDir = fromManager
    return fromManager
  }

  private fun openAttachSheet() {
    HomeActionsSheet().show(supportFragmentManager, HomeActionsSheet.TAG)
  }

  override fun onAttachmentsSelected(uris: List<Uri>) {
    uris.forEach { uri ->
      if (attachments.none { it.toString() == uri.toString() }) {
        attachments.add(uri)
        addAttachmentView(uri)
      }
    }
  }

  private fun isImage(uri: Uri): Boolean {
    val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
    if (mime?.startsWith("image/") == true) return true
    val ext = uri.toString().substringBefore('?').substringAfterLast('.').lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
  }

  private fun displayName(uri: Uri): String {
    runCatching {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val name = cursor.getString(0)
            if (!name.isNullOrBlank()) return name
          }
        }
    }
    return uri.lastPathSegment ?: "file"
  }

  private fun addAttachmentView(uri: Uri) {
    val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    val name = displayName(uri)

    if (isImage(uri)) {
      val iv = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) }
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = name
      }
      Glide.with(this).load(uri).centerCrop().into(iv)
      binding.attachRow.addView(iv)
    } else {
      val tv = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
          marginEnd = dp(8)
          gravity = android.view.Gravity.CENTER_VERTICAL
        }
        maxLines = 1
        maxWidth = dp(140)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        text = "\uD83D\uDCCE $name"
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundResource(R.drawable.bg_attach_chip)
      }
      binding.attachRow.addView(tv)
    }
    binding.attachScroll.visibility = View.VISIBLE
  }

  private fun clearAttachments() {
    attachments.clear()
    binding.attachRow.removeAllViews()
    binding.attachScroll.visibility = View.GONE
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
  private fun startProjectContext() {
    val root = resolveProject() ?: return
    val path = root.absolutePath
    val currentFileRel = currentFile?.let { f ->
      runCatching { File(f).toRelativeString(root) }.getOrDefault(f)
    }

    lifecycleScope.launch {
      val index = withContext(Dispatchers.IO) { ContextCache.getOrAnalyze(path) }
      systemPrompt = PromptBuilder.build(index, currentFileRel ?: currentFile)
    }
  }

  private fun sendMessage() {
    val text = binding.messageInput.text?.toString()?.trim().orEmpty()
    if (text.isBlank()) return

    // One task per send: block new sends while a task is in flight (prevents duplicate requests).
    val status = activeTask?.status
    if (status == ChatTask.Status.RUNNING || status == ChatTask.Status.WORKING) {
      flashError(getString(string.msg_ai_task_blocked))
      return
    }

    binding.messageInput.text?.clear()

    // Always re-resolve so waiting on this screen never invalidates the session.
    val dir = resolveProject()
    val fullText = if (attachments.isNotEmpty()) {
      val names = attachments.joinToString(", ") { displayName(it) }
      "$text\n\n[Attached: $names]"
    } else text

    val isAnalysis = text.lowercase().startsWith("analyze")
    adapter.add(ChatMessage("user", fullText))
    clearAttachments()
    persistHistory()

    if (isAnalysis && dir != null) {
      lifecycleScope.launch {
        adapter.add(ChatMessage("assistant", "…"))
        binding.sendButton.isEnabled = false
        val analysis = withContext(Dispatchers.IO) {
          val idx = ContextCache.getOrAnalyze(dir.absolutePath)
          PromptBuilder.buildAnalysis(idx)
        }
        adapter.setLastContent(analysis)
        binding.sendButton.isEnabled = true
      }
      return
    }

    val task = ChatTask(
      id = "task_${System.currentTimeMillis()}",
      prompt = fullText,
      status = ChatTask.Status.RUNNING,
    )
    activeTask = task
    dir?.let { ChatTaskStore.save(it, task) }
    adapter.add(ChatMessage("assistant", "⏳ ${getString(string.msg_ai_task_running)}"))
    binding.continueButton.visibility = View.GONE
    binding.sendButton.isEnabled = false

    runTask(task, fullText)
  }

  /**
   * Runs an AI task as a streaming request so tokens arrive incrementally and a single short
   * network read window can never abort a long generation. Updates the task state, the chat
   * bubble, and the per-project task file as it goes.
   */
  private fun runTask(task: ChatTask, prompt: String) {
    lifecycleScope.launch {
      val accumulated = StringBuilder()
      val onChunk: (String) -> Unit = { delta ->
        if (delta.isNotEmpty()) {
          accumulated.append(delta)
          activeTask = activeTask?.copy(
            status = ChatTask.Status.WORKING,
            partial = accumulated.toString(),
            updatedAt = System.currentTimeMillis(),
          )
          projectDir?.let { ChatTaskStore.save(it, activeTask!!) }
          adapter.setLastContent(accumulated.toString())
        }
      }
      try {
        val engine = AiFactory.engine()
        val provider = engine.activeProvider()
        val providerId = provider.providerId
        val model = AiFactory.storage().getModel(providerId)
        val useStream = provider.capabilities.contains(Capability.streaming)
        val dir = projectDir ?: resolveProject()
        val fileOps = dir?.let { ProjectFileOps(it) }
        // Build/Plan use real file-operation tools (app-controlled writes). Chat stays conversational.
        val content = if (fileOps != null && mode != "chat" && provider.capabilities.contains(Capability.tools)) {
          chatEngine.runWithTools(model, prompt, systemPrompt, tools, onChunk) { call -> fileOps.dispatch(call) }
            .message.content
        } else {
          callProvider(model, prompt, useStream, onChunk)
        }
        collectEdits(content)
        // Prefer tool-based writes; fall back to the legacy [[WRITE:]] format only if nothing was written.
        val finalContent = if (mode == "build" && fileOps != null && fileOps.changedFiles > 0) {
          "$content\n\n✅ Applied ${fileOps.changedFiles} file change(s) to this project. Open Preview to see the result."
        } else if (mode == "build" && pendingEdits.isNotEmpty()) {
          val n = pendingEdits.size
          applyEdits()
          "$content\n\n✅ Applied $n file change(s) to this project. Open Preview to see the result."
        } else {
          content
        }
        adapter.setLastContent(finalContent)
        activeTask = activeTask?.copy(
          status = ChatTask.Status.COMPLETED, partial = finalContent, updatedAt = System.currentTimeMillis())
        projectDir?.let { ChatTaskStore.save(it, activeTask!!) }
        persistHistory()
        binding.continueButton.visibility = View.GONE
      } catch (ce: kotlinx.coroutines.CancellationException) {
        // Lifecycle destroyed the coroutine mid-task: keep the partial result and offer Continue.
        activeTask = activeTask?.copy(
          status = ChatTask.Status.INTERRUPTED, partial = accumulated.toString(),
          updatedAt = System.currentTimeMillis())
        projectDir?.let { ChatTaskStore.save(it, activeTask!!) }
        throw ce
      } catch (e: Throwable) {
        val reason = friendlyError(e)
        adapter.setLastContent(
          "⚠ $reason\n\n${getString(string.msg_ai_task_continue)} to retry this task.")
        activeTask = activeTask?.copy(
          status = ChatTask.Status.FAILED, error = reason, partial = accumulated.toString(),
          updatedAt = System.currentTimeMillis())
        projectDir?.let { ChatTaskStore.save(it, activeTask!!) }
        binding.continueButton.visibility = View.VISIBLE
        flashError(reason)
      } finally {
        binding.sendButton.isEnabled = true
      }
    }
  }

  /**
   * Single provider call with at-most-one retry reserved for a genuine 429 carrying Retry-After.
   * Streaming providers (OpenAI-compatible/OpenCode Zen) stream tokens; the fallback path uses a
   * blocking call with the provider's long read timeout.
   */
  private suspend fun callProvider(
    model: String,
    prompt: String,
    useStream: Boolean,
    onChunk: (String) -> Unit,
  ): String = retryOnceOnRateLimit(
    onRetry = { wait -> onChunk("\n⏳ ${getString(string.msg_ai_err_rate_limit_retry, wait)}") }
  ) {
    if (useStream) {
      chatEngine.stream(model, prompt, systemPrompt) { chunk ->
        if (chunk.content.isNotEmpty()) onChunk(chunk.content)
      }.message.content
    } else {
      chatEngine.send(model, prompt, systemPrompt).message.content.also { onChunk(it) }
    }
  }

  /** Resumes a FAILED/INTERRUPTED task from its original prompt (no duplicate in-flight request). */
  private fun continueTask() {
    val task = activeTask ?: return
    if (task.status != ChatTask.Status.FAILED && task.status != ChatTask.Status.INTERRUPTED) return
    adapter.setLastContent("⏳ ${getString(string.msg_ai_task_running)}")
    binding.continueButton.visibility = View.GONE
    binding.sendButton.isEnabled = false
    val resumed = task.copy(status = ChatTask.Status.RUNNING, error = null, updatedAt = System.currentTimeMillis())
    activeTask = resumed
    projectDir?.let { ChatTaskStore.save(it, resumed) }
    runTask(resumed, task.prompt)
  }

  /** Human-readable message that reflects what the provider actually said. */
  private fun friendlyError(err: Throwable): String = when (err) {
    is QuotaException -> getString(string.msg_ai_err_quota)
    is RateLimitException -> getString(string.msg_ai_err_rate_limit)
    is AuthenticationException -> getString(string.msg_ai_err_auth)
    is ModelNotFoundException -> getString(string.msg_ai_err_model)
    is ProviderConfigurationException -> err.message ?: getString(string.msg_ai_err_bad_request)
    is ProviderException -> getString(string.msg_ai_err_bad_request)
    is NetworkException -> getString(string.msg_ai_err_network)
    is SocketTimeoutException -> getString(string.msg_ai_err_timeout_task)
    is java.net.ConnectException -> getString(string.msg_ai_err_network)
    else ->
      if (err is AiException) err.message ?: err.javaClass.simpleName
      else getString(string.msg_ai_chat_error, err.message ?: "unknown error")
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
    val root = projectDir ?: resolveProject() ?: return
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
}
