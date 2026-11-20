package com.arkan.cadpro

import android.net.Uri
import android.os.Bundle
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.hypot
import kotlin.math.pow

private const val PX_PER_METER = 40f
private data class Pipe(val a: Offset, val b: Offset, val layer: String)

private fun pipeLayerColor(layer: String): Color {
    val key = layer.trim().uppercase(Locale.ROOT)
    return when {
        key.contains("250") -> Color(0xFFFF3030)
        key.contains("315") -> Color(0xFF2878FF)
        key.contains("400") -> Color(0xFF20C060)
        key.contains("500") -> Color(0xFFFFD21F)
        key.contains("600") -> Color(0xFF20D8E8)
        key.contains("700") -> Color(0xFFFF9633)
        else -> Color(0xFF00C8A0)
    }
}


private fun nearestManhole(point: Offset, manholes: List<Manhole>): Manhole? =
    manholes.minByOrNull { hypot((it.point.x - point.x).toDouble(), (it.point.y - point.y).toDouble()) }
        ?.takeIf { hypot((it.point.x - point.x).toDouble(), (it.point.y - point.y).toDouble()) <= 3.0 * PX_PER_METER }

private fun connectedManholes(pipe: Pipe, manholes: List<Manhole>): Pair<Manhole, Manhole>? {
    val start = nearestManhole(pipe.a, manholes) ?: return null
    val end = nearestManhole(pipe.b, manholes) ?: return null
    return if (start.id != end.id && start.invertLevel != null && end.invertLevel != null) start to end else null
}

private data class Manhole(val id: String, val point: Offset, val depth: Double, val length: Double, val width: Double, val northing: Double, val easting: Double, val altitude: Double = 0.0, val groundLevel: Double? = null, val invertLevel: Double? = null, val coordinatesKnown: Boolean = true, val manualType: String? = null) {
    val type: String get() = manualType ?: when { depth <= 0.0 -> "غير معروف"; depth <= 1.70 -> "AS"; depth <= 3.00 -> "BS"; depth <= 4.00 -> "CS"; else -> "CD" }
    val isCircular: Boolean get() = depth >= 1.70
    val excavation: Double get() = if (isCircular) kotlin.math.PI * (length / 2.0) * (length / 2.0) * depth else length * width * depth
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ArkanApp() } }
}

