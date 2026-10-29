// SPDX-License-Identifier: GPL-3.0-only
package com.palmerintech.firetube.drivepoc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** A fake seekable DocumentsProvider-style URI, not the Google Drive Android provider. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.media3.common.util.UnstableApi
class SafTransportPocTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://drive.poc.documents/document/existing-file")
    private val provider = DocumentProvider()
    @Before fun setup() {
        provider.attachInfo(context, ProviderInfo().apply { authority = "drive.poc.documents" })
        ShadowContentResolver.registerProviderInternal("drive.poc.documents", provider)
    }
    // The provider exposes an existing fixture, without copying it into app storage.
    @Test fun nonMediaStoreDocumentUriCanReachTheExistingMedia3ContentDataSource() {
        val source = ContentDataSource(context)
        try {
            val length = source.open(DataSpec(uri))
            val extractor = Mp3Extractor()
            try { assertTrue(extractor.sniff(DefaultExtractorInput(source, 0, length))) } finally { extractor.release() }
        } finally { source.close() }
    }
    @Test fun seekingDependsOnASeekableDescriptorReturnedByTheProvider() {
        val source = ContentDataSource(context)
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(100).setLength(24).build())
            val buffer = ByteArray(24)
            assertEquals(24, source.read(buffer, 0, 24))
            assertArrayEquals(provider.file.readBytes().copyOfRange(100, 124), buffer)
        } finally { source.close() }
    }
    @Test fun providerDisplayNamesKeepTheirOriginalUnicode() {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(DocumentProvider.NAME, it.getString(0))
        }
    }
    @Test fun revokedPermissionRequiresAnAdapterToHandleSecurityException() {
        provider.revoked = true
        val source = ContentDataSource(context)
        try {
            try { source.open(DataSpec(uri)); fail("Access must be denied") }
            catch (_: SecurityException) { } // Media3 lets this propagate; a future SAF adapter must map it.
        } finally { source.close() }
    }
    class DocumentProvider : ContentProvider() {
        var revoked = false
        val file = File("src/test/resources/local-audio/tone.mp3")
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(NAME)) }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            if (revoked) throw SecurityException("Fixture permission revoked")
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
        }
        override fun getType(uri: Uri) = "audio/mpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        companion object { const val NAME = "עברית العربية Ελληνικά English e\u0301 🎵.mp3" }
    }
}
