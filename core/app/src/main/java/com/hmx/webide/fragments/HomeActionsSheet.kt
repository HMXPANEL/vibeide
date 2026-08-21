package com.hmx.webide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hmx.webide.databinding.FragmentHomeActionsSheetBinding
import com.hmx.webide.resources.R.drawable
import com.hmx.webide.resources.R.string

class HomeActionsSheet : BottomSheetDialogFragment() {

  companion object {
    const val TAG = "vibeide.actions"
  }

  private var _binding: FragmentHomeActionsSheetBinding? = null
  private val binding get() = checkNotNull(_binding)

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = FragmentHomeActionsSheetBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.rowFiles.actionIcon.setImageResource(drawable.ic_add)
    binding.rowFiles.actionLabel.setText(string.home_action_add_files)

    binding.rowImage.actionIcon.setImageResource(drawable.ic_image)
    binding.rowImage.actionLabel.setText(string.home_action_add_image)

    binding.rowContext.actionIcon.setImageResource(drawable.ic_search)
    binding.rowContext.actionLabel.setText(string.home_action_add_context)

    binding.rowAttach.actionIcon.setImageResource(drawable.ic_folder)
    binding.rowAttach.actionLabel.setText(string.home_action_attach_file)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
