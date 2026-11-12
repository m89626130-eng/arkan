package com.arkan.cadpro

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStreamWriter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArkanApp() }
    }
}

@Composable
private fun ArkanApp() {
    var points by remember { mutableStateOf(listOf<Offset>()) }
    var lispName by remember { mutableStateOf("لم يتم اختيار ملف LSP") }
    var showKmlDialog by remember { mutableStateOf(false) }
    var longitude by remember { mutableStateOf("46.257") }
    var latitude by remember { mutableStateOf("31.057") }
    var status by remember { mutableStateOf("ARKAN CAD PRO V1.0 • Offline") }
    val context = androidx.compose.ui.platform.LocalContext.current

    val lispPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            lispName = uri.lastPathSegment ?: "LSP file selected"
            status = "تم اختيار ملف LISP. تشغيله داخل AutoCAD المكتبي عبر APPLOAD."
        }
    }

    val kmlSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val lon = longitude.toDouble()
                val lat = latitude.toDouble()
                require(lon in -180.0..180.0 && lat in -90.0..90.0)
                val kml = KmlPointWriter.point("ARKAN reference point", lon, lat)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(kml) }
                }
                status = "تم حفظ ملف KML بنجاح."
            } catch (_: Exception) {
                status = "إحداثيات غير صحيحة. أدخل Longitude وLatitude بنظام WGS84."
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF00C8A0),
        background = Color(0xFF101418),
        surface = Color(0xFF1B2329)
    )) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(12.dp)) {
            Text("ARKAN CAD PRO", color = Color(0xFF00C8A0), fontSize = 24.sp)
            Text("V1.0 • Android CAD starter", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { points = emptyList(); status = "تم مسح مساحة الرسم." }) { Text("CLEAR") }
                Button(onClick = { lispPicker.launch(arrayOf("*/*")) }) { Text("LOAD LSP") }
                Button(onClick = { showKmlDialog = true }) { Text("EXPORT KML") }
            }
            Spacer(Modifier.height(8.dp))
            Text(lispName, color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Canvas(
                Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF080B0E), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { points = points + it },
                            onDrag = { change, _ -> points = points + change.position }
                        )
                    }
            ) {
                val step = 40f
                for (x in 0..(size.width / step).toInt()) {
                    drawLine(Color(0xFF263139), Offset(x * step, 0f), Offset(x * step, size.height), 1f)
                }
                for (y in 0..(size.height / step).toInt()) {
                    drawLine(Color(0xFF263139), Offset(0f, y * step), Offset(size.width, y * step), 1f)
                }
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(Color(0xFF00C8A0), a, b, strokeWidth = 3f)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("اسحب بإصبعك للرسم التجريبي • Touch to sketch", color = Color.White, fontSize = 12.sp)
            Text(status, color = Color(0xFFB8C7CE), fontSize = 12.sp)
        }
    }

    if (showKmlDialog) {
        AlertDialog(
            onDismissRequest = { showKmlDialog = false },
            title = { Text("تصدير نقطة KML / WGS84") },
            text = {
                Column {
                    OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") })
                    OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") })
                    Text("هذه النسخة التجريبية تصدّر نقطة مرجعية WGS84 فقط. تصدير عناصر الرسم وتحويل UTM قيد التطوير.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showKmlDialog = false
                    kmlSaver.launch("ARKAN_reference.kml")
                }) { Text("SAVE KML") }
            },
            dismissButton = { TextButton(onClick = { showKmlDialog = false }) { Text("CANCEL") } }
        )
    }
}

private object KmlPointWriter {
    fun point(name: String, longitude: Double, latitude: Double): String {
        val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>$safeName</name>
      <Point><coordinates>${String.format(Locale.US, "%.8f,%.8f,0", longitude, latitude)}</coordinates></Point>
    </Placemark>
  </Document>
</kml>
"""
    }
}
