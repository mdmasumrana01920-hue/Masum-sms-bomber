package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data model for displaying testing operations in our real-time audit logs
data class AuditLog(
    val timestamp: String,
    val serial: Int,
    val target: String,
    val status: String,
    val isSuccess: Boolean,
    val responseTimeMs: Long,
    val details: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    RateLimitTesterScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateLimitTesterScreen(modifier: Modifier = Modifier) {
    // Audit input parameters
    var targetPhone by remember { mutableStateOf("") }
    var requestCountString by remember { mutableStateOf("10") }
    var requestDelayString by remember { mutableStateOf("1500") } // delay in ms
    var mockRequestUri by remember { mutableStateOf("https://httpbin.org/post") } // Safe custom auditing target helper

    // Execution state trackers
    var isRunning by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableStateOf(0f) }
    var currentRequestIndex by remember { mutableStateOf(0) }
    var totalRequestsNeeded by remember { mutableStateOf(0) }
    
    // Summary metrics
    var successCount by remember { mutableStateOf(0) }
    var failureCount by remember { mutableStateOf(0) }
    var averageLatencyMs by remember { mutableStateOf(0L) }
    
    // Status text
    var consoleStatusMessage by remember { mutableStateOf("Ready to begin API rate-limit evaluation.") }

    // Logs list
    val auditLogs = remember { mutableStateListOf<AuditLog>() }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var testerJob by remember { mutableStateOf<Job?>(null) }

    // Error triggers
    var phoneError by remember { mutableStateOf(false) }
    var countError by remember { mutableStateOf(false) }
    var delayError by remember { mutableStateOf(false) }

    // Colors
    val goldYellow = Color(0xFFFFD700)
    val darkBackground = Color(0xFF121212)
    val cardBackground = Color(0xFF1E1E1E)
    val terminalColor = Color(0xFF0D0D0D)
    val greenStatus = Color(0xFF00FFCC)
    val redStatus = Color(0xFFFF4848)

    // Function to run HTTP rate limit checks (POST or GET sandbox request)
    fun triggerApiAudit() {
        // Validate inputs
        phoneError = targetPhone.isBlank() || targetPhone.length < 5
        val requestCount = requestCountString.toIntOrNull()
        countError = requestCount == null || requestCount <= 0
        val requestDelay = requestDelayString.toIntOrNull()
        delayError = requestDelay == null || requestDelay < 100

        if (phoneError || countError || delayError) {
            consoleStatusMessage = "Invalid configuration. Review parameters & try again."
            return
        }

        // Setup environment
        val finalCount = requestCount ?: 10
        val finalDelay = requestDelay ?: 1500
        val finalPhone = targetPhone
        val finalUri = mockRequestUri

        isRunning = true
        currentProgress = 0f
        currentRequestIndex = 0
        totalRequestsNeeded = finalCount
        successCount = 0
        failureCount = 0
        averageLatencyMs = 0L
        auditLogs.clear()
        consoleStatusMessage = "Initializing parallel task workers..."

        // Spawn Coroutine for complete background network operations (No UI thread usage or freeze)
        testerJob = coroutineScope.launch(Dispatchers.IO) {
            var latencySum = 0L
            
            for (i in 1..finalCount) {
                if (!isRunning) break

                withContext(Dispatchers.Main) {
                    currentRequestIndex = i
                    consoleStatusMessage = "Attempting dispatch serial #$i to verify threshold limits..."
                }

                val startTime = System.currentTimeMillis()
                var success = false
                var resCode = -1
                var statusMsg = ""
                var details = ""

                try {
                    val url = URL(finalUri)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android Studio API Stress Tester)")

                    // Provide payload safely mimicking generic user metrics parameters
                    val postPayload = "{\"target\":\"$finalPhone\",\"serial\":$i,\"client_id\":\"masum_bomber_tester\"}"
                    
                    conn.outputStream.use { os ->
                        val input = postPayload.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    resCode = conn.responseCode
                    statusMsg = conn.responseMessage ?: "No status text"
                    
                    // Consider standard 2xx statuses as successfully validated rate limit transactions
                    if (resCode in 200..299) {
                        success = true
                        details = "200 OK — Limit reached or API accepted target correctly."
                    } else {
                        success = false
                        details = "$resCode — Request rejected or rate-limited correctly."
                    }
                } catch (e: Exception) {
                    success = false
                    resCode = 0
                    statusMsg = e.message ?: "Network Socket Timeout"
                    details = "Failed connection: ${e.localizedMessage ?: "Unknown Network Error"}"
                }

                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime
                latencySum += duration

                val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                val timestamp = formatter.format(Date())

                val newLog = AuditLog(
                    timestamp = timestamp,
                    serial = i,
                    target = finalPhone,
                    status = if (resCode == 0) "TIMEOUT" else "HTTP $resCode",
                    isSuccess = success,
                    responseTimeMs = duration,
                    details = details
                )

                withContext(Dispatchers.Main) {
                    auditLogs.add(newLog)
                    currentProgress = i.toFloat() / finalCount.toFloat()
                    if (success) successCount++ else failureCount++
                    averageLatencyMs = latencySum / i
                    
                    // Auto scroll to latest logs
                    coroutineScope.launch {
                        if (auditLogs.size > 0) {
                            lazyListState.animateScrollToItem(auditLogs.size - 1)
                        }
                    }
                }

                // Sleep requested interval delay between stress queries
                if (i < finalCount) {
                    delay(finalDelay.toLong())
                }
            }

            withContext(Dispatchers.Main) {
                isRunning = false
                consoleStatusMessage = "Testing process finalized. Evaluated $finalCount API validation audits."
            }
        }
    }

    // Terminate audit safely inside coroutine context immediately
    fun stopApiAudit() {
        isRunning = false
        testerJob?.cancel()
        consoleStatusMessage = "Audit forced to cancel. Analysis report populated below."
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Gold Yellow Title Header with beautiful icon visual descriptors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Shield and Security",
                    tint = goldYellow,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Masum SMS Bomber",
                        color = goldYellow,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.testTag("app_title")
                    )
                    Text(
                        text = "Secure API Stress & Rate Limit Tester v1.2",
                        color = Color.LightGray.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            // Warnings Box indicating security precautions
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Instruction Info",
                        tint = goldYellow,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Instructions: Audit endpoint speed and security vulnerabilities. Only test endpoints you are authorized to evaluate.",
                        color = Color.White,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // Parameter Input Section Form with Filled TextFields and Dark Card Styles
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBackground),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Aptitude Testing Parameters",
                        color = goldYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    // Target Resource / Phone input
                    OutlinedTextField(
                        value = targetPhone,
                        onValueChange = { targetPhone = it },
                        label = { Text("Target Phone Number / ID") },
                        placeholder = { Text("e.g. +8801XXXXXXXXX") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = "Phone icon", tint = goldYellow) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("target_phone_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        isError = phoneError,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = goldYellow,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = goldYellow
                        )
                    )
                    if (phoneError) {
                        Text(
                            text = "Standard target phone/ID required (min 5 characters).",
                            color = redStatus,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Request Limit Count input
                        OutlinedTextField(
                            value = requestCountString,
                            onValueChange = { requestCountString = it },
                            label = { Text("SMS Count") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sms_count_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = countError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = goldYellow,
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = goldYellow
                            )
                        )

                        // Delays in ms
                        OutlinedTextField(
                            value = requestDelayString,
                            onValueChange = { requestDelayString = it },
                            label = { Text("Delay (ms)") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("delay_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = delayError,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = goldYellow,
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = goldYellow
                            )
                        )
                    }

                    // Simulated/Real backend endpoint hook inputs
                    OutlinedTextField(
                        value = mockRequestUri,
                        onValueChange = { mockRequestUri = it },
                        label = { Text("Audited Gate Endpoint URL") },
                        leadingIcon = { Icon(Icons.Default.Send, contentDescription = "Endpoint URI", tint = goldYellow) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("endpoint_url_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = goldYellow,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = goldYellow
                        )
                    )
                }
            }

            // Real-Time Control & Start Bombing (Stress test) Button Block
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isRunning) {
                    Button(
                        onClick = { triggerApiAudit() },
                        colors = ButtonDefaults.buttonColors(containerColor = goldYellow, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("start_bombing_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start stress test icon")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "START BOMBING",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Button(
                        onClick = { stopApiAudit() },
                        colors = ButtonDefaults.buttonColors(containerColor = redStatus, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("stop_bombing_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Terminate background loop")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "STOP TESTING",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Live progress & system feedback metrics banner
            AnimatedVisibility(visible = isRunning || auditLogs.isNotEmpty()) {
                Surface(
                    color = terminalColor,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, goldYellow.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "WORKER PROGRESS: $currentProgress",
                                color = goldYellow,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Request $currentRequestIndex of $totalRequestsNeeded",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        LinearProgressIndicator(
                            progress = { currentProgress },
                            color = goldYellow,
                            trackColor = Color.DarkGray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Latency: ${averageLatencyMs}ms",
                                color = greenStatus,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Success: $successCount",
                                color = greenStatus,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Refused/Safe: $failureCount",
                                color = redStatus,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Real-time Virtualized Terminal Emulator detailing transaction records
            Surface(
                color = terminalColor,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Title Bar for terminal emulator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(greenStatus)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REAL-TIME AUDIT LOGS",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        IconButton(
                            onClick = { auditLogs.clear(); successCount = 0; failureCount = 0 },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear all screen messages",
                                tint = Color.LightGray,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)

                    // Logs scroll container
                    if (auditLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = consoleStatusMessage,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(auditLogs) { log ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (log.isSuccess) Color(0x1100FFCC) else Color(
                                                0x11FF4848
                                            )
                                        )
                                        .padding(6.dp)
                                        .border(
                                            0.5.dp,
                                            if (log.isSuccess) greenStatus.copy(alpha = 0.2f) else redStatus.copy(
                                                alpha = 0.2f
                                            ),
                                            RoundedCornerShape(4.dp)
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "[#${log.serial}] - ${log.timestamp}",
                                            color = goldYellow,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = log.status,
                                            color = if (log.isSuccess) greenStatus else redStatus,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Target: ${log.target} | Latency: ${log.responseTimeMs}ms",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = log.details,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
