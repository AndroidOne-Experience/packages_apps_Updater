/*
 * Copyright (C) 2017-2022 The LineageOS Project
 * Copyright (C) 2020-2022 SHIFT GmbH
 * Copyright (C) 2025 PixelOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.androidone.ota

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.RecoverySystem
import android.util.Log
import com.androidone.ota.controller.UpdaterService
import com.androidone.ota.misc.StringGenerator
import com.androidone.ota.misc.Utils
import com.androidone.ota.model.Update
import com.androidone.ota.model.UpdateStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.zip.ZipFile

class UpdateImporter(private val activity: Activity, private val callbacks: Callbacks) {
    fun stopImport() {
        val intent = Intent(activity, UpdaterService::class.java)
        intent.action = UpdaterService.ACTION_IMPORT_STOP
        activity.startService(intent)
    }

    fun openImportPicker() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME_ZIP)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK) {
            return false
        }

        return onPicked(data.data!!, data.flags)
    }

    private fun onPicked(uri: Uri, flags: Int): Boolean {
        try {
            val persistableFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (persistableFlags != 0) {
                activity.contentResolver.takePersistableUriPermission(uri, persistableFlags)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist read permission for local update import", e)
        }

        callbacks.onImportStarted()
        callbacks.onImportProgress(0)

        return try {
            val intent = Intent(activity, UpdaterService::class.java)
            intent.action = UpdaterService.ACTION_IMPORT_LOCAL_UPDATE
            intent.putExtra(UpdaterService.EXTRA_IMPORT_URI, uri.toString())
            activity.startService(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local update import", e)
            callbacks.onImportCompleted(null)
            false
        }
    }

    interface Callbacks {
        fun onImportStarted()

        fun onImportProgress(progress: Int)

        fun onImportCompleted(update: Update?)
    }

    interface ProgressListener {
        fun onProgress(progress: Int)
    }

    companion object {
        private const val REQUEST_PICK = 9061
        private const val TAG = "UpdateImporter"
        private const val MIME_ZIP = "application/zip"
        private const val FILE_NAME = "localUpdate.zip"
        private const val METADATA_PATH = "META-INF/com/android/metadata"
        private const val METADATA_TIMESTAMP_KEY = "post-timestamp="

        @JvmStatic
        @Throws(Exception::class)
        fun importUpdate(context: Context, uri: Uri, progressListener: ProgressListener? = null): Update {
            var importedFile: File? = null
            try {
                importedFile = importFile(context, uri, progressListener)
                verifyPackage(importedFile)
                return buildLocalUpdate(context, importedFile)
            } catch (e: Exception) {
                importedFile?.delete()
                throw e
            }
        }

        @JvmStatic
        @SuppressLint("SetWorldReadable")
        @Throws(IOException::class)
        fun importFile(context: Context, uri: Uri, progressListener: ProgressListener? = null): File {
            val parcelDescriptor =
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Failed to obtain fileDescriptor")

            val iStream = FileInputStream(parcelDescriptor.fileDescriptor)
            val downloadDir = Utils.getDownloadPath(context)
            val outFile = File(downloadDir, FILE_NAME)
            if (outFile.exists()) {
                outFile.delete()
            }
            val oStream = FileOutputStream(outFile)

            var read: Int
            val buffer = ByteArray(4096)
            val fileSize = parcelDescriptor.statSize
            var lastProgress = -2
            while ((iStream.read(buffer).also { read = it }) > 0) {
                oStream.write(buffer, 0, read)
                if (fileSize > 0) {
                    val progress = Math.round(outFile.length() * 100f / fileSize)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        progressListener?.onProgress(progress)
                    }
                }
            }
            oStream.flush()
            oStream.close()
            iStream.close()
            parcelDescriptor.close()

            progressListener?.onProgress(100)
            outFile.setReadable(true, false)

            return outFile
        }

        @JvmStatic
        fun buildLocalUpdate(context: Context, file: File): Update {
            val timeStamp = getTimeStamp(file)
            val buildDate = StringGenerator.getDateLocalizedUTC(context, DateFormat.MEDIUM, timeStamp)
            val name = context.getString(R.string.local_update_import)
            val update = Update()
            update.availableOnline = false
            update.name = name
            update.file = file
            update.fileSize = file.length()
            update.downloadId = Update.LOCAL_ID
            update.timestamp = timeStamp
            update.status = UpdateStatus.VERIFIED
            update.persistentStatus = UpdateStatus.Persistent.VERIFIED
            update.version = String.format("%s (%s)", name, buildDate)
            return update
        }

        @JvmStatic
        @Throws(Exception::class)
        fun verifyPackage(file: File) {
            try {
                RecoverySystem.verifyPackage(file, null, null)
            } catch (e: Exception) {
                if (file.exists()) {
                    file.delete()
                    throw Exception("Verification failed, file has been deleted")
                } else {
                    throw e
                }
            }
        }

        private fun getTimeStamp(file: File): Long {
            try {
                val metadataContent = readZippedFile(file)
                val lines =
                    metadataContent.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                for (line in lines) {
                    if (!line.startsWith(METADATA_TIMESTAMP_KEY)) {
                        continue
                    }

                    val timeStampStr = line.replace(METADATA_TIMESTAMP_KEY, "")
                    return timeStampStr.toLong()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read date from local update zip package", e)
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Failed to parse timestamp number from zip metadata file", e)
            }

            Log.e(TAG, "Couldn't find timestamp in zip file, falling back to \$now")
            return System.currentTimeMillis()
        }

        @Throws(IOException::class)
        private fun readZippedFile(file: File): String {
            val sb = StringBuilder()
            var iStream: InputStream? = null

            try {
                ZipFile(file).use { zip ->
                    val iterator = zip.entries()
                    while (iterator.hasMoreElements()) {
                        val entry = iterator.nextElement()
                        if (METADATA_PATH != entry.name) {
                            continue
                        }

                        iStream = zip.getInputStream(entry)
                        break
                    }

                    if (iStream == null) {
                        throw FileNotFoundException("Couldn't find " + METADATA_PATH + " in " + file.name)
                    }

                    val buffer = ByteArray(1024)
                    var read: Int
                    while ((iStream!!.read(buffer).also { read = it }) > 0) {
                        sb.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read file from zip package", e)
                throw e
            } finally {
                if (iStream != null) {
                    iStream!!.close()
                }
            }

            return sb.toString()
        }
    }
}
