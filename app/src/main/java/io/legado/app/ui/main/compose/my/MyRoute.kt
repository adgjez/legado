package io.legado.app.ui.main.compose.my

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class MyRouteCallbacks(
    val onItemClick: (String) -> Unit = {},
    val onHelpClick: () -> Unit = {}
)

@Composable
fun MyRoute(
    modifier: Modifier = Modifier,
    callbacks: MyRouteCallbacks = MyRouteCallbacks(),
    contentPadding: PaddingValues = PaddingValues(bottom = 92.dp),
    sections: List<MySettingSectionUi> = defaultMySettingSections()
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val state = remember(searchQuery, sections) {
        MyPageUiState(
            searchQuery = searchQuery,
            sections = sections
        )
    }
    MyScreen(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        actions = MyPageActions(
            onSearchQueryChange = { searchQuery = it },
            onItemClick = { callbacks.onItemClick(it.key) },
            onHelpClick = callbacks.onHelpClick
        )
    )
}