@Composable
private fun ArkanApp() {
    var pipes by remember { mutableStateOf(listOf<Pipe>()) }
    var manholes by remember { mutableStateOf(listOf<Manhole>()) }
    var mode by remember { mutableStateOf("PIPE") }
    var pipeStart by remember { mutableStateOf<Offset?>(null) }
    var pipeLayer by remember { mutableStateOf("PIPE-250") }
    var targetSlopePercent by remember { mutableStateOf("0.50") }
    var showPipeDiameterMenu by remember { mutableStateOf(false) }
    var trenchWidth by remember { mutableStateOf("0.60") }
    var trenchDepth by remember { mutableStateOf("2.00") }
    var lispName by remember { mutableStateOf("لم يتم اختيار ملف LSP") }
    var status by remember { mutableStateOf("اختر أداة ثم المس مساحة الرسم.") }
    var showManholeDialog by remember { mutableStateOf(false) }
    var showKmlDialog by remember { mutableStateOf(false) }
    var dxfFileName by remember { mutableStateOf("لم يتم اختيار DXF") }
    var importedKml by remember { mutableStateOf<String?>(null) }
    var importedSummary by remember { mutableStateOf("") }
    var importedLayerLengths by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var importedManholeCount by remember { mutableStateOf(0) }
    var importedManholes by remember { mutableStateOf(listOf<Manhole>()) }
    var importedDiameterLengths by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var importedUnclassifiedGeometryLength by remember { mutableStateOf(0.0) }
    var longitude by remember { mutableStateOf("46.257") }
    var latitude by remember { mutableStateOf("31.057") }
    var mhId by remember { mutableStateOf("MH-01") }
    var mhDepth by remember { mutableStateOf("") }
    var mhLength by remember { mutableStateOf("1.10") }
    var mhWidth by remember { mutableStateOf("1.10") }
    var mhNorthing by remember { mutableStateOf("") }
    var mhEasting by remember { mutableStateOf("") }
    var mhGroundLevel by remember { mutableStateOf("") }
    var mhInvertLevel by remember { mutableStateOf("") }
    var pendingManhole by remember { mutableStateOf<Manhole?>(null) }
    val context = LocalContext.current

    val totalLength = pipes.sumOf { hypot((it.b.x-it.a.x).toDouble(), (it.b.y-it.a.y).toDouble()) / PX_PER_METER }
    val effectiveManholes = if (importedKml != null) importedManholes else manholes
    val totalExcavation = effectiveManholes.sumOf { it.excavation }
    val layerLengths = pipes.groupBy { it.layer }.mapValues { entry -> entry.value.sumOf { hypot((it.b.x-it.a.x).toDouble(), (it.b.y-it.a.y).toDouble()) / PX_PER_METER } }
    val effectiveLayerLengths = if (importedKml != null) importedLayerLengths else layerLengths
    val effectivePipeLength = if (importedKml != null) effectiveLayerLengths.values.sum() else totalLength
    val effectiveDiameterLengths = if (importedKml != null) importedDiameterLengths else emptyMap()
    val standardPipeDiameters = listOf(250, 315, 400, 500, 600, 700)
    val displayedPipeDiameters = (standardPipeDiameters + effectiveDiameterLengths.keys).distinct().sorted()
    val importedDiameterLabelTotal = effectiveDiameterLengths.values.sum()
    val diameterUnclassifiedLength = importedUnclassifiedGeometryLength
    val trenchVolume = effectivePipeLength * (trenchWidth.toDoubleOrNull() ?: 0.0) * (trenchDepth.toDoubleOrNull() ?: 0.0)

    val dxfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("تعذر قراءة الملف")
                val converted = DxfKmlImporter.convert(raw)
                importedKml = converted.kml
                importedSummary = converted.summary
                importedLayerLengths = converted.layerLengths
                importedManholeCount = converted.manholeCount
                importedManholes = converted.manholes
                importedDiameterLengths = converted.diameterLengths
                importedUnclassifiedGeometryLength = converted.unclassifiedGeometryLength
                dxfFileName = uri.lastPathSegment ?: "DXF"
                status = "تم استيراد هندسة DXF: $importedSummary. نظام الإحداثيات المفترض WGS84 / UTM 38N."
            } catch (e: Exception) { status = "تعذر فتح DXF: " + e.message }
        }
    }

    val lispPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { lispName = uri.lastPathSegment ?: "LSP file selected"; status = "تم اختيار ملف LISP. شغّله في AutoCAD المكتبي باستخدام APPLOAD." }
    }

    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) try {
            val rows = mutableListOf<String>()
            rows.add("ARKAN CAD PRO - Quantity Report")
            rows.add("Item,Value,Unit")
            rows.add("Total pipe length,${String.format(Locale.US,"%.2f",effectivePipeLength)},m")
            rows.add("Manholes count,${effectiveManholes.size},count")
            rows.add("Manhole excavation,${String.format(Locale.US,"%.2f",totalExcavation)},m3")
            rows.add("Trench width,$trenchWidth,m")
            rows.add("Trench depth,$trenchDepth,m")
            rows.add("Estimated pipe trench excavation,${String.format(Locale.US,"%.2f",trenchVolume)},m3")
            rows.add("")
            rows.add("Pipe lengths by Diameter (mm),Length (m),Source")
            displayedPipeDiameters.forEach { diameter ->
                val length = effectiveDiameterLengths[diameter] ?: 0.0
                rows.add("$diameter,${String.format(Locale.US,"%.2f",length)},${if (length > 0.0) "DXF label" else "Not labelled in drawing"}")
            }
            if (importedKml != null) rows.add("Unclassified centerline geometry,${String.format(Locale.US,"%.2f",diameterUnclassifiedLength)},m")
            rows.add("")
            rows.add("Pipe lengths by Layer,,")
            effectiveLayerLengths.forEach { (layer,length) -> rows.add("$layer,${String.format(Locale.US,"%.2f",length)},m") }
            rows.add("")
            rows.add("Manhole ID,Type,Depth m,GL m,INV m,Length m,Width m,N,E,Excavation m3")
            effectiveManholes.forEach { mh -> rows.add("${mh.id},${mh.type},${mh.depth},${mh.groundLevel ?: ""},${mh.invertLevel ?: ""},${mh.length},${mh.width},${mh.northing},${mh.easting},${String.format(Locale.US,"%.2f",mh.excavation)}") }
            context.contentResolver.openOutputStream(uri)?.use { it.write(("\uFEFF" + rows.joinToString("\r\n")).toByteArray(Charsets.UTF_8)) }
            status = "تم تصدير جدول الكميات بصيغة CSV المتوافقة مع Excel."
        } catch (e: Exception) { status = "تعذر تصدير جدول Excel: ${e.message}" }
    }
    val xlsxSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri: Uri? ->
        if (uri != null) try {
            val output = context.contentResolver.openOutputStream(uri) ?: error("تعذر فتح ملف الحفظ")
            output.use {
                ArkanXlsxWriter.write(
                    it,
                    effectivePipeLength,
                    effectiveManholes.size,
                    totalExcavation,
                    trenchWidth.toDoubleOrNull() ?: 0.0,
                    trenchDepth.toDoubleOrNull() ?: 0.0,
                    trenchVolume,
                    effectiveLayerLengths,
                    effectiveDiameterLengths,
                    diameterUnclassifiedLength,
                    effectiveManholes
                )
            }
            status = "تم تصدير ملف Excel منسق بثلاث أوراق: الملخص، أطوال الأنابيب، والمناهيل."
        } catch (e: Exception) {
            status = "تعذر تصدير Excel: ${e.message}"
        }
    }

    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) try {
            val document = PdfDocument()
            val paint = Paint().apply { textSize = 12f; color = android.graphics.Color.BLACK }
            val lines = mutableListOf<String>()
            lines.add("ARKAN CAD PRO - Quantity Report")
            lines.add("Total pipe length: %.2f m".format(Locale.US,effectivePipeLength))
            lines.add("Manholes count: ${effectiveManholes.size}")
            lines.add("Manhole excavation: %.2f m3".format(Locale.US,totalExcavation))
            lines.add("Trench width: $trenchWidth m")
            lines.add("Trench depth: $trenchDepth m")
            lines.add("Estimated pipe trench excavation: %.2f m3".format(Locale.US,trenchVolume))
            lines.add("")
            lines.add("Pipe lengths by diameter (values explicitly labelled in DXF):")
            displayedPipeDiameters.forEach { diameter ->
                val length = effectiveDiameterLengths[diameter] ?: 0.0
                lines.add("$diameter mm : %.2f m".format(Locale.US,length))
            }
            if (importedKml != null) lines.add("Unclassified centerline geometry : %.2f m".format(Locale.US,diameterUnclassifiedLength))
            lines.add("Geometric centerline by DXF layer:")
            effectiveLayerLengths.forEach { (layer,length) -> lines.add("$layer : %.2f m".format(Locale.US,length)) }
            lines.add("")
            lines.add("Manholes: ID | Type | Depth | GL | INV | Length | Width | N | E | Excavation")
            effectiveManholes.forEach { mh -> lines.add("${mh.id} | ${mh.type} | ${mh.depth} | ${mh.groundLevel ?: "-"} | ${mh.invertLevel ?: "-"} | ${mh.length} | ${mh.width} | ${mh.northing} | ${mh.easting} | %.2f".format(Locale.US,mh.excavation)) }
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create())
            var y = 48f
            lines.forEach { line ->
                if (y > 800f) {
                    document.finishPage(page)
                    pageNumber++
                    page = document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create())
                    y = 48f
                }
                page.canvas.drawText(line.take(105),36f,y,paint)
                y += 20f
            }
            document.finishPage(page)
            context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
            document.close()
            status = "تم تصدير تقرير PDF."
        } catch (e: Exception) { status = "تعذر تصدير PDF: ${e.message}" }
    }

    val kmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri: Uri? ->
        if (uri != null) try {
            val manualNetwork = if (importedKml == null && manholes.isNotEmpty()) DxfKmlImporter.convertManual(manholes, pipes) else null
            val lon = longitude.toDouble(); val lat = latitude.toDouble()
            require(importedKml != null || manualNetwork != null || (lon in -180.0..180.0 && lat in -90.0..90.0))
            val outputKml = importedKml ?: manualNetwork?.first ?: KmlPointWriter.point("ARKAN reference point", lon, lat)
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(outputKml) } }
            status = when {
                importedKml != null -> "تم تصدير شبكة DXF إلى KML."
                manualNetwork != null -> "تم تصدير ${manholes.size} منهل و${manualNetwork.second} مقطع أنبوب بإحداثيات UTM Zone 38N."
                else -> "تم حفظ نقطة KML المرجعية."
            }
        } catch (e: Exception) { status = "تعذر تصدير KML: ${e.message}" }
    }


    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00C8A0), background = Color(0xFF101418), surface = Color(0xFF1B2329))) {
        Column(
            Modifier.fillMaxSize()
                .background(Color(0xFF101418))
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text("ARKAN CAD PRO", color = Color(0xFF00C8A0), fontSize = 24.sp)
            Text("V1.10 • حساب المناهيل والميول تلقائياً", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { dxfPicker.launch(arrayOf("*/*")) }) { Text("استيراد DXF") }
                Button(onClick = { mode = "PIPE"; pipeStart = null }) { Text("رسم أنبوب") }
                Button(onClick = { showManholeDialog = true }) { Text("إضافة منهول") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    Button(onClick = { showPipeDiameterMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("قطر الأنبوب: ${pipeLayer.substringAfterLast("-")} مم")
                    }
                    DropdownMenu(expanded = showPipeDiameterMenu, onDismissRequest = { showPipeDiameterMenu = false }) {
                        listOf("250", "315", "400", "500", "600", "700").forEach { diameter ->
                            DropdownMenuItem(
                                text = { Text("$diameter مم") },
                                onClick = { pipeLayer = "PIPE-$diameter"; showPipeDiameterMenu = false; status = "تم اختيار قطر $diameter مم" }
                            )
                        }
                    }
                }
                Button(onClick = { lispPicker.launch(arrayOf("*/*")) }) { Text("اختيار LSP") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(trenchWidth, { trenchWidth = it }, label = { Text("عرض الخندق م") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(trenchDepth, { trenchDepth = it }, label = { Text("عمق الخندق م") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Text(if (mode == "PIPE") "المس نقطتين لرسم مقطع أنبوب." else "المس مساحة الرسم لتحديد موقع المنهول.", color = Color(0xFFB8C7CE), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(360.dp).background(Color(0xFF080B0E), RoundedCornerShape(8.dp)).pointerInput(mode, pendingManhole, pipeLayer, targetSlopePercent) {
                detectTapGestures { tap ->
                    when {
                        pendingManhole != null -> {
                            val mh = pendingManhole!!.copy(point = tap)
                            manholes = manholes + mh; mhId = "MH-%02d".format(Locale.US, manholes.size + 1); mhDepth = ""; mhNorthing = ""; mhEasting = ""; mhGroundLevel = ""; mhInvertLevel = ""; pendingManhole = null; status = "تمت إضافة ${mh.id} (${mh.type})"
                        }
                        mode == "PIPE" -> {
                            val first = pipeStart
                            if (first == null) { pipeStart = tap; status = "تم تحديد بداية الأنبوب؛ اختر نقطة النهاية." }
                            else {
                                val newPipe = Pipe(first, tap, pipeLayer.ifBlank { "SEWER" })
                                pipes = pipes + newPipe
                                pipeStart = null
                                val lengthM = hypot((tap.x-first.x).toDouble(), (tap.y-first.y).toDouble()) / PX_PER_METER
                                val startMh = nearestManhole(first, manholes)
                                val endMh = nearestManhole(tap, manholes)
                                val slope = targetSlopePercent.toDoubleOrNull()
                                if (startMh != null && endMh != null && startMh.id != endMh.id &&
                                    startMh.invertLevel != null && endMh.invertLevel == null && slope != null && slope > 0.0 && lengthM > 0.0) {
                                    val calculatedInv = startMh.invertLevel!! - lengthM * slope / 100.0
                                    manholes = manholes.map { mh ->
                                        if (mh.id == endMh.id) {
                                            val calculatedDepth = mh.groundLevel?.let { it - calculatedInv } ?: mh.depth
                                            val calculatedLength = if (calculatedDepth >= 1.70) 0.7318 else 1.10
                                            val calculatedWidth = if (calculatedDepth >= 1.70) 0.7318 else 0.70
                                            mh.copy(invertLevel = calculatedInv, depth = calculatedDepth, length = calculatedLength, width = calculatedWidth)
                                        } else mh
                                    }
                                    status = "طول الأنبوب: %.2f م | الميل: %.2f%% | تم حساب INV للمنهول %s = %.3f".format(Locale.US, lengthM, slope, endMh.id, calculatedInv)
                                } else {
                                    status = "طول المقطع: %.2f م".format(Locale.US, lengthM)
                                }
                            }
                        }
                    }
                }
            }) {
                for (x in 0..(size.width/PX_PER_METER).toInt()) drawLine(Color(0xFF263139), Offset(x*PX_PER_METER,0f), Offset(x*PX_PER_METER,size.height),1f)
                for (y in 0..(size.height/PX_PER_METER).toInt()) drawLine(Color(0xFF263139), Offset(0f,y*PX_PER_METER), Offset(size.width,y*PX_PER_METER),1f)
                pipes.forEach { pipe ->
                    drawLine(pipeLayerColor(pipe.layer), pipe.a, pipe.b, 5f)
                    drawCircle(Color.White, 4f, pipe.a)
                    drawCircle(Color.White, 4f, pipe.b)
                    connectedManholes(pipe, manholes)?.let { (startMh, endMh) ->
                        val downstream = if (startMh.invertLevel!! > endMh.invertLevel!!) pipe.b else pipe.a
                        val upstream = if (downstream == pipe.b) pipe.a else pipe.b
                        val dx = downstream.x - upstream.x
                        val dy = downstream.y - upstream.y
                        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                        val ux = dx / length
                        val uy = dy / length
                        val mid = Offset((pipe.a.x + pipe.b.x) / 2f, (pipe.a.y + pipe.b.y) / 2f)
                        val tip = Offset(mid.x + ux * 13f, mid.y + uy * 13f)
                        val back = Offset(mid.x - ux * 9f, mid.y - uy * 9f)
                        drawLine(pipeLayerColor(pipe.layer), back, tip, 3f)
                        drawLine(pipeLayerColor(pipe.layer), tip, Offset(tip.x - ux * 8f - uy * 6f, tip.y - uy * 8f + ux * 6f), 3f)
                        drawLine(pipeLayerColor(pipe.layer), tip, Offset(tip.x - ux * 8f + uy * 6f, tip.y - uy * 8f - ux * 6f), 3f)
                    }
                }
                pipeStart?.let { drawCircle(Color.Yellow,8f,it) }
                manholes.forEach { mh ->
                    if (mh.isCircular) {
                        val radius = (0.7318 * PX_PER_METER / 2).toFloat()
                        drawCircle(Color(0xFFFFC857), radius, mh.point, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                    } else {
                        val halfWidth = (0.70 * PX_PER_METER / 2).toFloat()
                        val halfLength = (1.10 * PX_PER_METER / 2).toFloat()
                        drawRect(Color(0xFFFFC857), Offset(mh.point.x-halfWidth,mh.point.y-halfLength), androidx.compose.ui.geometry.Size(halfWidth*2,halfLength*2), style=androidx.compose.ui.graphics.drawscope.Stroke(width=3f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("كل مربع = 1 متر (مقياس تجريبي) • $status", color=Color.White,fontSize=11.sp)
            Text("طول الأنابيب: %.2f م | المناهيل: %d | حفر المناهيل: %.2f م³".format(Locale.US,effectivePipeLength,if (importedKml != null) importedManholeCount else manholes.size,totalExcavation),color=Color(0xFF00C8A0),fontSize=12.sp)
            Text("حفر خنادق الأنابيب التقديري: %.2f م³".format(Locale.US,trenchVolume),color=Color(0xFF00C8A0),fontSize=12.sp)
            if (importedKml != null) {
                Text("أطوال الأنابيب حسب القطر الفعلي من وسوم DXF", color=Color.White, fontSize=13.sp)
                displayedPipeDiameters.forEach { diameter ->
                    val length = effectiveDiameterLengths[diameter] ?: 0.0
                    val sourceNote = if (length > 0.0) "" else " (لا يوجد وسم بهذا القطر)"
                    Text("$diameter مم : %.2f م%s".format(Locale.US,length,sourceNote), color=Color(0xFFB8C7CE), fontSize=11.sp)
                }
                Text(
                    if (diameterUnclassifiedLength >= 0.0)
                        "طول هندسة غير مربوط بقطر: %.2f م".format(Locale.US,diameterUnclassifiedLength)
                    else
                        "فرق الوسوم عن الهندسة: %.2f م".format(Locale.US,-diameterUnclassifiedLength),
                    color=Color(0xFFFFC857), fontSize=11.sp
                )
                Text("الطول الهندسي حسب طبقة DXF:", color=Color.White, fontSize=11.sp)
                effectiveLayerLengths.forEach { (layer, length) -> Text("$layer : %.2f م".format(Locale.US,length), color=Color(0xFFB8C7CE), fontSize=10.sp) }
            } else if (effectiveLayerLengths.isNotEmpty()) {
                Text("أطوال الأنابيب حسب Layer", color=Color.White, fontSize=13.sp)
                effectiveLayerLengths.forEach { (layer, length) -> Text("$layer : %.2f م".format(Locale.US,length), color=Color(0xFFB8C7CE), fontSize=11.sp) }
            }
            if (pipes.isNotEmpty()) {
                Text("بيانات مقاطع الأنابيب والميل", color = Color.White, fontSize = 14.sp)
                pipes.forEachIndexed { index, pipe ->
                    val lengthM = hypot((pipe.b.x - pipe.a.x).toDouble(), (pipe.b.y - pipe.a.y).toDouble()) / PX_PER_METER
                    val pair = connectedManholes(pipe, manholes)
                    if (pair != null && lengthM > 0.0) {
                        val (startMh, endMh) = pair
                        val delta = startMh.invertLevel!! - endMh.invertLevel!!
                        val slopePercent = kotlin.math.abs(delta) / lengthM * 100.0
                        val flow = if (delta > 0.0) "${startMh.id} ← ${endMh.id}" else "${startMh.id} → ${endMh.id}"
                        Text("مقطع ${index + 1} | ${pipe.layer} | طول %.2f م | ميل %.2f%% | اتجاه الجريان: %s".format(Locale.US, lengthM, slopePercent, flow), color = Color(0xFFB8C7CE), fontSize = 11.sp)
                    } else {
                        Text("مقطع ${index + 1} | ${pipe.layer} | طول %.2f م | اربط طرفي الأنبوب بمركزي منهولين لهما INV لإظهار الميل والسهم.".format(Locale.US, lengthM), color = Color(0xFFB8C7CE), fontSize = 11.sp)
                    }
                }
                Text("ألوان الأنابيب حسب القطر: 250 أحمر، 315 أزرق، 400 أخضر، 500 أصفر، 600 سماوي، 700 برتقالي.", color = Color.LightGray, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Text("إجمالي الأطوال حسب الطبقة", color = Color.White, fontSize = 13.sp)
                val drawnLayerTotals = pipes.groupBy { it.layer }.mapValues { entry ->
                    entry.value.sumOf { hypot((it.b.x - it.a.x).toDouble(), (it.b.y - it.a.y).toDouble()) / PX_PER_METER }
                }
                drawnLayerTotals.toSortedMap().forEach { (layer, length) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(layer, color = pipeLayerColor(layer), fontSize = 11.sp)
                        Text("%.2f م".format(Locale.US, length), color = Color.White, fontSize = 11.sp)
                    }
                }
                Text("المجموع الكلي: %.2f م".format(Locale.US, drawnLayerTotals.values.sum()), color = Color(0xFF00C8A0), fontSize = 12.sp)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick={xlsxSaver.launch("ARKAN_Quantity_Report.xlsx")}) { Text("تصدير Excel") }
                Button(onClick={pdfSaver.launch("ARKAN_Report.pdf")}) { Text("PDF") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick={showKmlDialog=true}) { Text("تصدير KML") }
                Button(
                    onClick = {
                        pipes = emptyList()
                        manholes = emptyList()
                        pipeStart = null
                        pendingManhole = null
                        importedKml = null
                        importedSummary = ""
                        importedLayerLengths = emptyMap()
                        importedManholeCount = 0
                        importedManholes = emptyList()
                        importedDiameterLengths = emptyMap()
                        importedUnclassifiedGeometryLength = 0.0
                        dxfFileName = "لم يتم اختيار DXF"
                        status = "تم مسح المشروع الحالي بالكامل، بما فيه بيانات DXF المستوردة."
                    }
                ) { Text("مسح المشروع") }
            }
            Text("ملف DXF: $dxfFileName",color=Color.LightGray,fontSize=10.sp)
            if (importedSummary.isNotBlank()) Text("العناصر المستوردة: $importedSummary",color=Color(0xFF00C8A0),fontSize=10.sp)
            Text("ملف LSP: $lispName",color=Color.LightGray,fontSize=10.sp)
            if(effectiveManholes.isNotEmpty()) {
                Text("جدول المناهيل",color=Color.White,fontSize=14.sp)
                if (importedKml != null) Text("أبعاد الحفر الافتراضية للمناهيل المستوردة: 1.10 × 1.10 م؛ العمق من نص h في DXF.",color=Color.LightGray,fontSize=10.sp)
                Column(Modifier.heightIn(max=150.dp).verticalScroll(rememberScrollState())) {
                    effectiveManholes.forEach { mh -> Text("${mh.id} | ${mh.type} | H ${mh.depth}م | GL ${mh.groundLevel ?: "-"} | INV ${mh.invertLevel ?: "-"} | N ${mh.northing} | E ${mh.easting} | حفر %.2fم³".format(Locale.US,mh.excavation),color=Color(0xFFB8C7CE),fontSize=11.sp) }
                }
            }
        }
    }

    if(showManholeDialog) AlertDialog(
        onDismissRequest={showManholeDialog=false}, title={Text("بيانات المنهول")},
        text={ Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(mhId,{mhId=it},label={Text("رقم المنهول")},singleLine=true)
            OutlinedTextField(mhGroundLevel,{mhGroundLevel=it},label={Text("GL / منسوب سطح الأرض")},singleLine=true)
            OutlinedTextField(mhInvertLevel,{mhInvertLevel=it},label={Text("INV / منسوب قاع الأنبوب (اتركه فارغاً للحساب التلقائي)")},singleLine=true)
            val previewDepth = mhGroundLevel.toDoubleOrNull()?.let { gl -> mhInvertLevel.toDoubleOrNull()?.let { inv -> gl - inv } } ?: 0.0
            val automaticType = when { previewDepth <= 0.0 -> "بانتظار INV"; previewDepth <= 1.70 -> "AS"; previewDepth <= 3.00 -> "BS"; previewDepth <= 4.00 -> "CS"; else -> "CD" }
            OutlinedTextField(value = automaticType, onValueChange = {}, label = { Text("نوع المنهولة (تلقائي)") }, modifier = Modifier.fillMaxWidth(), readOnly = true, singleLine = true)
            OutlinedTextField(targetSlopePercent, { targetSlopePercent = it }, label = { Text("نسبة الميول (%)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("أدخل نسبة الميل هنا، مثلاً 0.50 أو 1.00. يستخدمها البرنامج لحساب INV للمنهول التالي تلقائياً حسب طول الأنبوب.", color = Color.LightGray, fontSize = 11.sp)
            Text("أدخل GL لكل منهول. أدخل INV للمنهول الأول؛ ويمكن ترك INV للمناهيل التالية فارغاً ليحسبه البرنامج من نسبة الميل أعلاه. نوع المنهول يُحدد تلقائياً ولا يحتاج اختياراً يدوياً.", color = Color.LightGray, fontSize = 11.sp)
        }},
        confirmButton={ TextButton(onClick={
            try {
                val gl = mhGroundLevel.toDoubleOrNull()
                val inv = mhInvertLevel.toDoubleOrNull()
                require(gl != null) { "أدخل منسوب سطح الأرض GL." }
                require(mhInvertLevel.isBlank() || inv != null) { "قيمة INV غير صحيحة." }
                val d = if (inv != null) gl - inv else 0.0
                val n=0.0; val e=0.0
                val id=mhId.trim().ifBlank { "MH-%02d".format(Locale.US, manholes.size + 1) }
                require(d >= 0.0) { "تحقق من GL وINV؛ يجب أن يكون GL أعلى من INV." }
                require(id !in manholes.map { it.id }) { "رقم المنهول مستخدم مسبقاً." }
                val length = if (d >= 1.70) 0.7318 else 1.10
                val width = if (d >= 1.70) 0.7318 else 0.70
                pendingManhole=Manhole(id,Offset.Zero,d,length,width,n,e,groundLevel=gl,invertLevel=inv,coordinatesKnown=false,manualType=null)
                mode="MANHOLE";showManholeDialog=false;status="المس مساحة الرسم لتحديد موقع المنهول."
            } catch(e:Exception) { status=e.message ?: "تحقق من GL وINV ورقم المنهول." }
        }) { Text("التالي: تحديد الموقع") }},
        dismissButton={TextButton(onClick={showManholeDialog=false}) { Text("إلغاء") }}
    )

    if(showKmlDialog) AlertDialog(
        onDismissRequest={showKmlDialog=false},title={Text(if (importedKml != null || manholes.isNotEmpty()) "تصدير شبكة المجاري KML" else "نقطة مرجعية KML / WGS84")},
        text={Column {
            if (importedKml != null) Text("سيتم تصدير هندسة DXF المستوردة. $importedSummary\nالتحويل يفترض WGS84 / UTM Zone 38N.")
            else if (manholes.isNotEmpty()) Text("سيتم تحويل إحداثيات N وE للمناهيل إلى WGS84 وتصدير نقاطها، وربط الأنبوب إذا كانت نهايتاه قريبتين من مركزي منهولين. النظام المفترض UTM Zone 38N.")
            else {
                OutlinedTextField(longitude,{longitude=it},label={Text("Longitude")})
                OutlinedTextField(latitude,{latitude=it},label={Text("Latitude")})
                Text("أدخل إحداثيات WGS84 لنقطة مرجعية.")
            }
        }},
        confirmButton={TextButton(onClick={showKmlDialog=false;kmlSaver.launch(if (importedKml != null || manholes.isNotEmpty()) "ARKAN_SEWER_NETWORK.kml" else "ARKAN_reference.kml")}) { Text("حفظ KML") }},
        dismissButton={TextButton(onClick={showKmlDialog=false}) { Text("إلغاء") }}
    )
}


private data class ArkanXlsxCell(
    val text: String,
    val number: Double? = null,
    val style: Int = 0
)

private object ArkanXlsxWriter {
    fun write(
        output: OutputStream,
        totalPipeLength: Double,
        manholeCount: Int,
        manholeExcavation: Double,
        trenchWidth: Double,
        trenchDepth: Double,
        trenchExcavation: Double,
        layerLengths: Map<String, Double>,
        diameterLengths: Map<Int, Double>,
        unclassifiedGeometryLength: Double,
        manholes: List<Manhole>
    ) {
        val summaryRows = mutableListOf<List<ArkanXlsxCell>>()
        summaryRows += listOf(ArkanXlsxCell("ARKAN CAD PRO - تقرير الكميات", style = 1))
        summaryRows += listOf(
            ArkanXlsxCell("البيان", style = 2),
            ArkanXlsxCell("القيمة", style = 2),
            ArkanXlsxCell("الوحدة", style = 2)
        )
        fun summary(label: String, value: Double, unit: String) {
            summaryRows += listOf(ArkanXlsxCell(label), ArkanXlsxCell("", value, 3), ArkanXlsxCell(unit))
        }
        summary("الطول الكلي للأنابيب", totalPipeLength, "م")
        summary("عدد المناهيل", manholeCount.toDouble(), "منهول")
        summary("حجم حفر المناهيل", manholeExcavation, "م³")
        summary("عرض خندق الأنابيب", trenchWidth, "م")
        summary("عمق خندق الأنابيب", trenchDepth, "م")
        summary("حجم حفر خنادق الأنابيب", trenchExcavation, "م³")
        summaryRows += listOf(ArkanXlsxCell("ملاحظة: أبعاد حفر المناهيل المستوردة افتراضية 1.10 × 1.10 م، والعمق مستخرج من DXF."))

        val layerRows = mutableListOf<List<ArkanXlsxCell>>()
        layerRows += listOf(ArkanXlsxCell("أطوال الأنابيب حسب القطر الفعلي", style = 1))
        layerRows += listOf(
            ArkanXlsxCell("القطر (مم)", style = 2),
            ArkanXlsxCell("الطول المعلن (م)", style = 2),
            ArkanXlsxCell("مصدر البيانات", style = 2)
        )
        val standardDiameters = listOf(250, 315, 400, 500, 600, 700)
        val shownDiameters = (standardDiameters + diameterLengths.keys).distinct().sorted()
        shownDiameters.forEach { diameter ->
            val value = diameterLengths[diameter]
            layerRows += listOf(
                ArkanXlsxCell(diameter.toString()),
                ArkanXlsxCell("", value ?: 0.0, 3),
                ArkanXlsxCell(if (value != null && value > 0.0) "مذكور صراحة في DXF" else "لا يوجد وسم بهذا القطر")
            )
        }
        layerRows += listOf(
            ArkanXlsxCell(if (unclassifiedGeometryLength >= 0.0) "هندسة مسارات غير مرتبطة بقطر" else "فرق: الوسوم أكبر من طول الهندسة"),
            ArkanXlsxCell("", kotlin.math.abs(unclassifiedGeometryLength), 3),
            ArkanXlsxCell("فرق حسابي بين الهندسة ووسوم الأطوال")
        )
        layerRows += listOf(
            ArkanXlsxCell("مجموع الأطوال المعلنة حسب القطر"),
            ArkanXlsxCell("", diameterLengths.values.sum(), 3),
            ArkanXlsxCell("من أبعاد وأطوال النصوص في DXF")
        )
        layerRows += listOf(ArkanXlsxCell(""))
        layerRows += listOf(ArkanXlsxCell("أطوال هندسة المسارات حسب Layer", style = 1))
        layerRows += listOf(ArkanXlsxCell("اسم الطبقة", style = 2), ArkanXlsxCell("الطول الهندسي (م)", style = 2))
        layerLengths.toSortedMap().forEach { (layer, length) ->
            layerRows += listOf(ArkanXlsxCell(layer), ArkanXlsxCell("", length, 3))
        }
        if (layerLengths.isEmpty()) {
            layerRows += listOf(ArkanXlsxCell("لا توجد أطوال هندسية"), ArkanXlsxCell("", 0.0, 3))
        }

        val manholeRows = mutableListOf<List<ArkanXlsxCell>>()
        manholeRows += listOf(ArkanXlsxCell("جدول المناهيل", style = 1))
        manholeRows += listOf(
            ArkanXlsxCell("رقم المنهول", style = 2),
            ArkanXlsxCell("التصنيف", style = 2),
            ArkanXlsxCell("العمق H (م)", style = 2),
            ArkanXlsxCell("GL (م)", style = 2),
            ArkanXlsxCell("INV (م)", style = 2),
            ArkanXlsxCell("الطول (م)", style = 2),
            ArkanXlsxCell("العرض (م)", style = 2),
            ArkanXlsxCell("N / Northing", style = 2),
            ArkanXlsxCell("E / Easting", style = 2),
            ArkanXlsxCell("حجم الحفر (م³)", style = 2)
        )
        manholes.forEach { mh ->
            manholeRows += listOf(
                ArkanXlsxCell(mh.id),
                ArkanXlsxCell(mh.type),
                ArkanXlsxCell("", mh.depth, 3),
                ArkanXlsxCell("", mh.groundLevel, 3),
                ArkanXlsxCell("", mh.invertLevel, 3),
                ArkanXlsxCell("", mh.length, 3),
                ArkanXlsxCell("", mh.width, 3),
                ArkanXlsxCell("", mh.northing, 3),
                ArkanXlsxCell("", mh.easting, 3),
                ArkanXlsxCell("", mh.excavation, 3)
            )
        }
        manholeRows += listOf(
            ArkanXlsxCell("المجموع"),
            ArkanXlsxCell(""),
            ArkanXlsxCell(""),
            ArkanXlsxCell(""),
            ArkanXlsxCell(""),
            ArkanXlsxCell(""),
            ArkanXlsxCell(""),
            ArkanXlsxCell(""),
            ArkanXlsxCell("", manholes.sumOf { it.excavation }, 3)
        )

        ZipOutputStream(output).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes())
            put(zip, "_rels/.rels", rootRelationships())
            put(zip, "xl/workbook.xml", workbook())
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships())
            put(zip, "xl/styles.xml", styles())
            put(zip, "xl/worksheets/sheet1.xml", sheet(summaryRows, listOf(38.0, 18.0, 14.0)))
            put(zip, "xl/worksheets/sheet2.xml", sheet(layerRows, listOf(34.0, 22.0, 34.0)))
            put(zip, "xl/worksheets/sheet3.xml", sheet(manholeRows, listOf(18.0, 14.0, 15.0, 15.0, 15.0, 15.0, 15.0, 22.0, 22.0, 20.0)))
            zip.finish()
        }
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun rootRelationships() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<bookViews><workbookView/></bookViews>
<sheets>
<sheet name="ملخص" sheetId="1" r:id="rId1"/>
<sheet name="أطوال الأنابيب" sheetId="2" r:id="rId2"/>
<sheet name="المناهيل" sheetId="3" r:id="rId3"/>
</sheets>
</workbook>"""

    private fun workbookRelationships() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
<Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.000"/></numFmts>
<fonts count="3">
<font><sz val="11"/><color rgb="FF202020"/><name val="Calibri"/></font>
<font><b/><sz val="16"/><color rgb="FF087A67"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
</fonts>
<fills count="4">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF00A98B"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFE6F5F1"/><bgColor indexed="64"/></patternFill></fill>
</fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color rgb="FFB7DCD3"/></left><right style="thin"><color rgb="FFB7DCD3"/></right><top style="thin"><color rgb="FFB7DCD3"/></top><bottom style="thin"><color rgb="FFB7DCD3"/></bottom><diagonal/></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
<xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"><alignment horizontal="right"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private fun sheet(rows: List<List<ArkanXlsxCell>>, widths: List<Double>): String {
        val maxColumns = maxOf(widths.size, rows.maxOfOrNull { it.size } ?: 1)
        val maxColRef = columnName(maxColumns)
        val rowXml = rows.mapIndexed { rowIndex, cells ->
            val rowNumber = rowIndex + 1
            val height = if (rowIndex == 0) """ ht="30" customHeight="1"""" else ""
            val cellXml = cells.mapIndexed { colIndex, cell ->
                val ref = columnName(colIndex + 1) + rowNumber
                if (cell.number != null) {
                    """<c r="$ref" s="${cell.style}"><v>${String.format(Locale.US, "%.3f", cell.number)}</v></c>"""
                } else {
                    val style = if (cell.style > 0) """ s="${cell.style}"""" else ""
                    """<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${escape(cell.text)}</t></is></c>"""
                }
            }.joinToString("")
            """<row r="$rowNumber"$height>$cellXml</row>"""
        }.joinToString("")
        val colsXml = widths.mapIndexed { index, width ->
            """<col min="${index + 1}" max="${index + 1}" width="${String.format(Locale.US, "%.1f", width)}" customWidth="1"/>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0" showGridLines="0" rightToLeft="1"/></sheetViews>
<sheetFormatPr defaultRowHeight="22"/>
<cols>$colsXml</cols>
<sheetData>$rowXml</sheetData>
<autoFilter ref="A2:${maxColRef}${rows.size}"/>
</worksheet>"""
    }

    private fun columnName(index: Int): String {
        var n = index
        val result = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            result.insert(0, ('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return result.toString()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}


private object DxfKmlImporter {
    data class ImportResult(
        val kml: String,
        val summary: String,
        val layerLengths: Map<String, Double>,
        val manholeCount: Int,
        val manholes: List<Manhole>,
        val diameterLengths: Map<Int, Double>,
        val unclassifiedGeometryLength: Double
    )
    private data class PairCode(val code: Int, val value: String)
    private data class GeoPoint(val lon: Double, val lat: Double, val alt: Double)
    private data class DxfPosition(val east: Double, val north: Double, val alt: Double)
    private data class DxfLabel(val east: Double, val north: Double, val text: String)
    fun convertManual(manholes: List<Manhole>, pipes: List<Pipe>): Pair<String, Int> {
        require(manholes.isNotEmpty()) { "لا توجد مناهيل للتصدير" }
        val placemarks = StringBuilder()
        manholes.forEach { mh ->
            val geo = utmToWgs84(mh.easting, mh.northing, mh.altitude)
            placemarks.append("<Placemark><name>${xmlEscape(mh.id)}</name><Point><coordinates>${fmt(geo.lon)},${fmt(geo.lat)},${fmt(geo.alt)}</coordinates></Point></Placemark>")
        }
        var exportedPipes = 0
        val maxSnapPixels = PX_PER_METER * 3.0
        pipes.forEachIndexed { index, pipe ->
            val start = manholes.minByOrNull { hypot((it.point.x - pipe.a.x).toDouble(), (it.point.y - pipe.a.y).toDouble()) }
            val end = manholes.minByOrNull { hypot((it.point.x - pipe.b.x).toDouble(), (it.point.y - pipe.b.y).toDouble()) }
            if (start != null && end != null && start.id != end.id) {
                val startDistance = hypot((start.point.x - pipe.a.x).toDouble(), (start.point.y - pipe.a.y).toDouble())
                val endDistance = hypot((end.point.x - pipe.b.x).toDouble(), (end.point.y - pipe.b.y).toDouble())
                if (startDistance <= maxSnapPixels && endDistance <= maxSnapPixels) {
                    val a = utmToWgs84(start.easting, start.northing, start.altitude)
                    val b = utmToWgs84(end.easting, end.northing, end.altitude)
                    val name = xmlEscape("${pipe.layer} - Pipe ${index + 1}")
                    placemarks.append("<Placemark><name>$name</name><Style><LineStyle><color>ff00ffff</color><width>3</width></LineStyle></Style><LineString><tessellate>1</tessellate><coordinates>${fmt(a.lon)},${fmt(a.lat)},${fmt(a.alt)} ${fmt(b.lon)},${fmt(b.lat)},${fmt(b.alt)}</coordinates></LineString></Placemark>")
                    exportedPipes++
                }
            }
        }
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>ARKAN Manual Sewer Network - UTM 38N</name>$placemarks</Document></kml>"""
        return kml to exportedPipes
    }

    fun convert(raw: String): ImportResult {
        val lines = raw.replace("\r", "").lines()
        val pairs = mutableListOf<PairCode>()
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull()
            if (code != null) pairs.add(PairCode(code, lines[i + 1].trim()))
            i += 2
        }
        val start = pairs.indexOfFirst { it.code == 2 && it.value.equals("ENTITIES", true) }
        require(start >= 0) { "قسم ENTITIES غير موجود" }
        val entities = mutableListOf<List<PairCode>>()
        var current = mutableListOf<PairCode>()
        for (p in pairs.drop(start + 1)) {
            if (p.code == 0) {
                if (current.isNotEmpty()) entities.add(current)
                current = mutableListOf(p)
                if (p.value == "ENDSEC") break
            } else current.add(p)
        }
        if (current.isNotEmpty() && current.firstOrNull()?.value != "ENDSEC") entities.add(current)
        val placemarks = StringBuilder()
        var pipeCount = 0
        val layerLengths = mutableMapOf<String, Double>()
        val structurePositions = entities.filter { it.firstOrNull()?.value == "INSERT" && it.firstOrNull { p -> p.code == 8 }?.value == "C-SSWR-STRC" }.mapNotNull { e ->
            val east = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val north = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val alt = e.firstOrNull { it.code == 30 }?.value?.toDoubleOrNull() ?: 0.0
            DxfPosition(east, north, alt)
        }
        val labels = entities.filter { e ->
            val type = e.firstOrNull()?.value
            (type == "TEXT" || type == "MTEXT") && e.firstOrNull { it.code == 8 }?.value == "C-STRM-TEXT"
        }.mapNotNull { e ->
            val east = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val north = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val labelText = e.firstOrNull { it.code == 1 }?.value ?: e.firstOrNull { it.code == 3 }?.value ?: return@mapNotNull null
            DxfLabel(east, north, labelText)
        }
        val pipeLabelPattern = Regex("""(?i)(\d+(?:\.\d+)?)\s*m\s+of\s+(\d+)\s*mm\b.*\bpipe\b""")
        val diameterLengths = mutableMapOf<Int, Double>()
        labels.forEach { label ->
            val match = pipeLabelPattern.find(label.text) ?: return@forEach
            val length = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val diameter = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (length > 0.0 && diameter > 0) {
                diameterLengths[diameter] = (diameterLengths[diameter] ?: 0.0) + length
            }
        }
        val importedManholes = structurePositions.mapIndexed { index, pos ->
            val nearestStructure = labels.filter { it.text.contains("STRUCTURE", true) }
                .minByOrNull { hypot(it.east - pos.east, it.north - pos.north) }
            val nearestDepth = labels.filter { Regex("""(?i)h\s*:\s*[-+]?\d+(?:\.\d+)?""").containsMatchIn(it.text) }
                .minByOrNull { hypot(it.east - pos.east, it.north - pos.north) }
            val extractedId = nearestStructure?.text?.let { Regex("""\(([^)]+)\)""").find(it)?.groupValues?.getOrNull(1) }
            val extractedDepth = nearestDepth?.text?.let { Regex("""(?i)h\s*:\s*([-+]?\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() } ?: 0.0
            Manhole(extractedId?.let { "MH-$it" } ?: "Manhole ${index + 1}", Offset.Zero, extractedDepth, 1.10, 1.10, pos.north, pos.east, pos.alt)
        }
        importedManholes.forEach { mh ->
            val geo = utmToWgs84(mh.easting, mh.northing, mh.altitude)
            val safeName = xmlEscape(mh.id)
            placemarks.append("<Placemark><name>$safeName</name><Point><coordinates>${fmt(geo.lon)},${fmt(geo.lat)},${fmt(geo.alt)}</coordinates></Point></Placemark>")
        }
        val mhCount = importedManholes.size
        for (e in entities) {
            val type = e.firstOrNull()?.value ?: continue
            val layer = e.firstOrNull { it.code == 8 }?.value ?: ""
            if (type == "LWPOLYLINE" && layer == "C-SSWR-CNTR") {
                val pts = mutableListOf<Pair<Double, Double>>()
                var x: Double? = null
                for (p in e.drop(1)) {
                    if (p.code == 10) x = p.value.toDoubleOrNull()
                    if (p.code == 20 && x != null) {
                        val y = p.value.toDoubleOrNull()
                        if (y != null) pts.add(x!! to y)
                        x = null
                    }
                }
                if (pts.size >= 2) {
                    val length = pts.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
                    layerLengths[layer] = (layerLengths[layer] ?: 0.0) + length
                    val coords = pts.joinToString(" ") { (east, north) ->
                        val g = utmToWgs84(east, north, 0.0)
                        "${fmt(g.lon)},${fmt(g.lat)},${fmt(g.alt)}"
                    }
                    pipeCount++
                    placemarks.append("<Placemark><name>Sewer pipe $pipeCount</name><Style><LineStyle><color>ff00ffff</color><width>3</width></LineStyle></Style><LineString><tessellate>1</tessellate><coordinates>$coords</coordinates></LineString></Placemark>")
                }
            }
        }
        require(pipeCount > 0 || mhCount > 0) { "لم يتم العثور على خطوط أو مناهيل في الطبقات المحددة" }
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>ARKAN Sewer Network</name>$placemarks</Document></kml>"""
        val geometricCenterlineLength = layerLengths.values.sum()
        val labelledDiameterLength = diameterLengths.values.sum()
        val unclassifiedGeometryLength = geometricCenterlineLength - labelledDiameterLength
        return ImportResult(
            kml,
            "خطوط: $pipeCount | مناهيل: $mhCount | أقطار موسومة: ${diameterLengths.size}",
            layerLengths,
            mhCount,
            importedManholes,
            diameterLengths,
            unclassifiedGeometryLength
        )
    }
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun fmt(v: Double) = String.format(Locale.US, "%.8f", v)
    private fun utmToWgs84(easting: Double, northing: Double, altitude: Double): GeoPoint {
        val a = 6378137.0
        val ecc = 0.00669438
        val k0 = 0.9996
        val x = easting - 500000.0
        val y = northing
        val m = y / k0
        val mu = m / (a * (1 - ecc / 4 - 3 * ecc * ecc / 64 - 5 * ecc * ecc * ecc / 256))
        val e1 = (1 - kotlin.math.sqrt(1 - ecc)) / (1 + kotlin.math.sqrt(1 - ecc))
        val j1 = 3 * e1 / 2 - 27 * e1.pow(3) / 32
        val j2 = 21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32
        val j3 = 151 * e1.pow(3) / 96
        val j4 = 1097 * e1.pow(4) / 512
        val fp = mu + j1 * kotlin.math.sin(2 * mu) + j2 * kotlin.math.sin(4 * mu) + j3 * kotlin.math.sin(6 * mu) + j4 * kotlin.math.sin(8 * mu)
        val ep2 = ecc / (1 - ecc)
        val c1 = ep2 * kotlin.math.cos(fp).pow(2)
        val t1 = kotlin.math.tan(fp).pow(2)
        val n1 = a / kotlin.math.sqrt(1 - ecc * kotlin.math.sin(fp).pow(2))
        val r1 = a * (1 - ecc) / (1 - ecc * kotlin.math.sin(fp).pow(2)).pow(1.5)
        val d = x / (n1 * k0)
        val q1 = n1 * kotlin.math.tan(fp) / r1
        val q2 = d * d / 2
        val q3 = (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24
        val q4 = (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
        val lat = fp - q1 * (q2 - q3 + q4)
        val q5 = d
        val q6 = (1 + 2 * t1 + c1) * d.pow(3) / 6
        val q7 = (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120
        val lon = Math.toRadians(45.0) + (q5 - q6 + q7) / kotlin.math.cos(fp)
        return GeoPoint(Math.toDegrees(lon), Math.toDegrees(lat), altitude)
    }
    private fun Double.pow(n: Int): Double = this.pow(n.toDouble())
}

private object KmlPointWriter {
    fun point(name:String,longitude:Double,latitude:Double):String {
        val safe=name.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>$safe</name><Point><coordinates>${String.format(Locale.US,"%.8f,%.8f,0",longitude,latitude)}</coordinates></Point></Placemark></Document></kml>
"""
    }
}