package com.hmx.webide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.hmx.webide.activities.MainActivity
import com.hmx.webide.activities.aichat.AIChatActivity
import com.hmx.webide.adapters.RecentProjectsAdapter
import com.hmx.webide.databinding.FragmentMainBinding
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.Environment
import com.hmx.webide.utils.ProjectValidator
import com.hmx.webide.utils.flashError
import java.io.File

class MainFragment : Fragment() {

  private var _binding: FragmentMainBinding? = null
  private val binding get() = checkNotNull(_binding)

  private val recentAdapter by lazy { RecentProjectsAdapter { openProject(it) } }

  private var buildMode = true

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = FragmentMainBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.menuButton.contentDescription = getString(string.home_open_menu)
    binding.menuButton.setOnClickListener { (requireActivity() as MainActivity).openDrawer() }

    binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) return@addOnButtonCheckedListener
      buildMode = checkedId == binding.buildButton.id
      binding.messageInput.hint =
        if (buildMode) getString(string.home_hint_build) else getString(string.home_hint_chat)
    }
    binding.buildButton.isChecked = true
    binding.messageInput.hint = getString(string.home_hint_build)

    binding.plusButton.contentDescription = getString(string.home_actions_title)
    binding.plusButton.setOnClickListener {
      HomeActionsSheet().show(childFragmentManager, HomeActionsSheet.TAG)
    }

    binding.sendButton.contentDescription = getString(string.title_ai_chat_send)
    binding.sendButton.setOnClickListener { send() }

    binding.recentProjects.layoutManager =
      LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
    binding.recentProjects.adapter = recentAdapter
    loadProjects()
  }

  private fun send() {
    val text = binding.messageInput.text?.toString()?.trim().orEmpty()
    if (text.isBlank()) {
      flashError(getString(string.msg_empty_search_query))
      return
    }
    val intent =
      Intent(requireActivity(), AIChatActivity::class.java).apply {
        putExtra(AIChatActivity.EXTRA_INITIAL_MESSAGE, text)
        putExtra(AIChatActivity.EXTRA_MODE, if (buildMode) "build" else "chat")
      }
    startActivity(intent)
  }

  private fun loadProjects() {
    val recent =
      GeneralPreferences.recentProjects
        .filter { it.isNotBlank() && File(it).exists() }
        .toSet()
    val all = (recent + scanProjectsDir()).distinct()
    recentAdapter.submit(all.map { it to (it in recent) })
  }

  private fun scanProjectsDir(): List<String> {
    val dir = Environment.PROJECTS_DIR
    if (!dir.isDirectory) return emptyList()
    return dir
      .listFiles()
      ?.filter { it.isDirectory && ProjectValidator.isSupportedProject(it) }
      ?.map { it.absolutePath }
      ?.sortedBy { it.lowercase() }
      .orEmpty()
  }

  private fun openProject(file: File) {
    if (!ProjectValidator.isSupportedProject(file)) {
      flashError(getString(string.msg_unsupported_project))
      return
    }
    GeneralPreferences.addRecentProject(file.absolutePath)
    (requireActivity() as MainActivity).openProject(file)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
