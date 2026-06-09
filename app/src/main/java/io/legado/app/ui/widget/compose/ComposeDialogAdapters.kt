package io.legado.app.ui.widget.compose

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.utils.showDialogFragment

fun Fragment.showComposeConfirmDialog(
    title: CharSequence,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    dangerPositive: Boolean = false,
    onPositive: () -> Unit,
    onNegative: (() -> Unit)? = null,
    onNeutral: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeConfirmDialog.create(
            title = title.toString(),
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            dangerPositive = dangerPositive,
            positiveRequiresCallback = true,
            negativeRequiresCallback = false,
            onPositive = onPositive,
            onNegative = onNegative,
            onNeutral = onNeutral
        )
    )
}

fun Fragment.showComposeActionListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    dangerIndices: Set<Int> = emptySet(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    showDialogFragment(
        ComposeActionListDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            message = message?.toString(),
            descriptions = descriptions.map { it.toString() },
            dangerIndices = dangerIndices,
            negativeText = negativeText.toString(),
            onSelected = onSelected
        )
    )
}

fun Fragment.showComposeSingleChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    allowNoSelection: Boolean = false,
    onPositive: (Int) -> Unit
) {
    showDialogFragment(
        ComposeSingleChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            selectedIndex = selectedIndex,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            allowNoSelection = allowNoSelection,
            onPositive = onPositive
        )
    )
}

fun Fragment.showComposeMultiChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    checkedIndices: Set<Int>,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    onPositive: (BooleanArray) -> Unit
) {
    showDialogFragment(
        ComposeMultiChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            checked = BooleanArray(labels.size) { index -> index in checkedIndices },
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            onPositive = onPositive
        )
    )
}

fun Fragment.showComposeTextInputDialog(
    title: CharSequence,
    hint: CharSequence = "",
    initialValue: CharSequence = "",
    message: CharSequence? = null,
    readOnly: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    validateInput: ((String) -> Boolean)? = null,
    onPositive: (String) -> Unit,
    onNeutral: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeTextInputDialog.create(
            title = title.toString(),
            hint = hint.toString(),
            initialValue = initialValue.toString(),
            message = message?.toString(),
            readOnly = readOnly,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            validateInput = validateInput,
            onPositive = onPositive,
            onNeutral = onNeutral
        )
    )
}

fun AppCompatActivity.showComposeConfirmDialog(
    title: CharSequence,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    dangerPositive: Boolean = false,
    onPositive: () -> Unit,
    onNegative: (() -> Unit)? = null,
    onNeutral: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeConfirmDialog.create(
            title = title.toString(),
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            dangerPositive = dangerPositive,
            positiveRequiresCallback = true,
            negativeRequiresCallback = false,
            onPositive = onPositive,
            onNegative = onNegative,
            onNeutral = onNeutral
        )
    )
}

fun AppCompatActivity.showComposeActionListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    dangerIndices: Set<Int> = emptySet(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    showDialogFragment(
        ComposeActionListDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            message = message?.toString(),
            descriptions = descriptions.map { it.toString() },
            dangerIndices = dangerIndices,
            negativeText = negativeText.toString(),
            onSelected = onSelected
        )
    )
}

fun AppCompatActivity.showComposeSingleChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    allowNoSelection: Boolean = false,
    onPositive: (Int) -> Unit
) {
    showDialogFragment(
        ComposeSingleChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            selectedIndex = selectedIndex,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            allowNoSelection = allowNoSelection,
            onPositive = onPositive
        )
    )
}

fun AppCompatActivity.showComposeMultiChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    checkedIndices: Set<Int>,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    onPositive: (BooleanArray) -> Unit
) {
    showDialogFragment(
        ComposeMultiChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            checked = BooleanArray(labels.size) { index -> index in checkedIndices },
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            onPositive = onPositive
        )
    )
}

fun AppCompatActivity.showComposeTextInputDialog(
    title: CharSequence,
    hint: CharSequence = "",
    initialValue: CharSequence = "",
    message: CharSequence? = null,
    readOnly: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    validateInput: ((String) -> Boolean)? = null,
    onPositive: (String) -> Unit,
    onNeutral: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeTextInputDialog.create(
            title = title.toString(),
            hint = hint.toString(),
            initialValue = initialValue.toString(),
            message = message?.toString(),
            readOnly = readOnly,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            validateInput = validateInput,
            onPositive = onPositive,
            onNeutral = onNeutral
        )
    )
}
