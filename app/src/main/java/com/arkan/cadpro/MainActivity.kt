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
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.pow

private const val PX_PER_METER = 40f
private data class Pipe(val a: Offset, val b: Offset, val layer: String)
private data class Manhole(val id: String, val point: Offset, val depth: Double, val length: Double, val width: Double, val northing: Double, val easting: Double, val altitude: Double = 0.0) {
    val type: String get() = when { depth <= 0.0 -> "غير معروف"; depth <= 1.70 -> "AS"; depth <= 3.00 -> "BS"; depth <= 4.00 -> "CD"; else -> "خارج التصنيف" }
    val excavation: Double get() = length * width * depth
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
    var pipeLayer by remember { mutableStateOf("SEWER") }
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
    var longitude by remember { mutableStateOf("46.257") }
    var latitude by remember { mutableStateOf("31.057") }
    var mhId by remember { mutableStateOf("MH-01") }
    var mhDepth by remember { mutableStateOf("") }
    var mhLength by remember { mutableStateOf("1.10") }
    var mhWidth by remember { mutableStateOf("1.10") }
    var mhNorthing by remember { mutableStateOf("") }
    var mhEasting by remember { mutableStateOf("") }
    var pendingManhole by remember { mutableStateOf<Manhole?>(null) }
    val context = LocalContext.current

