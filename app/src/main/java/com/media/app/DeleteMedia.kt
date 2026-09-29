package com.media.app

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
//  DELETING A FILE
//
//  A player that cannot remove the track you never want to hear again is a
//  read-only window onto your own storage. This is the two-star review.
//
//  Three different rules, one per era, and getting any of them wrong means
//  the delete silently does nothing:
//
//    API 30+  MediaStore.createDeleteRequest. The system asks the user, and
//             the app never needs write access to anything it did not create.
//             The system dialog IS the confirmation, so we do not add a
//             second one in front of it.
//
//    API 29   Scoped storage without the request API. The delete is attempted
//             and throws RecoverableSecurityException, which carries the
//             IntentSender to ask with. That exception type only exists from
//             29, so the code touching it lives behind @RequiresApi rather
//             than behind a runtime check the verifier cannot see.
//
//    API 24-28  A plain delete, with WRITE_EXTERNAL_STORAGE. Nothing asks the
//             user, so we ask them ourselves.
//
//  The resolver call is never made on the main thread: it is a database write
//  and a file unlink.
// ============================================================================

private sealed interface Outcome {
    object Done : Outcome
    data class NeedsConsent(val sender: IntentSender) : Outcome
    object Failed : Outcome
}

@RequiresApi(Build.VERSION_CODES.R)
private fun deleteRequest(context: Context, item: AppMediaItem): IntentSender =
    MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri)).intentSender

@RequiresApi(Build.VERSION_CODES.Q)
private fun consentFrom(t: Throwable): IntentSender? =
    (t as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

/**
 * Returns a function that deletes an item, asking the user in whichever way
 * this Android version requires. [onDeleted] runs only when the file is
 * actually gone.
 */
@Composable
fun rememberMediaDeleter(onDeleted: (AppMediaItem) -> Unit): (AppMediaItem) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Waiting on the system dialog. Held so the result callback knows which
    // item the user just approved.
    var pending by remember { mutableStateOf<AppMediaItem?>(null) }
    // Waiting on OUR dialog, which only exists below API 30.
    var confirm by remember { mutableStateOf<AppMediaItem?>(null) }
    var failed by remember { mutableStateOf<AppMediaItem?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val item = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && item != null) onDeleted(item)
    }

    fun remove(item: AppMediaItem) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(item.uri, null, null)
                    Outcome.Done
                } catch (t: Throwable) {
                    val sender =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) consentFrom(t) else null
                    if (sender != null) Outcome.NeedsConsent(sender) else Outcome.Failed
                }
            }
            when (outcome) {
                is Outcome.Done -> onDeleted(item)
                is Outcome.NeedsConsent -> {
                    pending = item
                    launcher.launch(IntentSenderRequest.Builder(outcome.sender).build())
                }
                is Outcome.Failed -> failed = item
            }
        }
    }

    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this file?", color = MediaColors.Cream) },
            text = {
                Text(
                    "\"${item.title}\" will be removed from this device. " +
                        "This cannot be undone.",
                    color = MediaColors.CreamDim
                )
            },
            containerColor = MediaColors.Modal,
            confirmButton = {
                TextButton({ confirm = null; remove(item) }) {
                    Text("Delete", color = MediaColors.Danger)
                }
            },
            dismissButton = {
                TextButton({ confirm = null }) { Text("Cancel", color = MediaColors.CreamDim) }
            }
        )
    }

    failed?.let { item ->
        AlertDialog(
            onDismissRequest = { failed = null },
            title = { Text("Could not delete", color = MediaColors.Cream) },
            text = {
                Text(
                    "Android would not let this app remove \"${item.title}\". " +
                        "It may be on a card the system protects, or owned by " +
                        "another app.",
                    color = MediaColors.CreamDim
                )
            },
            containerColor = MediaColors.Modal,
            confirmButton = {
                TextButton({ failed = null }) { Text("OK", color = MediaColors.Accent) }
            }
        )
    }

    return { item ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The system's own dialog is the confirmation; ours would be a
            // second one asking the same question.
            pending = item
            val request = runCatching { deleteRequest(context, item) }.getOrNull()
            if (request != null) launcher.launch(IntentSenderRequest.Builder(request).build())
            else { pending = null; failed = item }
        } else {
            confirm = item
        }
    }
}
