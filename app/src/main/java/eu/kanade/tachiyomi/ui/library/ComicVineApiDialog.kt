package eu.kanade.tachiyomi.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.preference.ComicVinePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun ComicVineApiDialog(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { ComicVinePreferences(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(prefs.getApiKey()) }
    var keyVisible by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<TestStatus>(TestStatus.Idle) }
    var cacheCleared by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("ComicVine API") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Enter your ComicVine API key. You can get one free at comicvine.gamespot.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        testStatus = TestStatus.Idle
                        cacheCleared = false
                    },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = if (keyVisible) "Hide key" else "Show key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Test connection row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            testStatus = TestStatus.Loading
                            scope.launch {
                                testStatus = testApiKey(apiKey.trim())
                            }
                        },
                        enabled = apiKey.isNotBlank() && testStatus != TestStatus.Loading,
                    ) {
                        Text("Test")
                    }

                    when (val status = testStatus) {
                        TestStatus.Idle -> {}
                        TestStatus.Loading -> CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                        )
                        is TestStatus.Success -> Text(
                            text = "✓ Connected",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is TestStatus.Error -> Text(
                            text = "✗ ${status.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                HorizontalDivider()

                // Cache section
                Text(
                    text = "Volume cache stores ComicVine series matches for your library. Clear it if releases aren't showing correctly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            prefs.clearVolumeCache()
                            cacheCleared = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Clear Cache")
                    }

                    if (cacheCleared) {
                        Text(
                            text = "✓ Cache cleared",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.setApiKey(apiKey.trim())
                    onDismissRequest()
                },
                enabled = apiKey.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        },
    )
}

private sealed interface TestStatus {
    data object Idle : TestStatus
    data object Loading : TestStatus
    data object Success : TestStatus
    data class Error(val message: String) : TestStatus
}

private suspend fun testApiKey(apiKey: String): TestStatus {
    if (apiKey.isBlank()) return TestStatus.Error("Key is empty")
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://comicvine.gamespot.com/api/issues/?api_key=$apiKey&format=json&limit=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mihon/1.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val code = connection.responseCode
            connection.disconnect()
            when (code) {
                200 -> TestStatus.Success
                401 -> TestStatus.Error("Invalid API key (401)")
                403 -> TestStatus.Error("Forbidden — check your key (403)")
                404 -> TestStatus.Error("Endpoint not found (404)")
                else -> TestStatus.Error("Unexpected response: $code")
            }
        } catch (e: Exception) {
            TestStatus.Error(e.message ?: "Connection failed")
        }
    }
}