    val totalLength = pipes.sumOf { hypot((it.b.x-it.a.x).toDouble(), (it.b.y-it.a.y).toDouble()) / PX_PER_METER }
    val effectiveManholes = if (importedKml != null) importedManholes else manholes
    val totalExcavation = effectiveManholes.sumOf { it.excavation }
    val layerLengths = pipes.groupBy { it.layer }.mapValues { entry -> entry.value.sumOf { hypot((it.b.x-it.a.x).toDouble(), (it.b.y-it.a.y).toDouble()) / PX_PER_METER } }
    val effectiveLayerLengths = if (importedKml != null) importedLayerLengths else layerLengths
    val effectivePipeLength = if (importedKml != null) effectiveLayerLengths.values.sum() else totalLength
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
            rows.add("Pipe lengths by Layer,,")
            effectiveLayerLengths.forEach { (layer,length) -> rows.add("$layer,${String.format(Locale.US,"%.2f",length)},m") }
            rows.add("")
            rows.add("Manhole ID,Type,Depth m,Length m,Width m,N,E,Excavation m3")
            effectiveManholes.forEach { mh -> rows.add("${mh.id},${mh.type},${mh.depth},${mh.length},${mh.width},${mh.northing},${mh.easting},${String.format(Locale.US,"%.2f",mh.excavation)}") }
            context.contentResolver.openOutputStream(uri)?.use { it.write(("\uFEFF" + rows.joinToString("\r\n")).toByteArray(Charsets.UTF_8)) }
            status = "تم تصدير جدول الكميات بصيغة CSV المتوافقة مع Excel."
        } catch (e: Exception) { status = "تعذر تصدير جدول Excel: ${e.message}" }
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
            lines.add("Pipe lengths by Layer:")
            effectiveLayerLengths.forEach { (layer,length) -> lines.add("$layer : %.2f m".format(Locale.US,length)) }
            lines.add("")
            lines.add("Manholes: ID | Type | Depth | Length | Width | N | E | Excavation")
            effectiveManholes.forEach { mh -> lines.add("${mh.id} | ${mh.type} | ${mh.depth} | ${mh.length} | ${mh.width} | ${mh.northing} | ${mh.easting} | %.2f".format(Locale.US,mh.excavation)) }
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
            val lon = longitude.toDouble(); val lat = latitude.toDouble()
            require(lon in -180.0..180.0 && lat in -90.0..90.0)
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(importedKml ?: KmlPointWriter.point("ARKAN reference point", lon, lat)) } }
            status = if (importedKml != null) "تم تصدير شبكة المجاري المستوردة إلى KML." else "تم حفظ نقطة KML المرجعية."
        } catch (_: Exception) { status = "إحداثيات WGS84 غير صحيحة." }
    }


    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00C8A0), background = Color(0xFF101418), surface = Color(0xFF1B2329))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(10.dp)) {
            Text("ARKAN CAD PRO", color = Color(0xFF00C8A0), fontSize = 24.sp)
            Text("V1.4 • DXF workflow", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { dxfPicker.launch(arrayOf("*/*")) }) { Text("استيراد DXF") }
                Button(onClick = { mode = "PIPE"; pipeStart = null }) { Text("رسم أنبوب") }
                Button(onClick = { showManholeDialog = true }) { Text("إضافة منهول") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(pipeLayer, { pipeLayer = it }, label = { Text("Layer الأنبوب") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { lispPicker.launch(arrayOf("*/*")) }) { Text("LOAD LSP") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(trenchWidth, { trenchWidth = it }, label = { Text("عرض الخندق م") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(trenchDepth, { trenchDepth = it }, label = { Text("عمق الخندق م") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Text(if (mode == "PIPE") "المس نقطتين لرسم مقطع أنبوب." else "المس مساحة الرسم لتحديد موقع المنهول.", color = Color(0xFFB8C7CE), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080B0E), RoundedCornerShape(8.dp)).pointerInput(mode, pendingManhole, pipeLayer) {
                detectTapGestures { tap ->
                    when {
                        pendingManhole != null -> {
                            val mh = pendingManhole!!.copy(point = tap)
                            manholes = manholes + mh; mhId = "MH-%02d".format(Locale.US, manholes.size + 1); mhDepth = ""; mhNorthing = ""; mhEasting = ""; pendingManhole = null; status = "تمت إضافة ${mh.id} (${mh.type})"
                        }
                        mode == "PIPE" -> {
                            val first = pipeStart
                            if (first == null) { pipeStart = tap; status = "تم تحديد بداية الأنبوب؛ اختر نقطة النهاية." }
                            else {
                                pipes = pipes + Pipe(first, tap, pipeLayer.ifBlank { "SEWER" }); pipeStart = null
                                status = "طول المقطع: %.2f م".format(Locale.US, hypot((tap.x-first.x).toDouble(), (tap.y-first.y).toDouble()) / PX_PER_METER)
                            }
                        }
                    }
                }
            }) {
                for (x in 0..(size.width/PX_PER_METER).toInt()) drawLine(Color(0xFF263139), Offset(x*PX_PER_METER,0f), Offset(x*PX_PER_METER,size.height),1f)
                for (y in 0..(size.height/PX_PER_METER).toInt()) drawLine(Color(0xFF263139), Offset(0f,y*PX_PER_METER), Offset(size.width,y*PX_PER_METER),1f)
                pipes.forEach { drawLine(Color(0xFF00C8A0),it.a,it.b,5f); drawCircle(Color.White,4f,it.a); drawCircle(Color.White,4f,it.b) }
                pipeStart?.let { drawCircle(Color.Yellow,8f,it) }
                manholes.forEach { mh ->
                    val hw=(mh.width*PX_PER_METER/2).toFloat(); val hl=(mh.length*PX_PER_METER/2).toFloat()
                    drawRect(Color(0xFFFFC857),Offset(mh.point.x-hw,mh.point.y-hl),androidx.compose.ui.geometry.Size(hw*2,hl*2),style=androidx.compose.ui.graphics.drawscope.Stroke(width=3f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("كل مربع = 1 متر (مقياس تجريبي) • $status", color=Color.White,fontSize=11.sp)
            Text("طول الأنابيب: %.2f م | المناهيل: %d | حفر المناهيل: %.2f م³".format(Locale.US,effectivePipeLength,if (importedKml != null) importedManholeCount else manholes.size,totalExcavation),color=Color(0xFF00C8A0),fontSize=12.sp)
            Text("حفر خنادق الأنابيب التقديري: %.2f م³".format(Locale.US,trenchVolume),color=Color(0xFF00C8A0),fontSize=12.sp)
            if (effectiveLayerLengths.isNotEmpty()) { Text("أطوال الأنابيب حسب Layer", color=Color.White, fontSize=13.sp); effectiveLayerLengths.forEach { (layer, length) -> Text("$"+"layer : %.2f م".format(Locale.US,length), color=Color(0xFFB8C7CE), fontSize=11.sp) } }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick={csvSaver.launch("ARKAN_Quantities.csv")}) { Text("EXCEL / CSV") }
                Button(onClick={pdfSaver.launch("ARKAN_Report.pdf")}) { Text("PDF") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick={showKmlDialog=true}) { Text("EXPORT KML") }
                Button(onClick={pipes=emptyList();manholes=emptyList();pipeStart=null;pendingManhole=null;status="تم مسح الرسم."}) { Text("CLEAR") }
            }
            Text("ملف DXF: $dxfFileName",color=Color.LightGray,fontSize=10.sp)
            if (importedSummary.isNotBlank()) Text("العناصر المستوردة: $importedSummary",color=Color(0xFF00C8A0),fontSize=10.sp)
            Text("ملف LSP: $lispName",color=Color.LightGray,fontSize=10.sp)
            if(effectiveManholes.isNotEmpty()) {
                Text("جدول المناهيل",color=Color.White,fontSize=14.sp)
                if (importedKml != null) Text("أبعاد الحفر الافتراضية للمناهيل المستوردة: 1.10 × 1.10 م؛ العمق من نص h في DXF.",color=Color.LightGray,fontSize=10.sp)
                Column(Modifier.heightIn(max=150.dp).verticalScroll(rememberScrollState())) {
                    effectiveManholes.forEach { mh -> Text("${mh.id} | ${mh.type} | عمق ${mh.depth}م | N ${mh.northing} | E ${mh.easting} | حفر %.2fم³".format(Locale.US,mh.excavation),color=Color(0xFFB8C7CE),fontSize=11.sp) }
                }
            }
        }
    }

    if(showManholeDialog) AlertDialog(
        onDismissRequest={showManholeDialog=false}, title={Text("بيانات المنهول")},
        text={ Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(mhId,{mhId=it},label={Text("رقم المنهول")},singleLine=true)
            OutlinedTextField(mhDepth,{mhDepth=it},label={Text("العمق (م)")},singleLine=true)
            OutlinedTextField(mhLength,{mhLength=it},label={Text("الطول (م)")},singleLine=true)
            OutlinedTextField(mhWidth,{mhWidth=it},label={Text("العرض (م)")},singleLine=true)
            OutlinedTextField(mhNorthing,{mhNorthing=it},label={Text("N / Northing")},singleLine=true)
            OutlinedTextField(mhEasting,{mhEasting=it},label={Text("E / Easting")},singleLine=true)
            Text("يجب إدخال العمق وإحداثيي N وE. التصنيف: AS حتى 1.70م، BS حتى 3.00م، CD حتى 4.00م.")
        }},
        confirmButton={ TextButton(onClick={
            try {
                val d=mhDepth.toDouble(); val l=mhLength.toDouble(); val w=mhWidth.toDouble()
                val n=mhNorthing.toDouble(); val e=mhEasting.toDouble()
                val id=mhId.trim().ifBlank { "MH-%02d".format(Locale.US, manholes.size + 1) }
                require(d>0 && d<=4.0 && l>0 && w>0)
                require(id !in manholes.map { it.id }) { "رقم المنهول مستخدم مسبقاً." }
                pendingManhole=Manhole(id,Offset.Zero,d,l,w,n,e)
                mode="MANHOLE";showManholeDialog=false;status="المس مساحة الرسم لتحديد موقع المنهول."
            } catch(_:Exception) { status="أدخل العمق والطول والعرض وإحداثيي N وE بأرقام صحيحة؛ العمق حتى 4.00م، ولا تكرر رقم المنهول." }
        }) { Text("التالي: تحديد الموقع") }},
        dismissButton={TextButton(onClick={showManholeDialog=false}) { Text("إلغاء") }}
    )

    if(showKmlDialog) AlertDialog(
        onDismissRequest={showKmlDialog=false},title={Text(if (importedKml != null) "تصدير شبكة المجاري KML" else "نقطة مرجعية KML / WGS84")},
        text={Column {
            if (importedKml != null) Text("سيتم تصدير خطوط الأنابيب والمناهيل من ملف DXF إلى KML. $importedSummary\nالتحويل يفترض WGS84 / UTM Zone 38N.")
            else {
                OutlinedTextField(longitude,{longitude=it},label={Text("Longitude")})
                OutlinedTextField(latitude,{latitude=it},label={Text("Latitude")})
                Text("أدخل إحداثيات WGS84 لنقطة مرجعية.")
            }
        }},
        confirmButton={TextButton(onClick={showKmlDialog=false;kmlSaver.launch(if (importedKml != null) "ARKAN_SEWER_NETWORK.kml" else "ARKAN_reference.kml")}) { Text("حفظ KML") }},
        dismissButton={TextButton(onClick={showKmlDialog=false}) { Text("إلغاء") }}
    )
}


private object DxfKmlImporter {
    data class ImportResult(val kml: String, val summary: String, val layerLengths: Map<String, Double>, val manholeCount: Int, val manholes: List<Manhole>)
    private data class PairCode(val code: Int, val value: String)
    private data class GeoPoint(val lon: Double, val lat: Double, val alt: Double)
    private data class DxfPosition(val east: Double, val north: Double, val alt: Double)
    private data class DxfLabel(val east: Double, val north: Double, val text: String)
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
        val importedManholes = structurePositions.mapIndexed { index, pos ->
            val nearestStructure = labels.filter { it.text.contains("STRUCTURE", true) }
                .minByOrNull { hypot(it.east - pos.east, it.north - pos.north) }
            val nearestDepth = labels.filter { Regex("""(?i)h\\s*:\\s*[-+]?\\d+(?:\\.\\d+)?""").containsMatchIn(it.text) }
                .minByOrNull { hypot(it.east - pos.east, it.north - pos.north) }
            val extractedId = nearestStructure?.text?.let { Regex("""\\(([^)]+)\\)""").find(it)?.groupValues?.getOrNull(1) }
            val extractedDepth = nearestDepth?.text?.let { Regex("""(?i)h\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?)""").find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() } ?: 0.0
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
        return ImportResult(kml, "خطوط: $pipeCount | مناهيل: $mhCount", layerLengths, mhCount, importedManholes)
    }
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\\\"", "&quot;").replace("'", "&apos;")
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
