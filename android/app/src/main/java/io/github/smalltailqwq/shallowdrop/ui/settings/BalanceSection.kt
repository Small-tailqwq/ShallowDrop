package io.github.smalltailqwq.shallowdrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.motion.Button
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton

@Composable
fun BalanceSection(state: BalanceState, onRefresh: () -> Unit, onRecharge: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.balance_key_account), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            state.error != null -> Text(stringResource(state.error), color = MaterialTheme.colorScheme.error)
            state.balance != null -> {
                if (!state.balance.available) Text(stringResource(R.string.balance_unavailable), color = MaterialTheme.colorScheme.error)
                state.balance.balances.forEach { balance ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${balance.total} ${balance.currency}", style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.balance_breakdown, balance.toppedUp, balance.granted, balance.currency), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.balance_refresh)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onRecharge) { Text(stringResource(R.string.top_up)) }
        }
    }
}
