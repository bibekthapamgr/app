package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A bottom sheet's drag handle: Material's, in a touch target of at least 48 × 48 dp, as every control has
 * (docs/m1/04 §1). It draws 32 dp wide, and TalkBack offers it as a control that expands or closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PfSheetDragHandle() {
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
}
