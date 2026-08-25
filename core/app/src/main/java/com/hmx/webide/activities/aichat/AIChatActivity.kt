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
import com.hmx.webide.app.BaseIDEActivity
import com.hmx.webide.databinding.ActivityAiChatBinding
import com.hmx.webide.fragments.AttachmentListener
import com.hmx.webide.fragments.HomeActionsSheet
import com.hmx.webide.projects.IProjectManager
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.flashError
import com.hmx.webide.utils.flashSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AIChatActivity : BaseIDEActivity(), AttachmentListener {

  companion object {
    const val EXTRA_CURRENT_FILE = "current_file"
    const val EXTRA_INITIAL_MESSAGE = "initial_message"
    const val EXTRA_MODE = "mode"
    const val EXTRA_PROJECT_DIR = "project_dir"
  }

  private lateinit var binding: ActivityAiChatBinding
  private val adapter = AIChatAdapter()
  private val scope = CoroutineScope(Dispatchers.Main)

  /** Project resolved for this chat session; re-checked on every send. */
  private var projectDir: File? = null
  private var currentFile: String? = null
  private var pendingEdits = linkedMapOf<String, String>()
  private val attachments = mutableListOf<Uri>()

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
    updateModeUi()

    val initialMessage = intent.getStringExtra(EXTRA_INITIAL_MESSAGE)
    if (!initialMessage.isNullOrBlank()) {
      binding.messageInput.setText(initialMessage)
    }

    startProjectContext()
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

    scope.launch {
      val index = withContext(Dispatchers.IO) { ContextCache.getOrAnalyze(path) }
      systemPrompt = PromptBuilder.build(index, currentFileRel ?: currentFile)
    }
  }

  private fun sendMessage() {
    val text = binding.messageInput.text?.toString()?.trim().orEmpty()
    if (text.isBlank()) return
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

    if (isAnalysis && dir != null) {
      scope.launch {
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
          val response = chatEngine.send(model, fullText, systemPrompt)
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
