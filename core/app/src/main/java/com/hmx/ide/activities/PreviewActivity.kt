/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hmx.ide.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hmx.ide.R
import com.hmx.ide.utils.resolveAttr
import com.hmx.ide.web.WebPreviewServer
import java.io.File

/**
 * Live preview of the open web project. Serves the project directory over a
 * local HTTP server and renders it in a WebView (JS enabled), so relative
 * paths, `fetch()` and ES modules behave like a real browser.
 *
 * @author Akash Yadav
 */
class PreviewActivity : AppCompatActivity() {

  companion object {
    private const val TAG = "PreviewActivity"
    const val EXTRA_PROJECT_DIR = "projectDir"

    @JvmStatic
    fun newIntent(context: Context, projectDir: File): Intent {
      return Intent(context, PreviewActivity::class.java).apply {
        putExtra(EXTRA_PROJECT_DIR, projectDir.absolutePath)
      }
    }
  }

  private var server: WebPreviewServer? = null
  private var webView: WebView? = null

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val projectDir = intent.getStringExtra(EXTRA_PROJECT_DIR)?.let(::File)
    if (projectDir == null || !projectDir.isDirectory) {
      finish()
      return
    }

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(resolveAttr(R.attr.colorSurface))
    }

    val onSurface = resolveAttr(R.attr.colorOnSurface)

    val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      max = 100
      visibility = android.view.View.GONE
    }

    val webView = WebView(this).apply {
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      webViewClient = WebViewClient()
      webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
          progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
          progress.progress = newProgress
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
          Log.d(TAG, "[page] ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
          return true
        }
      }
    }
    this.webView = webView

    val toolbar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_bar_default_height_material))
      setPadding(
        resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_bar_default_padding_start_material),
        0, resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_bar_default_padding_end_material), 0)
    }

    toolbar.addView(iconButton(androidx.appcompat.R.drawable.abc_ic_ab_back_material, onSurface) {
      if (webView.canGoBack()) webView.goBack() else finish()
    })
    toolbar.addView(iconButton(R.drawable.ic_refresh, onSurface) { webView.reload() })
    toolbar.addView(iconButton(R.drawable.ic_close, onSurface) { finish() })

    root.addView(toolbar)
    root.addView(progress)
    root.addView(webView)
    setContentView(root)

    val server = WebPreviewServer(projectDir)
    this.server = server
    Thread {
      val port = server.start()
      runOnUiThread {
        webView.loadUrl("http://127.0.0.1:$port/")
      }
    }.start()
  }

  private fun iconButton(@androidx.annotation.DrawableRes iconRes: Int, tint: Int,
    onClick: () -> Unit
  ): ImageButton {
    return ImageButton(this).apply {
      setImageDrawable(ContextCompat.getDrawable(this@PreviewActivity, iconRes))
      setColorFilter(tint)
      background = null
      setPadding(12.dp, 12.dp, 12.dp, 12.dp)
      layoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_button_min_width_material),
        ViewGroup.LayoutParams.MATCH_PARENT)
      setOnClickListener { onClick() }
    }
  }

  private val Int.dp: Int
    get() = (this * resources.displayMetrics.density).toInt()

  override fun onBackPressed() {
    val webView = webView
    if (webView != null && webView.canGoBack()) {
      webView.goBack()
      return
    }
    super.onBackPressed()
  }

  override fun onDestroy() {
    server?.stop()
    webView?.destroy()
    webView = null
    super.onDestroy()
  }
}