package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.tokens.Dimens

/**
 * 加载状态：居中的圆形进度指示器。
 *
 * @param modifier 外部布局修饰符，可用来控制占位高度。
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(Dimens.spacingXl),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 亮色预览。 */
@Preview(name = "LoadingState - 亮色", showBackground = true)
@Composable
private fun LoadingStateLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            LoadingState()
        }
    }
}

/** 暗色预览。 */
@Preview(name = "LoadingState - 暗色", showBackground = true)
@Composable
private fun LoadingStateDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            LoadingState()
        }
    }
}
