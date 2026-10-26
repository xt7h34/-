package com.rigen.volumeui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The account photo: either one the person picked, or their WhatsApp photo (the default).
 * The bot decides which one to send; here we only keep a copy on the phone so it shows up instantly.
 */
object AvatarStore {
    private const val MAX_SIDE = 512
    private const val REFRESH_EVERY_MS = 2 * 60 * 1000L

    @Volatile
    private var lastRefresh = 0L

    private fun file(c: Context) = File(c.filesDir, "account_avatar.jpg")
    private fun etagFile(c: Context) = File(c.filesDir, "account_avatar.etag")

    fun cached(c: Context): Bitmap? = try {
        val f = file(c)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Throwable) {
        null
    }

    fun clearCache(c: Context) {
        file(c).delete()
        etagFile(c).delete()
        lastRefresh = 0L
    }

    /** Asks the bot for the current photo. Returns true when the copy on the phone changed. */
    suspend fun refresh(c: Context, token: String, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && file(c).exists() && now - lastRefresh < REFRESH_EVERY_MS) return false
        lastRefresh = now
        val etag = if (file(c).exists() && etagFile(c).exists()) etagFile(c).readText() else null
        val r = KanadeApi.fetchBytes("/avatar", token, etag)
        val bytes = r.bytes
        return when {
            r.status == 304 -> false
            r.status in 200..299 && bytes != null && bytes.isNotEmpty() -> withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    false
                } else {
                    file(c).writeBytes(bytes)
                    val tag = r.etag
                    if (tag != null) etagFile(c).writeText(tag) else etagFile(c).delete()
                    true
                }
            }
            // no photo at all (hidden on WhatsApp, none set): go back to the app icon
            r.status == 404 -> withContext(Dispatchers.IO) {
                val had = file(c).exists()
                clearCache(c)
                had
            }
            // offline or any other trouble: keep what we have
            else -> false
        }
    }

    /** Reads the picked image, turns it upright, crops it to a square and shrinks it. */
    private fun prepare(c: Context, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (raw == null) {
                null
            } else {
                val orientation = c.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                val m = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                val side = min(upright.width, upright.height)
                val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
                val done = if (side > MAX_SIDE) Bitmap.createScaledBitmap(square, MAX_SIDE, MAX_SIDE, true) else square
                val out = ByteArrayOutputStream()
                done.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    } catch (e: Throwable) {
        null
    }

    suspend fun upload(c: Context, token: String, uri: Uri): ApiResult {
        val jpeg = withContext(Dispatchers.IO) { prepare(c, uri) }
            ?: return ApiResult(400, JSONObject().put("error", c.getString(R.string.avatar_unreadable)))
        val r = KanadeApi.call(
            "/me/avatar",
            JSONObject().put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
            token,
        )
        if (r.ok) {
            // show the new photo right away; the bot's own copy is fetched the next time
            withContext(Dispatchers.IO) {
                file(c).writeBytes(jpeg)
                etagFile(c).delete()
            }
        }
        return r
    }

    suspend fun setMode(c: Context, token: String, mode: String): ApiResult {
        val r = KanadeApi.call("/me/avatar/mode", JSONObject().put("mode", mode), token)
        if (r.ok) refresh(c, token, force = true)
        return r
    }

    suspend fun remove(c: Context, token: String): ApiResult {
        val r = KanadeApi.call("/me/avatar/remove", JSONObject(), token)
        if (r.ok) refresh(c, token, force = true)
        return r
    }
}

/** The round account picture. Falls back to the app icon when there is no photo. */
@Composable
fun AccountAvatar(size: Dp, token: String?, version: Int = 0, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(version) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(version, token) {
        bmp = withContext(Dispatchers.IO) { AvatarStore.cached(ctx) }
        if (token != null && AvatarStore.refresh(ctx, token, force = version > 0)) {
            bmp = withContext(Dispatchers.IO) { AvatarStore.cached(ctx) }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFF4F1FF)),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.4f),
            )
        }
    }
}

/** The picture with a button to change it: pick from the gallery, use the WhatsApp photo, or remove it. */
@Composable
fun AccountAvatarEditor(token: String, avatar: JSONObject?, onChanged: (JSONObject) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }

    val hasCustom = avatar?.optBoolean("hasCustom") == true
    val usingCustom = avatar?.optString("mode") == "custom"

    fun handle(r: ApiResult) {
        busy = false
        if (r.ok) {
            error = ""
            r.body?.optJSONObject("avatar")?.let(onChanged)
            version++
        } else {
            error = r.message(ctx)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch { handle(AvatarStore.upload(ctx, token, uri)) }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AccountAvatar(96.dp, token, version, Modifier.clickable(enabled = !busy) { open = true })
        TextButton(onClick = { open = true }, enabled = !busy) { Text(stringResource(R.string.avatar_change)) }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.avatar_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { open = false; pick.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.avatar_choose)) }
                    if (usingCustom) {
                        TextButton(
                            onClick = {
                                open = false
                                busy = true
                                scope.launch { handle(AvatarStore.setMode(ctx, token, "whatsapp")) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.avatar_whatsapp)) }
                    } else if (hasCustom) {
                        TextButton(
                            onClick = {
                                open = false
                                busy = true
                                scope.launch { handle(AvatarStore.setMode(ctx, token, "custom")) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.avatar_use_saved)) }
                    }
                    if (hasCustom) {
                        TextButton(
                            onClick = {
                                open = false
                                busy = true
                                scope.launch { handle(AvatarStore.remove(ctx, token)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.avatar_remove)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.avatar_close)) } },
        )
    }
}
