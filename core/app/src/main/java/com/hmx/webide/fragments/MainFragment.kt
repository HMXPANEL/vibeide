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

class MainFragment : Fragment() {

  private var _binding: FragmentMainBinding? = null
  private val binding get() = checkNotNull(_binding)

  private var mode = "build"

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

    binding.chatChip.setOnClickListener { setMode("chat") }

    binding.modeSwitch.setOnClickListener {
      setMode(if (mode == "plan") "build" else "plan")
    }

    binding.plusButton.contentDescription = getString(string.home_actions_title)
    binding.plusButton.setOnClickListener {
      HomeActionsSheet().show(childFragmentManager, HomeActionsSheet.TAG)
    }

    binding.sendButton.contentDescription = getString(string.title_ai_chat_send)
    binding.sendButton.setOnClickListener { send() }

    updateModeUi()
  }

  private fun setMode(next: String) {
    mode = next
    updateModeUi()
  }

  private fun updateModeUi() {
    val heading = when (mode) {
      "chat" -> string.home_chat_heading
      "plan" -> string.home_plan_heading
      else -> string.home_welcome
    }
    val hint = when (mode) {
      "chat" -> string.home_hint_chat
      "plan" -> string.home_hint_plan
      else -> string.home_hint_build
    }
    val switchLabel = if (mode == "plan") string.home_build else string.home_plan

    binding.headingText.setText(heading)
    binding.messageInput.setHint(hint)
    binding.modeSwitch.setText(switchLabel)
    binding.chatChip.isChecked = mode == "chat"
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
        putExtra(AIChatActivity.EXTRA_MODE, mode)
      }
    startActivity(intent)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
