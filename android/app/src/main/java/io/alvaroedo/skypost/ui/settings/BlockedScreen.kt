package io.alvaroedo.skypost.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.ui.components.SubTopBar
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.label

/** Everything blocked (contacts, groups, bridged contacts, Pharos), to unblock. */
@Composable
fun BlockedScreen(repo: Repository, onBack: () -> Unit) {
    val blocked by repo.blocked.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { SubTopBar(stringResource(R.string.blocked_list), onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (blocked.isEmpty()) {
                item { ListItem(headlineContent = { Text(stringResource(R.string.blocked_empty)) }) }
            }
            items(blocked, key = { it.key }) { b ->
                val kind = b.kind
                ListItem(
                    leadingContent = {
                        if (kind != null) {
                            Avatar(b.title, kind, 40.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_cell_tower), null)
                        }
                    },
                    headlineContent = { Text(b.title) },
                    supportingContent = { Text(stringResource(kind?.label() ?: R.string.pharos_title)) },
                    trailingContent = { TextButton(onClick = { repo.unblock(b.key) }) { Text(stringResource(R.string.unblock)) } },
                )
            }
        }
    }
}
