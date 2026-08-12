package com.example.ottomatic.feature.api

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.ui.theme.OttomaticTheme

/**
 * Asks the user whether one named app may use the process API.
 *
 * ## Why an Activity, started for result
 *
 * `Activity.getCallingPackage()` is non-null **only** when the caller used
 * `startActivityForResult`, and what it reports is verified by the system — the same
 * class of proof `ContentProvider.getCallingPackage()` gives. So requiring that form
 * buys two things at once: the screen can name the app it is about, and no app can
 * raise this dialog *about somebody else*. A plain `startActivity` is refused
 * outright, which is the check that makes the identity worth anything at all.
 *
 * It is also why the refusal Bundle from `ApiTriggerProvider` carries a plain `Intent`
 * rather than a `PendingIntent`: a `PendingIntent` runs under Ottomatic's identity, so
 * this screen would find no calling package and could name nothing.
 *
 * ## What approval means
 *
 * **Package-wide, not per macro**, because `list` enumerates every API trigger on the
 * device — so a per-macro grant would be a promise this screen could not keep. The
 * text says so plainly rather than implying a narrower grant than is being made.
 *
 * What it does *not* cover is every macro: only those the user has deliberately put a
 * "Called by Another App" trigger on. That is the per-macro half of the gate, and it
 * was decided before this screen was ever reached.
 */
class ApiConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller == null) {
            // Reached by a plain startActivity, so there is nobody to name and
            // nothing that could be granted honestly.
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val label = ServiceLocator.packages.labelOf(caller)
        setContent {
            OttomaticTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    ConsentBody(
                        label = label,
                        packageName = caller,
                        onAllow = {
                            ServiceLocator.apiCallers.approveNow(caller, label)
                            setResult(Activity.RESULT_OK)
                            finish()
                        },
                        onDeny = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentBody(
    label: String,
    packageName: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(text = "Allow $label to run your macros?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text = packageName, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "It will be able to see the names of macros you have given a " +
                "\"Called by Another App\" trigger, and to run them. It cannot see or " +
                "change anything else in Ottomatic, and macros without that trigger stay " +
                "out of reach.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "You can withdraw this at any time under App access in Ottomatic.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDeny) { Text("Not now") }
            Spacer(Modifier.padding(horizontal = 4.dp))
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}
