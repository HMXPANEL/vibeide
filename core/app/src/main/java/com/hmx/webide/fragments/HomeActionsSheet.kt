package com.hmx.webide.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hmx.webide.databinding.FragmentHomeActionsSheetBinding
import com.hmx.webide.resources.R.drawable
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.flashError

/** Delivers picked attachments to the hosting screen (implemented by AIChatActivity). */
interface AttachmentListener {
  fun onAttachmentsSelected(uris: List<Uri>)
}

class HomeActionsSheet : BottomSheetDialogFragment() {

  companion object {
    const val TAG = "vibeide.actions"
  }

  private var _binding: FragmentHomeActionsSheetBinding? = null
  private val binding get() = checkNotNull(_binding)

  private val pickFiles =
    registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      deliver(uris)
    }

  private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    uri?.let { deliver(listOf(it)) }
  }

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

    binding.rowFiles.actionIcon.setImageResource(drawable.ic_folder)
    binding.rowFiles.actionLabel.setText(string.home_action_add_files)
    binding.rowFiles.root.setOnClickListener {
      pickFiles.launch(arrayOf("*/*"))
    }

    binding.rowImage.actionIcon.setImageResource(drawable.ic_image)
    binding.rowImage.actionLabel.setText(string.home_action_add_image)
    binding.rowImage.root.setOnClickListener {
      pickImage.launch("image/*")
    }

    binding.rowConnectors.actionIcon.setImageResource(drawable.ic_website)
    binding.rowConnectors.actionLabel.setText(string.home_action_connectors)
    binding.rowConnectors.root.setOnClickListener {
      flashError(requireContext().getString(string.msg_connectors_soon))
      dismiss()
    }
  }

  /** Persists read flags so we can read the picked content later. */
  private fun deliver(uris: List<Uri>) {
    uris.forEach { uri ->
      runCatching {
        requireContext().contentResolver.takePersistableUriPermission(
          uri,
          ContextCompat.FLAG_GRANT_READ_URI_PERMISSION or
            ContextCompat.FLAG_GRANT_WRITE_URI_PERMISSION
        )
      }
    }
    listener()?.onAttachmentsSelected(uris)
    dismiss()
  }

  private fun listener(): AttachmentListener? =
    (parentFragment as? AttachmentListener) ?: (activity as? AttachmentListener)

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
