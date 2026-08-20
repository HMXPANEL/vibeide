package com.hmx.webide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hmx.webide.activities.MainActivity
import com.hmx.webide.activities.PreferencesActivity
import com.hmx.webide.adapters.MainActionsListAdapter
import com.hmx.webide.app.BaseIDEActivity
import com.hmx.webide.common.databinding.LayoutDialogProgressBinding
import com.hmx.webide.databinding.FragmentMainBinding
import com.hmx.webide.models.MainScreenAction
import com.hmx.webide.preferences.databinding.LayoutDialogTextInputBinding
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.resources.R.string
import com.hmx.webide.tasks.runOnUiThread
import com.hmx.webide.utils.DialogUtils
import com.hmx.webide.utils.Environment
import com.hmx.webide.utils.flashError
import com.hmx.webide.utils.flashSuccess
import com.hmx.webide.web.WebProjectTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CancellationException

class MainFragment : BaseFragment() {

  private var binding: FragmentMainBinding? = null

  companion object {

    private val log = LoggerFactory.getLogger(MainFragment::class.java)
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentMainBinding.inflate(inflater, container, false)
    return binding!!.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val actions = MainScreenAction.all().also { actions ->
      val onClick = { action: MainScreenAction, _: View ->
        when (action.id) {
          MainScreenAction.ACTION_CREATE_PROJECT -> showCreateProject()
          MainScreenAction.ACTION_OPEN_PROJECT -> showOpenProjectSheet()
          MainScreenAction.ACTION_CLONE_REPO -> cloneGitRepo()
          MainScreenAction.ACTION_PREFERENCES -> gotoPreferences()
        }
      }

      actions.forEach { action ->
        action.onClick = onClick
      }
    }

    binding!!.actions.adapter = MainActionsListAdapter(actions)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding = null
  }

  private fun showOpenProjectSheet() {
    OpenProjectSheet().show(childFragmentManager, OpenProjectSheet.TAG)
  }

  private fun showCreateProject() {
    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    val binding = LayoutDialogTextInputBinding.inflate(layoutInflater)
    binding.name.setHint(string.project_name)

    builder.setView(binding.root)
    builder.setTitle(string.title_create_project)
    builder.setCancelable(false)
    builder.setPositiveButton(string.create) { dialog, _ ->
      dialog.dismiss()
      val name = binding.name.editText?.text?.toString()?.trim().orEmpty()
      if (name.isBlank()) {
        flashError(string.msg_empty_project_name)
        return@setPositiveButton
      }
      val dir = File(Environment.PROJECTS_DIR, name)
      if (dir.exists()) {
        flashError(string.msg_project_already_exists)
        return@setPositiveButton
      }
      showTemplateChooser(name)
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  private fun showTemplateChooser(name: String) {
    val templates = WebProjectTemplates.all
    DialogUtils.newMaterialDialogBuilder(requireContext())
      .setTitle(string.title_choose_template)
      .setItems(templates.map { it.name }.toTypedArray()) { dialog, which ->
        dialog.dismiss()
        doCreateProject(name, templates[which])
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun doCreateProject(name: String, template: WebProjectTemplates.Template) {
    val dir = File(Environment.PROJECTS_DIR, name)

    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        dir.mkdirs()
        template.files(name).forEach { (path, content) ->
          val file = File(dir, path)
          file.parentFile?.mkdirs()
          file.writeText(content)
        }
      }.onFailure { flashError(string.msg_project_create_failed) }

      withContext(Dispatchers.Main) {
        GeneralPreferences.addRecentProject(dir.absolutePath)
        openProject(dir)
      }
    }
  }

  fun openProject(root: File) {
    (requireActivity() as MainActivity).openProject(root)
  }

  private fun cloneGitRepo() {
    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    val binding = LayoutDialogTextInputBinding.inflate(layoutInflater)
    binding.name.setHint(string.git_clone_repo_url)

    builder.setView(binding.root)
    builder.setTitle(string.git_clone_repo)
    builder.setCancelable(true)
    builder.setPositiveButton(string.git_clone) { dialog, _ ->
      dialog.dismiss()
      val url = binding.name.editText?.text?.toString()
      doClone(url)
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  private fun doClone(repo: String?) {
    if (repo.isNullOrBlank()) {
      log.warn("Unable to clone repo. Invalid repo URL : {}'", repo)
      return
    }

    var url = repo.trim()
    if (!url.endsWith(".git")) {
      url += ".git"
    }

    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    val binding = LayoutDialogProgressBinding.inflate(layoutInflater)

    binding.message.visibility = View.VISIBLE

    builder.setTitle(string.git_clone_in_progress)
    builder.setMessage(url)
    builder.setView(binding.root)
    builder.setCancelable(false)

    val repoName = url.substringAfterLast('/').substringBeforeLast(".git")
    val targetDir = File(Environment.PROJECTS_DIR, repoName)

    val progress = GitCloneProgressMonitor(binding.progress, binding.message)
    val coroutineScope = (activity as? BaseIDEActivity?)?.activityScope ?: viewLifecycleScope

    var getDialog: Function0<AlertDialog?>? = null

    val cloneJob = coroutineScope.launch(Dispatchers.IO) {

      val git = try {
        Git.cloneRepository()
          .setURI(url)
          .setDirectory(targetDir)
          .setProgressMonitor(progress)
          .call()
      } catch (err: Throwable) {
        if (!progress.isCancelled) {
          err.printStackTrace()
          withContext(Dispatchers.Main) {
            getDialog?.invoke()?.also { if (it.isShowing) it.dismiss() }
            showCloneError(err)
          }
        }
        null
      }

      try {
        git?.close()
      } finally {
        val success = git != null
        withContext(Dispatchers.Main) {
          getDialog?.invoke()?.also { dialog ->
            if (dialog.isShowing) dialog.dismiss()
            if (success) flashSuccess(string.git_clone_success)
          }
        }
      }
    }

    builder.setPositiveButton(android.R.string.cancel) { iface, _ ->
      iface.dismiss()
      progress.cancel()
      cloneJob.cancel(CancellationException("Cancelled by user"))
    }

    val dialog = builder.show()
    getDialog = { dialog }
  }

  private fun showCloneError(error: Throwable?) {
    if (error == null) {
      flashError(string.git_clone_failed)
      return
    }

    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    builder.setTitle(string.git_clone_failed)
    builder.setMessage(error.localizedMessage)
    builder.setPositiveButton(android.R.string.ok, null)
    builder.show()
  }

  private fun gotoPreferences() {
    startActivity(Intent(requireActivity(), PreferencesActivity::class.java))
  }

  // TODO(itsaky) : Improve this implementation
  class GitCloneProgressMonitor(val progress: LinearProgressIndicator,
    val message: TextView
  ) : ProgressMonitor {

    private var cancelled = false

    fun cancel() {
      cancelled = true
    }

    override fun start(totalTasks: Int) {
      runOnUiThread { progress.max = totalTasks }
    }

    override fun beginTask(title: String?, totalWork: Int) {
      runOnUiThread { message.text = title }
    }

    override fun update(completed: Int) {
      runOnUiThread { progress.progress = completed }
    }

    override fun showDuration(enabled: Boolean) {
      // no-op
    }

    override fun endTask() {}

    override fun isCancelled(): Boolean {
      return cancelled || Thread.currentThread().isInterrupted
    }
  }
}
