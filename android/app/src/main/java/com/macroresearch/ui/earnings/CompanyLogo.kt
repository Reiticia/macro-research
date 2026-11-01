package com.macroresearch.ui.earnings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.CompanyLogoLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CompanyLogo(symbol: String, name: String, loader: CompanyLogoLoader?, source: BackendSettings) {
    var bitmap by remember(symbol, loader, source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(symbol, loader, source) {
        val raw = loader?.image(symbol)
        bitmap = if (raw == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                while (maxOf(bounds.outWidth,bounds.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
                BitmapFactory.decodeByteArray(raw,0,raw.size,options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = Modifier.size(44.dp).testTag("earnings-logo-$symbol"),
        shape = RoundedCornerShape(10.dp),
        color = if (bitmap == null) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image, stringResource(R.string.earnings_company_logo,name),
                modifier = Modifier.padding(6.dp).size(32.dp), contentScale = ContentScale.Fit)
            else Text(symbol.take(2), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
