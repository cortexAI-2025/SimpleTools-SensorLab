package com.simpletools.sensorlab.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.billing.LicenseManager
import com.simpletools.sensorlab.core.billing.ProMessage
import kotlinx.coroutines.launch

/**
 * The PRO offer. The price is the one Google Play returns; nothing is hard-coded here.
 * Purchase, restore and pending states all come from [LicenseManager].
 */
@Composable
fun ProPaywallDialog(license: LicenseManager, onDismiss: () -> Unit) {
    val state by license.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { license.loadPrice() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pro_unlock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.isPro) {
                    Text(stringResource(R.string.pro_status_active), style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(stringResource(R.string.pro_features_title), style = MaterialTheme.typography.titleSmall)
                    listOf(
                        R.string.pro_feature_recording, R.string.pro_feature_history, R.string.pro_feature_stats,
                        R.string.pro_feature_compare, R.string.pro_feature_export, R.string.pro_feature_backup,
                    ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        state.price?.let { stringResource(R.string.pro_price_label, it) }
                            ?: stringResource(R.string.pro_price_unavailable),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(
                        onClick = { scope.launch { license.restore() } },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.pro_restore)) }
                }
                state.message?.let {
                    Text(messageText(it, state.code), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.pro_free_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (state.isPro) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            } else {
                TextButton(
                    onClick = { scope.launch { license.purchase(activity) } },
                    enabled = !state.busy && state.price != null && activity != null,
                ) { Text(stringResource(R.string.pro_buy)) }
            }
        },
        dismissButton = {
            if (!state.isPro) TextButton(onClick = onDismiss) { Text(stringResource(R.string.pro_later)) }
        },
    )
}

@Composable
fun messageText(m: ProMessage, code: Int): String = when (m) {
    ProMessage.PURCHASED -> stringResource(R.string.pro_msg_purchased)
    ProMessage.PURCHASE_CANCELLED -> stringResource(R.string.pro_msg_cancelled)
    ProMessage.PURCHASE_FAILED -> stringResource(R.string.pro_msg_failed, code)
    ProMessage.PURCHASE_PENDING -> stringResource(R.string.pro_msg_pending)
    ProMessage.PURCHASE_ACKNOWLEDGE_FAILED -> stringResource(R.string.pro_msg_ack_failed)
    ProMessage.BILLING_UNAVAILABLE -> stringResource(R.string.pro_msg_unavailable)
    ProMessage.RESTORED -> stringResource(R.string.pro_msg_restored)
    ProMessage.NOTHING_TO_RESTORE -> stringResource(R.string.pro_msg_nothing)
}
