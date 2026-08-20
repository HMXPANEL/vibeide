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

package com.hmx.ide.tasks

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Handler(Looper.getMainLooper()) }

/** Run the given block on the main thread. If already on it, run immediately. */
fun runOnUiThread(block: () -> Unit) {
  if (Looper.myLooper() == Looper.getMainLooper()) {
    block()
  } else {
    mainHandler.post(block)
  }
}

/** Cancel this scope if it is still active. */
fun CoroutineScope.cancelIfActive(message: String? = null) {
  if (isActive) {
    cancel(message?.let(::CancellationException))
  }
}