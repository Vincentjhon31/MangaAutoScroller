package com.zynt.mangaautoscroller.util

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class FileManagerUtil {
    companion object {
        @Composable
        fun rememberDirectoryPickerLauncher(onDirectoryPicked: (Uri) -> Unit) =
            rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri?.let { onDirectoryPicked(it) }
            }

        fun getImageFilesFromDirectory(context: Context, directoryUri: Uri): List<Uri> {
            val contentResolver = context.contentResolver
            val docId = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, directoryUri)

            val imageFiles = mutableListOf<Uri>()

            docId?.listFiles()?.forEach { file ->
                if (file.isFile && file.type?.startsWith("image/") == true) {
                    imageFiles.add(file.uri)
                }
            }

            // Sort files by name
            return imageFiles.sortedBy { it.toString() }
        }
    }
}