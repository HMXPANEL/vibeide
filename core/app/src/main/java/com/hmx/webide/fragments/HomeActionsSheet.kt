package com.hmx.webide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hmx.webide.databinding.FragmentHomeActionsSheetBinding
import com.hmx.webide.databinding.LayoutHomeActionItemBinding
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

    setupRow(binding.rowFiles, drawable.ic_add, string.home_action_add_files)
    setupRow(binding.rowImage, drawable.ic_image, string.home_action_add_image)
    setupRow(binding.rowContext, drawable.ic_search, string.home_action_add_context)
    setupRow(binding.rowAttach, drawable.ic_folder, string.home_action_attach_file)
  }

  private fun setupRow(row: View, icon: Int, label: Int) {
    val item = LayoutHomeActionItemBinding.bind(row)
    item.actionIcon.setImageResource(icon)
    item.actionLabel.setText(label)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
