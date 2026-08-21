package com.hmx.webide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hmx.webide.activities.AIModelsActivity
import com.hmx.webide.activities.PreferencesActivity
import com.hmx.webide.databinding.FragmentProfileSheetBinding

class ProfileSheet : BottomSheetDialogFragment() {

  companion object {
    const val TAG = "vibeide.profile"
  }

  private var _binding: FragmentProfileSheetBinding? = null
  private val binding get() = checkNotNull(_binding)

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = FragmentProfileSheetBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.preferencesBtn.setOnClickListener {
      startActivity(Intent(requireActivity(), PreferencesActivity::class.java))
      dismiss()
    }

    binding.settingsBtn.setOnClickListener {
      startActivity(Intent(requireActivity(), AIModelsActivity::class.java))
      dismiss()
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
