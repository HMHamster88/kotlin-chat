package org.kotlinchat.desktop

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** MDI paperclip, shared by the composer button and the pending-file preview. */
@Composable
internal fun AttachFileIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = MdiIcons.paperclip,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
