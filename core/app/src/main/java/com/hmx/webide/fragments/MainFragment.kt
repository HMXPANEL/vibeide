package com.hmx.webide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.hmx.webide.activities.MainActivity
import com.hmx.webide.activities.aichat.AIChatActivity
import com.hmx.webide.databinding.FragmentMainBinding
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.flashError
import java.io.File

class MainFragment : Fragment() {

  private var _binding: FragmentMainBinding? = null
  private val binding get() = checkNotNull(_binding)

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

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
