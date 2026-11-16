package io.github.smalltailqwq.shallowdrop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.github.smalltailqwq.shallowdrop.R

/** 应用标志，与启动图标共用鹈鹕轮廓。 */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_app_mark), contentDescription = null, modifier = modifier)
}
