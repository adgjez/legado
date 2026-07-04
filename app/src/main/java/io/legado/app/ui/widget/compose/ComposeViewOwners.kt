package io.legado.app.ui.widget.compose

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner

fun ComposeView.installViewTreeOwnersFrom(
    anchor: View,
    fallbackContext: Context = context
) {
    val ownerContext = fallbackContext.findOwnerContext()
    val lifecycleOwner = ViewTreeLifecycleOwner.get(anchor)
        ?: ownerContext as? LifecycleOwner
    val viewModelStoreOwner = ViewTreeViewModelStoreOwner.get(anchor)
        ?: ownerContext as? ViewModelStoreOwner
    val savedStateRegistryOwner = ViewTreeSavedStateRegistryOwner.get(anchor)
        ?: ownerContext as? SavedStateRegistryOwner
    lifecycleOwner?.let { ViewTreeLifecycleOwner.set(this, it) }
    viewModelStoreOwner?.let { ViewTreeViewModelStoreOwner.set(this, it) }
    savedStateRegistryOwner?.let { ViewTreeSavedStateRegistryOwner.set(this, it) }
}

private tailrec fun Context.findOwnerContext(): Context {
    return when (this) {
        is LifecycleOwner, is ViewModelStoreOwner, is SavedStateRegistryOwner -> this
        is ContextWrapper -> baseContext.findOwnerContext()
        else -> this
    }
}
