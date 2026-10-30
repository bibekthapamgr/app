package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.proxyflow.core.designsystem.R
import app.proxyflow.core.designsystem.icon.PfIcons

/** Screen title bar; shows a back button when [onBack] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PfTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(PfIcons.Back, contentDescription = stringResource(R.string.pf_navigate_back))
                }
            }
        },
        actions = actions,
        modifier = modifier,
    )
}
