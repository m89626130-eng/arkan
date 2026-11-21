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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PX_PER_METER = 40f
private fun localToUtm(point: Offset, originEasting: Double, originNorthing: Double): Pair<Double, Double> =
    (originEasting + point.x / PX_PER_METER) to (originNorthing - point.y / PX_PER_METER)
private data class Pipe(val a: Offset, val b: Offset, val layer: String)
private data class CadLayer(val name: String, val color: Long, val visible: Boolean = true, val locked: Boolean = false)
private data class CadEntity(val type: String, val points: List<Offset>, val layer: String = "0", val text: String = "", val color: Long? = null)
private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val dx=b.x-a.x; val dy=b.y-a.y
    val len2=dx*dx+dy*dy
    if(len2<=1e-6f) return hypot((p.x-a.x).toDouble(),(p.y-a.y).toDouble()).toFloat()
    val t=(((p.x-a.x)*dx+(p.y-a.y)*dy)/len2).coerceIn(0f,1f)
    val q=Offset(a.x+t*dx,a.y+t*dy)
    return hypot((p.x-q.x).toDouble(),(p.y-q.y).toDouble()).toFloat()
}
private fun cadEntityDistance(p: Offset, e: CadEntity): Float {
    if(e.points.isEmpty()) return Float.MAX_VALUE
    if(e.type=="CIRCLE" && e.points.size>=2) {
        val radius=hypot((e.points[1].x-e.points[0].x).toDouble(),(e.points[1].y-e.points[0].y).toDouble()).toFloat()
        return kotlin.math.abs(hypot((p.x-e.points[0].x).toDouble(),(p.y-e.points[0].y).toDouble()).toFloat()-radius)
    }
    val pts=if(e.type=="ARC") arcPoints(e.points,32) else if(e.type=="RECTANGLE" && e.points.size>=2) {
        val a=e.points[0]; val b=e.points[1]
        listOf(Offset(minOf(a.x,b.x),minOf(a.y,b.y)),Offset(maxOf(a.x,b.x),minOf(a.y,b.y)),Offset(maxOf(a.x,b.x),maxOf(a.y,b.y)),Offset(minOf(a.x,b.x),maxOf(a.y,b.y)),Offset(minOf(a.x,b.x),minOf(a.y,b.y)))
    } else e.points
    if(pts.size==1) return hypot((p.x-pts[0].x).toDouble(),(p.y-pts[0].y).toDouble()).toFloat()
    return pts.zipWithNext().minOfOrNull { (a,b)->segmentDistance(p,a,b) } ?: Float.MAX_VALUE
}
private fun offsetCadPoints(points: List<Offset>, distance: Float, side: Float): List<Offset> {
    if(points.size<2) return emptyList()
    val sign=if(side>=0f) 1f else -1f
    return points.indices.map { i ->
        val prev=points[(i-1).coerceAtLeast(0)]; val next=points[(i+1).coerceAtMost(points.lastIndex)]
        val dx=next.x-prev.x; val dy=next.y-prev.y
        val len=hypot(dx.toDouble(),dy.toDouble()).toFloat().coerceAtLeast(1e-5f)
        Offset(points[i].x-dy/len*distance*sign,points[i].y+dx/len*distance*sign)
    }
}
private fun arcPoints(points: List<Offset>, steps: Int = 48): List<Offset> {
    if (points.size != 3) return emptyList()
    val a=points[0]; val m=points[1]; val b=points[2]
    val d=2.0*(a.x.toDouble()*(m.y-b.y)+m.x.toDouble()*(b.y-a.y)+b.x.toDouble()*(a.y-m.y))
    if (kotlin.math.abs(d)<1e-8) return listOf(a,m,b)
    val aa=a.x.toDouble()*a.x+a.y.toDouble()*a.y
    val mm=m.x.toDouble()*m.x+m.y.toDouble()*m.y
    val bb=b.x.toDouble()*b.x+b.y.toDouble()*b.y
    val cx=(aa*(m.y-b.y)+mm*(b.y-a.y)+bb*(a.y-m.y))/d
    val cy=(aa*(b.x-m.x)+mm*(a.x-b.x)+bb*(m.x-a.x))/d
    val radius=hypot(a.x-cx,a.y-cy)
    val start=atan2(a.y-cy,a.x-cx); val mid=atan2(m.y-cy,m.x-cx); val end=atan2(b.y-cy,b.x-cx)
    fun ccw(v:Double)=(v%(2.0*PI)+2.0*PI)%(2.0*PI)
    val midSweep=ccw(mid-start); val endSweep=ccw(end-start)
    val sweep=if(midSweep<=endSweep) endSweep else endSweep-2.0*PI
    return (0..steps).map { i -> val angle=start+sweep*i/steps; Offset((cx+radius*cos(angle)).toFloat(),(cy+radius*sin(angle)).toFloat()) }
}
private data class ImportedPath(val layer: String, val points: List<Pair<Double, Double>>)
private fun isPipeLayerName(layer: String): Boolean =
    Regex("(?i)(PIPE|SEWER|SWR|SSWR|DRAIN|STORM|RAIN|WASTE|GRAVITY|250|315|400|500|600|700|مجرى|انبوب|أنبوب|صرف|مجاري|امطار|أمطار)").containsMatchIn(layer)

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
private fun CadPaletteButton(icon: String, label: String, onClick: () -> Unit, selected: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.width(66.dp).height(60.dp),
        shape = RoundedCornerShape(5.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFF174B58) else Color(0xFF252B31),
            contentColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Color(0xFF39C6D8) else Color(0xFF3A444C)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, color = if (selected) Color.White else Color(0xFF59D7E5), fontSize = 23.sp, lineHeight = 25.sp)
            Spacer(Modifier.height(1.dp))
            Text(label, color = Color(0xFFE4E9EC), fontSize = 8.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ArkanApp() {
    var pipes by remember { mutableStateOf(listOf<Pipe>()) }
    var cadEntities by remember { mutableStateOf(listOf<CadEntity>()) }
    var cadLayers by remember { mutableStateOf(listOf(CadLayer("0", 0xFFFFFFFF))) }
    var currentCadLayer by remember { mutableStateOf("0") }
    var showNewLayerDialog by remember { mutableStateOf(false) }
    var newLayerName by remember { mutableStateOf("") }
    var newLayerColor by remember { mutableStateOf(0xFF00C8A0L) }
    var cadColorValue by remember { mutableStateOf(0xFFFFC857L) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var showDrawTools by remember { mutableStateOf(false) }
    var showEditTools by remember { mutableStateOf(false) }
    var showLayerTools by remember { mutableStateOf(false) }
    var showMeasureTools by remember { mutableStateOf(false) }
    var showDiameterTools by remember { mutableStateOf(false) }
    var showSewerNetworkTools by remember { mutableStateOf(false) }
    var selectedCadTab by remember { mutableStateOf("DRAW") }
    var showSlopeDialog by remember { mutableStateOf(false) }
    var cadRedo by remember { mutableStateOf(listOf<CadEntity>()) }
    var cadEditSelection by remember { mutableStateOf<Int?>(null) }
    var viewZoom by remember { mutableStateOf(1f) }
    var viewPanX by remember { mutableStateOf(0f) }
    var viewPanY by remember { mutableStateOf(0f) }
    var cadOffsetDistance by remember { mutableStateOf("0.25") }
    var cadCornerDistance by remember { mutableStateOf("0.25") }
    var cadScaleFactor by remember { mutableStateOf("2.0") }
    var cadTextValue by remember { mutableStateOf("Text") }
    var cadStretchVertex by remember { mutableStateOf<Int?>(null) }
    var cadDraft by remember { mutableStateOf(listOf<Offset>()) }
    var measureDraft by remember { mutableStateOf(listOf<Offset>()) }
    var measureLastResult by remember { mutableStateOf("") }
    var measureShowOnDrawing by remember { mutableStateOf(true) }
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
    var showClearConfirm by remember { mutableStateOf(false) }
    var showKmlDialog by remember { mutableStateOf(false) }
    var showGeoDialog by remember { mutableStateOf(false) }
    var referenceEasting by remember { mutableStateOf("") }
    var referenceNorthing by remember { mutableStateOf("") }
    var utmZone by remember { mutableStateOf("38") }
    var dxfFileName by remember { mutableStateOf("لم يتم اختيار DXF") }
    var importedKml by remember { mutableStateOf<String?>(null) }
    var importedSummary by remember { mutableStateOf("") }
    var importedLayerLengths by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var importedManholeCount by remember { mutableStateOf(0) }
    var importedManholes by remember { mutableStateOf(listOf<Manhole>()) }
    var importedPaths by remember { mutableStateOf(listOf<ImportedPath>()) }
    var importedDiameterLengths by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    var importedUnclassifiedGeometryLength by remember { mutableStateOf(0.0) }
    var longitude by remember { mutableStateOf("46.257") }
    var latitude by remember { mutableStateOf("31.057") }
    var mhId by remember { mutableStateOf("MH-01") }
    var mhDepth by remember { mutableStateOf("") }
    var mhLength by remember { mutableStateOf("1.10") }
    var mhWidth by remember { mutableStateOf("0.70") }
    var mhNorthing by remember { mutableStateOf("") }
    var mhEasting by remember { mutableStateOf("") }
    var mhGroundLevel by remember { mutableStateOf("") }
    var mhInvertLevel by remember { mutableStateOf("") }
    var pendingManhole by remember { mutableStateOf<Manhole?>(null) }
    val context = LocalContext.current
    fun cadLayerColor(name: String): Color = Color(cadLayers.firstOrNull { it.name == name }?.color ?: 0xFFFFFFFF)
    fun cadEntityColor(entity: CadEntity): Color = Color(entity.color ?: (cadLayers.firstOrNull { it.name == entity.layer }?.color ?: 0xFFFFFFFF))
    val visibleCadEntities = cadEntities.filter { entity -> cadLayers.firstOrNull { it.name == entity.layer }?.visible != false }

    fun finishPendingCadDraft() {
        val points = cadDraft
        val completed = when (mode) {
            "LINE" -> if (points.size >= 2) CadEntity("LINE", points.take(2), currentCadLayer) else null
            "POLYLINE" -> if (points.size >= 2) CadEntity("POLYLINE", points, currentCadLayer) else null
            "CIRCLE" -> if (points.size >= 2) CadEntity("CIRCLE", points.take(2), currentCadLayer) else null
            "RECTANGLE" -> if (points.size >= 2) CadEntity("RECTANGLE", points.take(2), currentCadLayer) else null
            "ARC" -> if (points.size >= 3) CadEntity("ARC", points.take(3), currentCadLayer) else null
            else -> null
        }
        if (completed != null) {
            cadEntities = cadEntities + completed
            cadRedo = emptyList()
            status = "تم حفظ شكل $mode قبل الانتقال إلى EDIT."
        }
        cadDraft = emptyList()
    }

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

    val importScope = rememberCoroutineScope()

    val dxfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("تعذر قراءة الملف")
                val converted = DxfKmlImporter.convert(raw, utmZone.toIntOrNull() ?: 38)
                importedKml = converted.kml
                importedSummary = converted.summary
                importedLayerLengths = converted.layerLengths
                importedManholeCount = converted.manholeCount
                importedManholes = converted.manholes
                importedPaths = converted.paths
                importedDiameterLengths = converted.diameterLengths
                importedUnclassifiedGeometryLength = converted.unclassifiedGeometryLength
                dxfFileName = uri.lastPathSegment ?: "DXF"
                status = "تم استيراد هندسة DXF: $importedSummary. نظام الإحداثيات المفترض WGS84 / UTM Zone ${utmZone}N."
            } catch (e: Exception) { status = "تعذر فتح DXF: " + e.message }
        }
    }

    val dwgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "لم يتم اختيار ملف DWG."
        } else {
            status = "تم اختيار ملف DWG؛ جارٍ نسخ الملف وتحويله وقراءة هندسته..."
            importScope.launch {
                try {
                    val converted = withContext(Dispatchers.IO) {
                        val convertedFile = LibreDwgRunner.convertDwgToDxf(context, uri)
                        check(convertedFile.isFile && convertedFile.length() > 0L) {
                            "أداة التحويل لم تُنتج ملف DXF."
                        }
                        val dxfText = convertedFile.readText(Charsets.UTF_8)
                        check(dxfText.contains("SECTION", true) && dxfText.contains("ENTITIES", true)) {
                            "ناتج المحوّل ليس DXF صالحاً أو لا يحتوي قسم ENTITIES."                        }
                        DxfKmlImporter.convert(dxfText, utmZone.toIntOrNull() ?: 38)
                    }
                    importedKml = converted.kml
                    importedSummary = converted.summary
                    importedLayerLengths = converted.layerLengths
                    importedManholeCount = converted.manholeCount
                    importedManholes = converted.manholes
                    importedPaths = converted.paths
                    importedDiameterLengths = converted.diameterLengths
                    importedUnclassifiedGeometryLength = converted.unclassifiedGeometryLength
                    dxfFileName = uri.lastPathSegment ?: "DWG"
                    status = "اكتمل استيراد DWG: " + converted.summary
                } catch (e: Throwable) {
                    status = "فشل استيراد DWG: " + (e.message ?: e.javaClass.simpleName)
                }
            }
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

    val dxfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri: Uri? ->
        if (uri != null) try {
            val e0 = referenceEasting.toDoubleOrNull() ?: 0.0
            val n0 = referenceNorthing.toDoubleOrNull() ?: 0.0
            val hasImportedUtm = importedKml != null && (importedPaths.isNotEmpty() || importedManholes.isNotEmpty())
            if (hasImportedUtm) {
                val dxf = buildArkanDxf(pipes, effectiveManholes, cadEntities, 0.0, 0.0, importedPaths, true)
                context.contentResolver.openOutputStream(uri)?.use { it.write(dxf.toByteArray(Charsets.UTF_8)) }
                status = "تم تصدير DXF مع الحفاظ على إحداثيات UTM الأصلية للمخطط المستورد."
            } else {
                require(e0 in 100000.0..900000.0 && n0 in 0.0..10000000.0) { "أدخل نقطة مرجعية UTM صحيحة قبل التصدير." }
                val dxf = buildArkanDxf(pipes, effectiveManholes, cadEntities, e0, n0)
                context.contentResolver.openOutputStream(uri)?.use { it.write(dxf.toByteArray(Charsets.UTF_8)) }
                status = "تم تصدير DXF بإحداثيات UTM Zone ${utmZone}N."
            }
        } catch (e: Exception) { status = "تعذر تصدير DXF: ${e.message}" }
    }

    val dwgSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/acad")) { uri: Uri? ->
        if (uri != null) try {
            val e0 = referenceEasting.toDoubleOrNull() ?: 0.0
            val n0 = referenceNorthing.toDoubleOrNull() ?: 0.0
            val hasImportedUtm = importedKml != null && (importedPaths.isNotEmpty() || importedManholes.isNotEmpty())
            val dxfText = if (hasImportedUtm) {
                buildArkanDxf(pipes, effectiveManholes, cadEntities, 0.0, 0.0, importedPaths, true)
            } else {
                require(e0 in 100000.0..900000.0 && n0 in 0.0..10000000.0) {
                    "أدخل إحداثيات نقطة مرجعية UTM أولاً؛ لا يمكن اعتبار الإحداثيات المحلية إحداثيات حقيقية."
                }
                buildArkanDxf(pipes, effectiveManholes, cadEntities, e0, n0)
            }
            val dwgFile = LibreDwgRunner.convertDxfToDwg(context, dxfText)
            context.contentResolver.openOutputStream(uri)?.use { output -> dwgFile.inputStream().use { it.copyTo(output) } }
            status = if (hasImportedUtm) "تم حفظ DWG مع الحفاظ على إحداثيات UTM الأصلية للمخطط المستورد."
                else "تم إنشاء DWG بإحداثيات UTM Zone ${utmZone}N."
        } catch (e: Exception) { status = "تعذر تصدير DWG: " + e.message }
    }

    val kmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri: Uri? ->
        if (uri != null) try {
            val manualNetwork = if (importedKml == null && manholes.isNotEmpty()) DxfKmlImporter.convertManual(manholes, pipes, utmZone.toIntOrNull() ?: 38) else null
            val lon = longitude.toDouble(); val lat = latitude.toDouble()
            require(importedKml != null || manualNetwork != null || (lon in -180.0..180.0 && lat in -90.0..90.0))
            val outputKml = importedKml ?: manualNetwork?.first ?: KmlPointWriter.point("ARKAN reference point", lon, lat)
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(outputKml) } }
            status = when {
                importedKml != null -> "تم تصدير شبكة CAD إلى KML وفق UTM Zone ${utmZone}N."
                manualNetwork != null -> "تم تصدير ${manholes.size} منهل و${manualNetwork.second} مقطع أنبوب بإحداثيات UTM Zone ${utmZone}N."
                else -> "تم حفظ نقطة KML المرجعية."
            }
        } catch (e: Exception) { status = "تعذر تصدير KML: ${e.message}" }
    }


    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00C8A0), background = Color(0xFF101418), surface = Color(0xFF1B2329))) {

        Column(
            Modifier.fillMaxSize().background(Color(0xFF101418))
                .statusBarsPadding().navigationBarsPadding().padding(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ARKAN CAD PRO", color = Color(0xFF53D8E5), fontSize = 20.sp)
                    Text("CAD WORKSPACE", color = Color(0xFF9AAAB2), fontSize = 9.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CadPaletteButton("↶", "UNDO", {
                        if (cadEntities.isNotEmpty()) { cadRedo = cadRedo + cadEntities.last(); cadEntities = cadEntities.dropLast(1); status = "Undo: تم التراجع عن آخر عنصر CAD." }
                        else status = "لا توجد عناصر CAD للتراجع."
                    })
                    CadPaletteButton("↷", "REDO", {
                        if (cadRedo.isNotEmpty()) { cadEntities = cadEntities + cadRedo.last(); cadRedo = cadRedo.dropLast(1); status = "Redo: تمت إعادة آخر خطوة." }
                        else status = "لا توجد خطوة لإعادتها."
                    })
                    CadPaletteButton("▣", "SAVE DWG", {
                        dwgSaver.launch("ARKAN_DRAWING.dwg")
                    })
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CadPaletteButton("⌖", "UTM", { showGeoDialog = true })
                CadPaletteButton("⇧", "OPEN DXF", { dxfPicker.launch(arrayOf("*/*")) })
                CadPaletteButton("⇧", "OPEN DWG", { dwgPicker.launch(arrayOf("*/*")) })
                CadPaletteButton("⌫", "CLEAR", { showClearConfirm = true })
                CadPaletteButton("+", "ZOOM", { viewZoom = (viewZoom * 1.25f).coerceAtMost(8f) })
                CadPaletteButton("−", "ZOOM", { viewZoom = (viewZoom / 1.25f).coerceAtLeast(0.25f) })
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp)) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = viewZoom; scaleY = viewZoom; translationX = viewPanX; translationY = viewPanY }.pointerInput(Unit) { detectTransformGestures { _, pan, zoomChange, _ -> viewZoom = (viewZoom * zoomChange).coerceIn(0.25f, 8f); viewPanX += pan.x; viewPanY += pan.y } }.background(Color.Black).pointerInput(mode, pendingManhole, pipeLayer, targetSlopePercent, referenceEasting, referenceNorthing, currentCadLayer, cadColorValue) {
                detectTapGestures(onDoubleTap = {
                    if (mode == "POLYLINE" && cadDraft.size >= 2) {                        cadEntities = cadEntities + CadEntity("POLYLINE", cadDraft, currentCadLayer)
                        cadRedo = emptyList()
                        cadDraft = emptyList()
                        status = "تم إنهاء POLYLINE."
                    }
                }, onTap = { tap ->
                    when {
                        mode == "MEASURE_DISTANCE" -> {
                            if (measureDraft.isEmpty()) { measureDraft = listOf(tap); status = "حدد نقطة النهاية لقياس DISTANCE." }
                            else {
                                val first = measureDraft.first()
                                val length = hypot((tap.x-first.x).toDouble(), (tap.y-first.y).toDouble()) / PX_PER_METER
                                val dx = (tap.x-first.x) / PX_PER_METER
                                val dy = -(tap.y-first.y) / PX_PER_METER
                                val angle = Math.toDegrees(atan2((tap.y-first.y).toDouble(), (tap.x-first.x).toDouble()))
                                val result = "Length: %.3f m   |   Angle: %.2f°   |   ΔX: %.3f m   |   ΔY: %.3f m".format(Locale.US, length, angle, dx, dy)
                                measureLastResult = result
                                if (measureShowOnDrawing) {
                                    cadEntities = cadEntities + CadEntity("MEASURE_DIMENSION", listOf(first,tap), currentCadLayer, color = 0xFF00C8A0)
                                    cadRedo = emptyList()
                                }
                                status = result
                                measureDraft = emptyList(); mode = "PIPE"
                            }
                        }
                        mode == "MEASURE_AREA" -> {
                            measureDraft = measureDraft + tap
                            status = "نقاط AREA المحددة: ${measureDraft.size}. حدد ثلاث نقاط أو أكثر ثم اضغط إنهاء."
                        }
                        mode == "MEASURE_ARC_LENGTH" -> {
                            val hit = cadEntities.indices.filter { cadEntities[it].type == "ARC" }.minByOrNull { cadEntityDistance(tap, cadEntities[it]) }
                            if (hit == null || cadEntityDistance(tap, cadEntities[hit]) > 32f) status = "لم يتم تحديد قوس ARC قريب."
                            else {
                                val pts = arcPoints(cadEntities[hit].points, 96)
                                val length = pts.zipWithNext().sumOf { (a,b) -> hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble()) } / PX_PER_METER
                                measureLastResult = "Arc Length: %.3f m".format(Locale.US, length)
                                status = measureLastResult; mode = "PIPE"
                            }
                        }
                        mode == "MEASURE_ID_POINT" -> {
                            val x = tap.x / PX_PER_METER; val y = -tap.y / PX_PER_METER
                            val e0 = referenceEasting.toDoubleOrNull(); val n0 = referenceNorthing.toDoubleOrNull()
                            measureLastResult = if (e0 != null && n0 != null) "X: %.3f m   |   Y: %.3f m   |   E: %.3f   |   N: %.3f".format(Locale.US, x, y, e0 + x, n0 + y)
                                else "X: %.3f m   |   Y: %.3f m (local drawing coordinates)".format(Locale.US, x, y)
                            status = "ID POINT | " + measureLastResult
                            mode = "PIPE"
                        }
                        mode == "DIMENSION" || mode == "DIM_ALIGNED" || mode == "DIM_LINEAR" -> {
                            if (cadDraft.isEmpty()) { cadDraft = listOf(tap); status = "حدد نقطة نهاية البعد." }
                            else {
                                val first = cadDraft.first()
                                if (hypot((tap.x-first.x).toDouble(),(tap.y-first.y).toDouble()) < 1f) status = "المس نقطة مختلفة لإنشاء البعد."
                                else {
                                    val kind = when(mode) { "DIM_LINEAR" -> "DIM_LINEAR"; "DIM_ALIGNED" -> "DIM_ALIGNED"; else -> "DIMENSION" }
                                    cadEntities = cadEntities + CadEntity(kind, listOf(first,tap), currentCadLayer)
                                    cadRedo = emptyList(); status = "تم إنشاء البعد على الرسم."; cadDraft = emptyList(); mode = "PIPE"
                                }
                            }
                        }
                        mode == "DIM_ANGULAR" -> {
                            cadDraft = cadDraft + tap
                            if (cadDraft.size == 1) status = "حدد نقطة على الضلع الأول."
                            else if (cadDraft.size == 2) status = "حدد نقطة على الضلع الثاني."
                            else { cadEntities = cadEntities + CadEntity("DIM_ANGULAR", cadDraft.take(3), currentCadLayer); cadRedo = emptyList(); status = "تم إنشاء قياس الزاوية."; cadDraft = emptyList(); mode = "PIPE" }
                        }
                        mode == "DIM_RADIUS" || mode == "DIM_DIAMETER" -> {
                            val hit = cadEntities.indices.filter { cadEntities[it].type == "CIRCLE" }.minByOrNull { cadEntityDistance(tap,cadEntities[it]) }
                            if (hit == null || cadEntityDistance(tap,cadEntities[hit]) > 36f) status = "لم يتم تحديد دائرة قريبة."
                            else { val circle = cadEntities[hit]; cadEntities = cadEntities + CadEntity(if(mode=="DIM_RADIUS") "DIM_RADIUS" else "DIM_DIAMETER", circle.points, currentCadLayer); cadRedo = emptyList(); status = "تم وضع قياس الدائرة."; mode = "PIPE" }
                        }
                        mode == "DIM_ARC_LENGTH" -> {
                            val hit = cadEntities.indices.filter { cadEntities[it].type == "ARC" }.minByOrNull { cadEntityDistance(tap,cadEntities[it]) }
                            if (hit == null || cadEntityDistance(tap,cadEntities[hit]) > 36f) status = "لم يتم تحديد قوس قريب."
                            else { cadEntities = cadEntities + CadEntity("DIM_ARCLENGTH", cadEntities[hit].points, currentCadLayer); cadRedo = emptyList(); status = "تم وضع قياس طول القوس."; mode = "PIPE" }
                        }
                        mode == "COLOR" -> {
                            val hit = cadEntities.indices.minByOrNull { cadEntityDistance(tap,cadEntities[it]) }
                            if (hit != null && cadEntityDistance(tap,cadEntities[hit]) <= 32f) {
                                cadEntities = cadEntities.toMutableList().also { it[hit] = it[hit].copy(color = cadColorValue) }
                                cadRedo = emptyList(); status = "تم تغيير لون العنصر."; mode = "PIPE"
                            } else status = "لم يتم تحديد عنصر قريب لتغيير لونه."
                        }
                        mode == "TEXT" -> {
                            val value = cadTextValue.trim()
                            if (value.isEmpty()) status = "اكتب النص أولاً."
                            else {
                                cadEntities = cadEntities + CadEntity("TEXT", listOf(tap), currentCadLayer, value)
                                cadRedo = emptyList()
                                status = "تم وضع النص على الرسم."
                            }
                        }
                        mode == "DELETE" -> {
                            val hit = cadEntities.indices.minByOrNull { cadEntityDistance(tap,cadEntities[it]) }
                            if (hit != null && cadEntityDistance(tap,cadEntities[hit]) <= 28f) {
                                cadRedo = cadRedo + cadEntities[hit]
                                cadEntities = cadEntities.filterIndexed { index, _ -> index != hit }
                                cadEditSelection = null
                                status = "تم حذف العنصر."
                            } else status = "لم يتم تحديد عنصر قريب."
                        }
                        mode in listOf("MOVE","COPY","OFFSET","ROTATE","MIRROR","SCALE","STRETCH","TRIM","EXTEND","FILLET","CHAMFER") -> {
                            val selected = cadEditSelection
                            val hit = cadEntities.indices.minByOrNull { cadEntityDistance(tap,cadEntities[it]) }
                                ?.takeIf { cadEntityDistance(tap,cadEntities[it]) <= 28f }
                            if (selected == null) {
                                if (hit == null) status = "لم يتم تحديد عنصر قريب؛ المس على العنصر نفسه."
                                else {
                                    cadEditSelection = hit
                                    status = when(mode) {
                                        "TRIM" -> "تم تحديد حد القطع. المس العنصر المراد تشذيبه."
                                        "EXTEND" -> "تم تحديد خط الحد. المس العنصر المراد تمديده."
                                        "FILLET", "CHAMFER" -> "تم تحديد الخط الأول. اختر الخط الثاني."
                                        "ROTATE" -> "تم تحديد العنصر. حدد مركز الدوران ثم نقطة البداية ثم نقطة النهاية للزاوية."
                                        "MIRROR" -> "تم تحديد العنصر. حدد نقطتي محور الانعكاس."
                                        "SCALE" -> "تم تحديد العنصر. حدد نقطة الارتكاز لتغيير الحجم."
                                        "STRETCH" -> "تم تحديد العنصر. اختر الرأس/النقطة التي تريد تحريكها."
                                        else -> "تم تحديد ${cadEntities[hit].type}. حدد نقطة/جهة التنفيذ."
                                    }
                                }
                            } else if (mode in listOf("TRIM","EXTEND")) {
                                if (hit == null || hit == selected) status = "حدد عنصراً ثانياً مختلفاً."
                                else {
                                    val boundary = cadEntities[selected]
                                    val target = cadEntities[hit]
                                    if (boundary.type != "LINE" || target.type != "LINE" || boundary.points.size < 2 || target.points.size < 2) {
                                        status = "TRIM وEXTEND يدعمان حالياً عناصر LINE فقط."
                                    } else {
                                        val a=boundary.points[0]; val b=boundary.points[1]
                                        val c=target.points[0]; val d=target.points[1]
                                        val rx=b.x-a.x; val ry=b.y-a.y
                                        val sx=d.x-c.x; val sy=d.y-c.y
                                        val den=rx*sy-ry*sx
                                        if(kotlin.math.abs(den)<1e-5f) status = "الخطّان متوازيان ولا توجد نقطة تقاطع."
                                        else {
                                            val qx=c.x-a.x; val qy=c.y-a.y
                                            val u=(qx*ry-qy*rx)/den
                                            val t=(qx*sy-qy*sx)/den
                                            val ix=a.x+t*rx; val iy=a.y+t*ry
                                            val intersection=Offset(ix,iy)
                                            if(mode=="TRIM") {
                                                if(t !in 0f..1f || u !in 0f..1f) status = "لا يوجد تقاطع داخل حدود الخطين؛ لم يتم التشذيب."
                                                else {
                                                    val firstDist=hypot((tap.x-c.x).toDouble(),(tap.y-c.y).toDouble())
                                                    val secondDist=hypot((tap.x-d.x).toDouble(),(tap.y-d.y).toDouble())
                                                    val newPoints=if(firstDist<=secondDist) listOf(intersection,d) else listOf(c,intersection)
                                                    cadEntities=cadEntities.toMutableList().also { it[hit]=target.copy(points=newPoints) }
                                                    cadRedo=emptyList(); status="تم TRIM عند نقطة التقاطع."
                                                }
                                            } else {
                                                if(t !in 0f..1f || (u in 0f..1f)) status = "لا توجد نقطة تمديد مناسبة خارج نهاية العنصر."
                                                else {
                                                    val firstDist=hypot((intersection.x-c.x).toDouble(),(intersection.y-c.y).toDouble())
                                                    val secondDist=hypot((intersection.x-d.x).toDouble(),(intersection.y-d.y).toDouble())
                                                    val newPoints=if(firstDist<secondDist) listOf(intersection,d) else listOf(c,intersection)
                                                    cadEntities=cadEntities.toMutableList().also { it[hit]=target.copy(points=newPoints) }
                                                    cadRedo=emptyList(); status="تم EXTEND إلى خط الحد."
                                                }
                                            }
                                        }
                                    }
                                    cadEditSelection=null
                                }
                            } else if (mode in listOf("FILLET","CHAMFER")) {
                                if (hit == null || hit == selected) status = "حدد خطاً ثانياً مختلفاً."
                                else {
                                    val first = cadEntities.getOrNull(selected)
                                    val second = cadEntities.getOrNull(hit)
                                    val distanceMeters = cadCornerDistance.toFloatOrNull()
                                    if (first == null || second == null) status = "تعذر العثور على الخط المحدد."
                                    else if (first.type != "LINE" || second.type != "LINE" || first.points.size < 2 || second.points.size < 2) {
                                        status = "$mode يدعم حالياً عنصرين من نوع LINE فقط."
                                    } else if (distanceMeters == null || distanceMeters <= 0f) {
                                        status = "أدخل قيمة موجبة لـ FILLET radius أو CHAMFER distance."
                                    } else {
                                        val a=first.points[0]; val b=first.points[1]
                                        val c=second.points[0]; val d=second.points[1]
                                        val rx=b.x-a.x; val ry=b.y-a.y
                                        val sx=d.x-c.x; val sy=d.y-c.y
                                        val den=rx*sy-ry*sx
                                        if(kotlin.math.abs(den)<1e-5f) status = "الخطّان متوازيان؛ لا يمكن تنفيذ $mode."
                                        else {
                                            val qx=c.x-a.x; val qy=c.y-a.y
                                            val t=(qx*sy-qy*sx)/den
                                            val u=(qx*ry-qy*rx)/den
                                            if(t !in 0f..1f || u !in 0f..1f) status = "يجب أن يتقاطع الخطان ضمن امتدادهما المرسوم."
                                            else {
                                                val corner=Offset(a.x+t*rx,a.y+t*ry)
                                                val firstStartNear=hypot((corner.x-a.x).toDouble(),(corner.y-a.y).toDouble()) <= hypot((corner.x-b.x).toDouble(),(corner.y-b.y).toDouble())
                                                val secondStartNear=hypot((corner.x-c.x).toDouble(),(corner.y-c.y).toDouble()) <= hypot((corner.x-d.x).toDouble(),(corner.y-d.y).toDouble())
                                                val pFar=if(firstStartNear) b else a
                                                val qFar=if(secondStartNear) d else c
                                                val v1x=pFar.x-corner.x; val v1y=pFar.y-corner.y
                                                val v2x=qFar.x-corner.x; val v2y=qFar.y-corner.y
                                                val len1=hypot(v1x.toDouble(),v1y.toDouble()).toFloat()
                                                val len2=hypot(v2x.toDouble(),v2y.toDouble()).toFloat()
                                                val dot=((v1x*v2x+v1y*v2y)/(len1*len2).coerceAtLeast(1e-6f)).coerceIn(-1f,1f)
                                                val theta=kotlin.math.acos(dot)
                                                val half=theta/2f
                                                val requested=distanceMeters*PX_PER_METER
                                                val trimDistance=if(mode=="FILLET") requested/kotlin.math.tan(half).coerceAtLeast(1e-5f) else requested
                                                if(theta < 0.02f || kotlin.math.abs(PI.toFloat()-theta) < 0.02f || !trimDistance.isFinite() || trimDistance <= 0f || trimDistance >= len1 || trimDistance >= len2) {
                                                    status = "المسافة كبيرة أو زاوية التقاطع غير مناسبة؛ قلل القيمة."
                                                } else {
                                                    val e1x=v1x/len1; val e1y=v1y/len1
                                                    val e2x=v2x/len2; val e2y=v2y/len2
                                                    val p=Offset(corner.x+e1x*trimDistance,corner.y+e1y*trimDistance)
                                                    val q=Offset(corner.x+e2x*trimDistance,corner.y+e2y*trimDistance)
                                                    val newFirst=if(firstStartNear) listOf(p,pFar) else listOf(pFar,p)
                                                    val newSecond=if(secondStartNear) listOf(q,qFar) else listOf(qFar,q)
                                                    val updated=cadEntities.toMutableList()
                                                    updated[selected]=first.copy(points=newFirst)
                                                    updated[hit]=second.copy(points=newSecond)
                                                    if(mode=="CHAMFER") {
                                                        updated.add(CadEntity("LINE",listOf(p,q),first.layer))
                                                        cadEntities=updated
                                                        status="تم تنفيذ CHAMFER بمسافة ${distanceMeters} م."
                                                    } else {
                                                        val bx=e1x+e2x; val by=e1y+e2y
                                                        val bl=hypot(bx.toDouble(),by.toDouble()).toFloat()
                                                        if(bl<1e-5f) status="تعذر حساب منصف زاوية FILLET."
                                                        else {
                                                            val bisX=bx/bl; val bisY=by/bl
                                                            val sinHalf=kotlin.math.sin(half).coerceAtLeast(1e-5f)
                                                            val center=Offset(corner.x+bisX*(requested/sinHalf),corner.y+bisY*(requested/sinHalf))
                                                            val mid=Offset(center.x-bisX*requested,center.y-bisY*requested)
                                                            updated.add(CadEntity("ARC",listOf(p,mid,q),first.layer))
                                                            cadEntities=updated
                                                            status="تم تنفيذ FILLET بنصف قطر ${distanceMeters} م."
                                                        }
                                                    }
                                                    cadRedo=emptyList()
                                                }
                                            }
                                        }
                                    }
                                    cadEditSelection=null
                                }
                            } else {
                                val entity = cadEntities.getOrNull(selected)
                                if (entity == null) { cadEditSelection = null; status = "العنصر المحدد غير موجود." }
                                else if (mode == "MIRROR") {
                                    if (cadDraft.size < 2) {
                                        cadDraft = cadDraft + tap
                                        status = if (cadDraft.size == 1) "حدد النقطة الثانية لمحور الانعكاس." else "جارٍ تنفيذ MIRROR."
                                    } else {
                                        val axisA = cadDraft[0]
                                        val axisB = cadDraft[1]
                                        val vx = axisB.x - axisA.x
                                        val vy = axisB.y - axisA.y
                                        val lengthSquared = vx * vx + vy * vy
                                        if (lengthSquared < 1e-6f) {
                                            cadDraft = emptyList()
                                            status = "محور الانعكاس قصير جداً؛ أعد تحديد النقطتين."
                                        } else {
                                            val reflected = entity.copy(points = entity.points.map { p ->
                                                val px = p.x - axisA.x
                                                val py = p.y - axisA.y
                                                val projection = (px * vx + py * vy) / lengthSquared
                                                val closestX = axisA.x + projection * vx
                                                val closestY = axisA.y + projection * vy
                                                Offset(2f * closestX - p.x, 2f * closestY - p.y)
                                            })
                                            cadEntities = cadEntities + reflected
                                            cadRedo = emptyList()
                                            cadEditSelection = null
                                            cadDraft = emptyList()
                                            status = "تم تنفيذ MIRROR مع الاحتفاظ بالعنصر الأصلي."
                                        }
                                    }
                                } else if (mode == "SCALE") {
                                    val factor = cadScaleFactor.toFloatOrNull()
                                    if (factor == null || factor <= 0f || !factor.isFinite()) {
                                        status = "أدخل معامل SCALE موجباً مثل 2 أو 0.5."
                                        cadEditSelection = null
                                    } else if (cadDraft.isEmpty()) {
                                        cadDraft = listOf(tap)
                                        status = "تم تحديد نقطة الارتكاز؛ جارٍ تغيير حجم العنصر."
                                    } else {
                                        val base = cadDraft.first()
                                        val scaled = entity.copy(points = entity.points.map { p ->
                                            Offset(base.x + (p.x - base.x) * factor, base.y + (p.y - base.y) * factor)
                                        })
                                        cadEntities = cadEntities.toMutableList().also { it[selected] = scaled }
                                        cadRedo = emptyList()
                                        cadEditSelection = null
                                        cadDraft = emptyList()
                                        status = "تم تنفيذ SCALE بمعامل ${factor}."
                                    }
                                } else if (mode == "ROTATE") {
                                    if (cadDraft.size < 2) {
                                        cadDraft = cadDraft + tap
                                        status = if(cadDraft.size == 1) "حدد نقطة بداية زاوية الدوران." else "حدد نقطة نهاية زاوية الدوران."
                                    } else {
                                        val base = cadDraft[0]; val ref = cadDraft[1]
                                        val a1 = atan2((ref.y-base.y).toDouble(),(ref.x-base.x).toDouble())
                                        val a2 = atan2((tap.y-base.y).toDouble(),(tap.x-base.x).toDouble())
                                        val angle = a2-a1
                                        val rotated = entity.copy(points=entity.points.map { p ->
                                            val dx=p.x-base.x; val dy=p.y-base.y
                                            Offset(base.x+(dx*cos(angle)-dy*sin(angle)).toFloat(),base.y+(dx*sin(angle)+dy*cos(angle)).toFloat())
                                        })
                                        cadEntities=cadEntities.toMutableList().also { it[selected]=rotated }
                                        cadRedo=emptyList(); cadEditSelection=null; cadDraft=emptyList()
                                        status="تم تدوير العنصر بمقدار %.2f درجة.".format(Locale.US,Math.toDegrees(angle))
                                    }
                                } else if (mode == "STRETCH") {
                                    if (entity.points.isEmpty()) { cadEditSelection=null; status="العنصر لا يحتوي نقاطاً قابلة للتمديد." }
                                    else if (cadStretchVertex == null) {
                                        val vi=entity.points.indices.minByOrNull { i -> hypot((tap.x-entity.points[i].x).toDouble(),(tap.y-entity.points[i].y).toDouble()) }
                                        val distance=vi?.let { hypot((tap.x-entity.points[it].x).toDouble(),(tap.y-entity.points[it].y).toDouble()) } ?: Double.MAX_VALUE
                                        if(vi==null || distance>36.0) { cadEditSelection=null; status="المس قريباً من إحدى نقاط العنصر." }
                                        else { cadStretchVertex=vi; status="تم تحديد الرأس. المس الموقع الجديد للرأس." }
                                    } else {
                                        val vi=cadStretchVertex!!
                                        val changed=entity.copy(points=entity.points.mapIndexed { i,p -> if(i==vi) tap else p })
                                        cadEntities=cadEntities.toMutableList().also { it[selected]=changed }
                                        cadRedo=emptyList(); cadEditSelection=null; cadStretchVertex=null
                                        status="تم تنفيذ STRETCH بتحريك نقطة واحدة."
                                    }
                                } else if (mode == "OFFSET") {
                                    val d = cadOffsetDistance.toFloatOrNull()
                                    if (d == null || d <= 0f) status = "أدخل مسافة OFFSET موجبة."
                                    else if (entity.type !in listOf("LINE","POLYLINE")) status = "OFFSET يدعم حالياً LINE وPOLYLINE فقط."
                                    else {
                                        val a=entity.points.first(); val b=entity.points.last()
                                        val side=(b.x-a.x)*(tap.y-a.y)-(b.y-a.y)*(tap.x-a.x)
                                        val shifted=offsetCadPoints(entity.points,d*PX_PER_METER,side)
                                        if(shifted.size>=2) { cadEntities=cadEntities+CadEntity(entity.type,shifted,entity.layer); cadRedo=emptyList(); status="تم إنشاء OFFSET بمقدار $d م." }
                                        cadEditSelection=null
                                    }
                                } else {
                                    val anchor=entity.points.firstOrNull()
                                    if(anchor==null) { cadEditSelection=null; status="لا يمكن نقل هذا العنصر." }
                                    else {
                                        val dx=tap.x-anchor.x; val dy=tap.y-anchor.y
                                        val moved=entity.copy(points=entity.points.map { Offset(it.x+dx,it.y+dy) })
                                        if(mode=="MOVE") { cadEntities=cadEntities.toMutableList().also { it[selected]=moved }; status="تم نقل العنصر." }
                                        else { cadEntities=cadEntities+moved; status="تم نسخ العنصر." }
                                        cadRedo=emptyList(); cadEditSelection=null
                                    }
                                }
                            }
                        }
                        mode == "LINE" -> {
                            if (cadDraft.isEmpty()) { cadDraft = listOf(tap); status = "حدد نقطة نهاية LINE." }
                            else { cadEntities = cadEntities + CadEntity("LINE", listOf(cadDraft.first(), tap), currentCadLayer); cadRedo = emptyList(); cadDraft = emptyList(); status = "تم رسم LINE." }
                        }
                        mode == "POLYLINE" -> {
                            cadDraft = cadDraft + tap
                            status = "نقاط POLYLINE: ${cadDraft.size}. انقر مرتين لإنهاء المسار أو استخدم زر إنهاء."
                        }
                        mode == "CIRCLE" -> {
                            if (cadDraft.isEmpty()) { cadDraft = listOf(tap); status = "حدد نقطة على محيط الدائرة." }
                            else { cadEntities = cadEntities + CadEntity("CIRCLE", listOf(cadDraft.first(), tap), currentCadLayer); cadRedo = emptyList(); cadDraft = emptyList(); status = "تم رسم CIRCLE." }
                        }
                        mode == "RECTANGLE" -> {
                            if (cadDraft.isEmpty()) { cadDraft = listOf(tap); status = "حدد الركن المقابل للمستطيل." }
                            else { cadEntities = cadEntities + CadEntity("RECTANGLE", listOf(cadDraft.first(), tap), currentCadLayer); cadRedo = emptyList(); cadDraft = emptyList(); status = "تم رسم RECTANGLE." }
                        }
                        mode == "ARC" -> {
                            cadDraft = cadDraft + tap
                            if (cadDraft.size == 3) { cadEntities = cadEntities + CadEntity("ARC", cadDraft, currentCadLayer); cadRedo = emptyList(); cadDraft = emptyList(); status = "تم رسم ARC بثلاث نقاط." }
                            else status = "حدد النقطة ${cadDraft.size + 1} للقوس."
                        }
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
                })
            }) {
                // Plain black CAD workspace: grid removed; drawing is allowed anywhere on the canvas.
                if (importedPaths.isNotEmpty() || importedManholes.isNotEmpty()) {
                    val allCoords = importedPaths.flatMap { it.points } + importedManholes.map { it.easting to it.northing }
                    if (allCoords.isNotEmpty()) {
                        val minE = allCoords.minOf { it.first }
                        val maxE = allCoords.maxOf { it.first }
                        val minN = allCoords.minOf { it.second }
                        val maxN = allCoords.maxOf { it.second }
                        val spanE = (maxE - minE).coerceAtLeast(0.01)
                        val spanN = (maxN - minN).coerceAtLeast(0.01)
                        val fit = minOf((size.width - 36f) / spanE.toFloat(), (size.height - 36f) / spanN.toFloat()).coerceAtLeast(0.0001f)
                        val offsetX = (size.width - spanE.toFloat() * fit) / 2f
                        val offsetY = (size.height - spanN.toFloat() * fit) / 2f
                        fun screenPoint(e: Double, n: Double) = Offset(offsetX + ((e - minE) * fit).toFloat(), offsetY + ((maxN - n) * fit).toFloat())
                        importedPaths.forEach { path ->
                            path.points.zipWithNext().forEach { (a, b) ->
                                drawLine(pipeLayerColor(path.layer), screenPoint(a.first, a.second), screenPoint(b.first, b.second), 4f)
                            }
                        }
                        importedManholes.forEach { mh ->
                            val p = screenPoint(mh.easting, mh.northing)
                            drawCircle(Color(0xFFFFC857), 7f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                        }
                    }
                }
                visibleCadEntities.forEach { entity ->
                    val entityColor = cadEntityColor(entity)
                    when (entity.type) {
                        "LINE" -> if (entity.points.size >= 2) drawLine(entityColor, entity.points[0], entity.points[1], 2f)
                        "POLYLINE" -> entity.points.zipWithNext().forEach { (a,b) -> drawLine(entityColor, a, b, 2f) }
                        "CIRCLE" -> if (entity.points.size >= 2) {
                            val c = entity.points[0]; val edge = entity.points[1]
                            drawCircle(entityColor, hypot((edge.x-c.x).toDouble(), (edge.y-c.y).toDouble()).toFloat(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        }
                        "RECTANGLE" -> if (entity.points.size >= 2) {
                            val a = entity.points[0]; val b = entity.points[1]
                            drawRect(entityColor, topLeft = Offset(minOf(a.x,b.x), minOf(a.y,b.y)), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x-a.x), kotlin.math.abs(b.y-a.y)), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        }
                        "ARC" -> {
                            val pts = arcPoints(entity.points)
                            pts.zipWithNext().forEach { (a,b) -> drawLine(entityColor, a, b, 2f) }
                        }
                        "MEASURE_DIMENSION" -> if (entity.points.size >= 2) {
                            val a = entity.points[0]; val b = entity.points[1]
                            val dx = b.x-a.x; val dy = b.y-a.y
                            val length = hypot(dx.toDouble(),dy.toDouble()).toFloat().coerceAtLeast(1f)
                            val nx = -dy/length; val ny = dx/length
                            val off = 24f
                            val da = Offset(a.x+nx*off,a.y+ny*off); val db = Offset(b.x+nx*off,b.y+ny*off)
                            drawLine(entityColor,a,da,1.5f); drawLine(entityColor,b,db,1.5f); drawLine(entityColor,da,db,2f)
                            val arrow = 8f
                            drawLine(entityColor,da,Offset(da.x+(dx/length*arrow)+(nx*arrow*0.55f),da.y+(dy/length*arrow)+(ny*arrow*0.55f)),2f)
                            drawLine(entityColor,da,Offset(da.x+(dx/length*arrow)-(nx*arrow*0.55f),da.y+(dy/length*arrow)-(ny*arrow*0.55f)),2f)
                            drawLine(entityColor,db,Offset(db.x-(dx/length*arrow)+(nx*arrow*0.55f),db.y-(dy/length*arrow)+(ny*arrow*0.55f)),2f)
                            drawLine(entityColor,db,Offset(db.x-(dx/length*arrow)-(nx*arrow*0.55f),db.y-(dy/length*arrow)-(ny*arrow*0.55f)),2f)
                            val label = "%.3f m".format(Locale.US,length/PX_PER_METER)
                            val paint = Paint().apply { color = entityColor.toArgb(); textSize = 16f; isAntiAlias = true; textAlign = Paint.Align.CENTER; style = Paint.Style.FILL }
                            drawContext.canvas.nativeCanvas.drawText(label,(da.x+db.x)/2f,(da.y+db.y)/2f-7f,paint)
                        }
                        "DIMENSION", "DIM_ALIGNED", "DIM_LINEAR" -> if (entity.points.size >= 2) {
                            val a = entity.points[0]; val b = entity.points[1]
                            val dx = b.x-a.x; val dy = b.y-a.y
                            val length = hypot(dx.toDouble(),dy.toDouble()).toFloat().coerceAtLeast(1f)
                            val nx = -dy/length; val ny = dx/length
                            val off = 22f
                            val da = Offset(a.x+nx*off,a.y+ny*off); val db = Offset(b.x+nx*off,b.y+ny*off)
                            drawLine(entityColor,a,da,1f); drawLine(entityColor,b,db,1f); drawLine(entityColor,da,db,2f)
                            val arrow = 7f
                            drawLine(entityColor,da,Offset(da.x+(dx/length*arrow)+(nx*arrow*0.55f),da.y+(dy/length*arrow)+(ny*arrow*0.55f)),2f)
                            drawLine(entityColor,da,Offset(da.x+(dx/length*arrow)-(nx*arrow*0.55f),da.y+(dy/length*arrow)-(ny*arrow*0.55f)),2f)
                            drawLine(entityColor,db,Offset(db.x-(dx/length*arrow)+(nx*arrow*0.55f),db.y-(dy/length*arrow)+(ny*arrow*0.55f)),2f)
                            drawLine(entityColor,db,Offset(db.x-(dx/length*arrow)-(nx*arrow*0.55f),db.y-(dy/length*arrow)-(ny*arrow*0.55f)),2f)
                            val label = "%.2f m".format(Locale.US,length/PX_PER_METER)
                            val paint = Paint().apply { color = entityColor.toArgb(); textSize = 15f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
                            drawContext.canvas.nativeCanvas.drawText(label,(da.x+db.x)/2f,(da.y+db.y)/2f-5f,paint)
                        }
                        "DIM_RADIUS", "DIM_DIAMETER" -> if (entity.points.size >= 2) {
                            val center = entity.points[0]; val edge = entity.points[1]
                            val radius = hypot((edge.x-center.x).toDouble(),(edge.y-center.y).toDouble()).toFloat()
                            val end = if(entity.type=="DIM_DIAMETER") Offset(center.x+(edge.x-center.x)*2f,center.y+(edge.y-center.y)*2f) else edge
                            drawLine(entityColor,center,end,2f)
                            val value = if(entity.type=="DIM_DIAMETER") radius*2f else radius
                            val label = (if(entity.type=="DIM_DIAMETER") "Ø " else "R ") + "%.2f m".format(Locale.US,value/PX_PER_METER)
                            val paint = Paint().apply { color=entityColor.toArgb(); textSize=15f; isAntiAlias=true }
                            drawContext.canvas.nativeCanvas.drawText(label,(center.x+end.x)/2f+5f,(center.y+end.y)/2f-5f,paint)
                        }
                        "DIM_ARCLENGTH" -> {
                            val pts=arcPoints(entity.points,96)
                            pts.zipWithNext().forEach { (a,b) -> drawLine(entityColor,a,b,2f) }
                            val len=pts.zipWithNext().sumOf { (a,b)->hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble()) }/PX_PER_METER
                            val mid=pts.getOrNull(pts.size/2)
                            if(mid!=null) { val paint=Paint().apply { color=entityColor.toArgb(); textSize=15f; isAntiAlias=true }; drawContext.canvas.nativeCanvas.drawText("Arc %.2f m".format(Locale.US,len),mid.x,mid.y-8f,paint) }
                        }
                        "DIM_ANGULAR" -> if(entity.points.size>=3) {
                            val v=entity.points[0]; val a=entity.points[1]; val b=entity.points[2]
                            val ax=a.x-v.x; val ay=a.y-v.y; val bx=b.x-v.x; val by=b.y-v.y
                            val denom=hypot(ax.toDouble(),ay.toDouble())*hypot(bx.toDouble(),by.toDouble())
                            val angle=if(denom>0.0) Math.acos(((ax*bx+ay*by)/denom).coerceIn(-1.0,1.0))*180.0/Math.PI else 0.0
                            drawLine(entityColor,v,a,2f); drawLine(entityColor,v,b,2f)
                            val paint=Paint().apply { color=entityColor.toArgb(); textSize=15f; isAntiAlias=true }
                            drawContext.canvas.nativeCanvas.drawText("%.1f°".format(Locale.US,angle),(v.x+a.x+b.x)/3f,(v.y+a.y+b.y)/3f,paint)
                        }
                        "TEXT" -> if (entity.points.isNotEmpty()) {
                            val paint = Paint().apply { color = entityColor.toArgb(); textSize = 16f; isAntiAlias = true }
                            drawContext.canvas.nativeCanvas.drawText(entity.text, entity.points[0].x, entity.points[0].y, paint)
                        }
                    }
                }
                if (cadDraft.isNotEmpty()) {
                    cadDraft.zipWithNext().forEach { (a,b) -> drawLine(Color.Yellow, a, b, 2f) }
                    cadDraft.forEach { drawCircle(Color.Yellow, 4f, it) }
                }
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
            Column(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewZoom = (viewZoom * 1.25f).coerceAtMost(8f); status = "تكبير: ${(viewZoom * 100).toInt()}%" }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(46.dp)) { Text("+", fontSize = 25.sp) }
                OutlinedButton(onClick = { viewZoom = (viewZoom / 1.25f).coerceAtLeast(0.25f); status = "تصغير: ${(viewZoom * 100).toInt()}%" }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(46.dp)) { Text("−", fontSize = 25.sp) }
            }
            }
            Spacer(Modifier.height(6.dp))

            Spacer(Modifier.height(8.dp))
            if (selectedCadTab == "EDIT") {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(cadOffsetDistance, { cadOffsetDistance = it }, label = { Text("OFFSET m") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(cadCornerDistance, { cadCornerDistance = it }, label = { Text("FILLET / CHAMFER") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(cadScaleFactor, { cadScaleFactor = it }, label = { Text("SCALE") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
            if (selectedCadTab == "ANNOTATION" || (selectedCadTab == "DRAW" && mode == "TEXT")) {
                OutlinedTextField(cadTextValue, { cadTextValue = it }, label = { Text("TEXT CONTENT") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (selectedCadTab == "COLOR") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("RED" to 0xFFFF4040L, "BLUE" to 0xFF3080FFFFL, "GREEN" to 0xFF20C060L, "YELLOW" to 0xFFFFD21FL, "CYAN" to 0xFF20D8E8L, "WHITE" to 0xFFFFFFFFL, "ORANGE" to 0xFFFF9633L).forEach { pair ->
                        CadPaletteButton("●", pair.first, { cadColorValue = pair.second }, selected = cadColorValue == pair.second)
                    }
                }
            }
            if (selectedCadTab == "LAYER") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CadPaletteButton("＋", "NEW LAYER", { newLayerName = ""; showNewLayerDialog = true })
                    CadPaletteButton("▤", "CURRENT", { showLayerMenu = true })
                    cadLayers.forEach { layer ->
                        CadPaletteButton(if (layer.visible) "◉" else "○", layer.name.take(8), {
                            cadLayers = cadLayers.map { item -> if (item.name == layer.name) item.copy(visible = !item.visible) else item }
                            status = if (layer.visible) "تم إخفاء " + layer.name else "تم إظهار " + layer.name
                        }, selected = currentCadLayer == layer.name)
                    }
                }
            }
            if (selectedCadTab == "SEWER NETWORK") {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(trenchWidth, { trenchWidth = it }, label = { Text("TRENCH WIDTH m") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(trenchDepth, { trenchDepth = it }, label = { Text("TRENCH DEPTH m") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val commandItems: List<Pair<String, String>> = when (selectedCadTab) {
                    "DRAW" -> listOf("LINE" to "╱", "POLYLINE" to "⌁", "CIRCLE" to "◯", "RECTANGLE" to "▭", "ARC" to "⌒", "TEXT" to "T")
                    "EDIT" -> listOf("MOVE" to "✥", "COPY" to "▣", "ROTATE" to "⟳", "MIRROR" to "⇄", "TRIM" to "✂", "EXTEND" to "↗", "STRETCH" to "↔", "OFFSET" to "⇉", "FILLET" to "⌒", "CHAMFER" to "⌜", "SCALE" to "⤢", "DELETE" to "⌫")
                    "LAYER" -> listOf("NEW LAYER" to "＋", "CURRENT LAYER" to "▤", "LOCK/UNLOCK" to "▣")
                    "COLOR" -> listOf("APPLY COLOR" to "◉")
                    "MEASURE" -> listOf("DISTANCE" to "↔", "AREA" to "▱", "ARC LENGTH" to "⌒", "ID POINT" to "⌖")
                    "DIMENSION" -> listOf("ALIGNED" to "↗", "LINEAR" to "↔", "ANGULAR" to "∠")
                    "DIAMETER" -> listOf("RADIUS" to "R", "DIAMETER" to "Ø", "ARC LENGTH" to "⌒")
                    "ANNOTATION" -> listOf("TEXT" to "T", "DIMENSION" to "↔")
                    "SEWER NETWORK" -> listOf("PIPE" to "━", "MANHOLE" to "◉", "DIAMETER" to "Ø", "SLOPE" to "%")
                    "EXPORT" -> listOf("CSV" to "▦", "PDF" to "▤", "KML" to "◎", "DXF" to "⌘", "DWG" to "▧")
                    else -> emptyList()
                }
                commandItems.forEach { (tool, icon) ->
                    CadPaletteButton(icon, tool, {
                        when (selectedCadTab) {
                            "DRAW" -> { mode = tool; cadDraft = emptyList(); pipeStart = null; status = "أداة " + tool + ": حدد نقاط الرسم." }
                            "EDIT" -> {
                                finishPendingCadDraft(); mode = tool; cadEditSelection = null; cadDraft = emptyList()
                                status = when (tool) {
                                    "TRIM" -> "حدد خط القطع ثم العنصر المراد تشذيبه."
                                    "EXTEND" -> "حدد خط الحد ثم العنصر المراد تمديده."
                                    "STRETCH" -> "حدد العنصر ثم اختر الرأس ثم موضعه الجديد."
                                    else -> "اختر العنصر ثم حدد نقاط تنفيذ " + tool
                                }
                            }
                            "LAYER" -> when (tool) {
                                "NEW LAYER" -> { newLayerName = ""; showNewLayerDialog = true }
                                "CURRENT LAYER" -> showLayerMenu = true
                                else -> {
                                    cadLayers = cadLayers.map { item -> if (item.name == currentCadLayer) item.copy(locked = !item.locked) else item }
                                    status = "تم تبديل حالة قفل الطبقة الحالية."
                                }
                            }
                            "COLOR" -> { mode = "COLOR"; cadEditSelection = null; status = "المس العنصر الذي تريد تغيير لونه." }
                            "MEASURE" -> {
                                mode = when (tool) {
                                    "DISTANCE" -> "MEASURE_DISTANCE"
                                    "AREA" -> "MEASURE_AREA"
                                    "ARC LENGTH" -> "MEASURE_ARC_LENGTH"
                                    else -> "MEASURE_ID_POINT"
                                }
                                measureDraft = emptyList()
                                status = "اختر نقاط/عنصر قياس " + tool
                            }
                            "DIMENSION", "DIAMETER" -> {
                                mode = "DIM_" + tool.replace(" ", "_")
                                cadDraft = emptyList()
                                status = "حدد العناصر أو النقاط لتنفيذ " + tool
                            }
                            "ANNOTATION" -> {
                                mode = if (tool == "DIMENSION") "DIMENSION" else "TEXT"
                                cadDraft = emptyList()
                                status = if (tool == "TEXT") "اكتب النص ثم المس مكان وضعه." else "حدد نقطتي بداية ونهاية البعد."
                            }
                            "EXPORT" -> when (tool) {
                                "CSV" -> csvSaver.launch("ARKAN_Quantity_Report.csv")
                                "PDF" -> pdfSaver.launch("ARKAN_Report.pdf")
                                "KML" -> showKmlDialog = true
                                "DXF" -> dxfSaver.launch("ARKAN_Sewer_Network.dxf")
                                "DWG" -> dwgSaver.launch("ARKAN_Sewer_Network.dwg")
                            }
                            else -> when (tool) {
                                "PIPE" -> { mode = "PIPE"; pipeStart = null; status = "حدد نقطة بداية الأنبوب ثم نهايته." }
                                "MANHOLE" -> showManholeDialog = true
                                "DIAMETER" -> showPipeDiameterMenu = true
                                "SLOPE" -> showSlopeDialog = true
                            }
                        }
                    })
                }
                if (mode == "POLYLINE" || mode == "MEASURE_AREA") {
                    CadPaletteButton("✓", "FINISH", {
                        if (mode == "POLYLINE") {
                            if (cadDraft.size >= 2) {
                                cadEntities = cadEntities + CadEntity("POLYLINE", cadDraft, currentCadLayer)
                                cadRedo = emptyList()
                                status = "تم إنهاء POLYLINE."
                            } else status = "حدد نقطتين على الأقل."
                            cadDraft = emptyList()
                        } else {
                            if (measureDraft.size >= 3) {
                                val twiceArea = measureDraft.indices.sumOf { i ->
                                    val a = measureDraft[i]; val b = measureDraft[(i + 1) % measureDraft.size]
                                    a.x.toDouble() * b.y - b.x.toDouble() * a.y
                                }
                                val area = kotlin.math.abs(twiceArea) / 2.0 / (PX_PER_METER * PX_PER_METER)
                                val perimeter = measureDraft.indices.sumOf { i ->
                                    val a = measureDraft[i]; val b = measureDraft[(i + 1) % measureDraft.size]
                                    hypot((b.x-a.x).toDouble(), (b.y-a.y).toDouble()) / PX_PER_METER
                                }
                                measureLastResult = "Area: %.2f m² | Perimeter: %.2f m".format(Locale.US, area, perimeter)
                                status = measureLastResult
                            } else status = "يلزم تحديد ثلاث نقاط على الأقل."
                            measureDraft = emptyList(); mode = "PIPE"
                        }
                    })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple("DRAW", "╱", "DRAW"),
                    Triple("EDIT", "✎", "EDIT"),
                    Triple("LAYER", "▤", "LAYER"),
                    Triple("COLOR", "◉", "COLOR"),
                    Triple("MEASURE", "↔", "MEASURE"),
                    Triple("DIMENSION", "↗", "DIMENSION"),
                    Triple("DIAMETER", "Ø", "DIAMETER"),
                    Triple("ANNOTATION", "T", "ANNOTATION"),
                    Triple("SEWER NETWORK", "◎", "SEWER"),
                    Triple("EXPORT", "⇧", "التصدير")
                ).forEach { (tab, icon, title) ->
                    CadPaletteButton(icon, title, { selectedCadTab = tab }, selected = selectedCadTab == tab)
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
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
            Text("استخدم حافظة EXPORT لاختيار صيغة الملف المطلوبة.", color=Color(0xFF9AAAB2), fontSize=10.sp)
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
    }

    if (showSlopeDialog) AlertDialog(
        onDismissRequest = { showSlopeDialog = false },
        title = { Text("نسبة ميل أنابيب الصرف الصحي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = targetSlopePercent,
                    onValueChange = { targetSlopePercent = it },
                    label = { Text("الميل (%)") },
                    placeholder = { Text("مثال: 0.50") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "أدخل الميل كنسبة مئوية؛ مثلاً 0.50 تعني نصف بالمئة. يُستخدم لحساب INV للمنهول التالي تلقائياً عند ترك قيمة INV فارغة.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val slope = targetSlopePercent.toDoubleOrNull()
                if (slope == null || slope <= 0.0 || slope > 100.0) {
                    status = "أدخل نسبة ميل صحيحة أكبر من صفر وحتى 100%."
                } else {
                    targetSlopePercent = String.format(Locale.US, "%.3f", slope).trimEnd('0').trimEnd('.')
                    status = "تم ضبط ميل شبكة الصرف الصحي على $targetSlopePercent%."
                    showSlopeDialog = false
                }
            }) { Text("حفظ الميل") }
        },
        dismissButton = {
            TextButton(onClick = { showSlopeDialog = false }) { Text("إلغاء") }
        }
    )

    if (showNewLayerDialog) AlertDialog(
        onDismissRequest = { showNewLayerDialog = false },
        title = { Text("إنشاء طبقة جديدة / NEW LAYER") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newLayerName, { newLayerName = it }, label = { Text("اسم الطبقة") }, singleLine = true)
            Text("اختر لون الطبقة", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0xFFFFFFFFL, 0xFFFF3030L, 0xFF2878FFFFL, 0xFF20C060L, 0xFFFFD21FL, 0xFF20D8E8L, 0xFF00C8A0L, 0xFFFF9633L).forEach { colorValue ->
                    Box(Modifier.size(30.dp).background(Color(colorValue), RoundedCornerShape(6.dp)).pointerInput(colorValue) { detectTapGestures(onTap = { newLayerColor = colorValue }) })
                }
            }
            Text("الطبقة الجديدة ستصبح الطبقة الحالية للرسم.", color = Color.LightGray, fontSize = 11.sp)
        } },        confirmButton = { TextButton(onClick = {
            val name = newLayerName.trim()
            when {
                name.isBlank() -> status = "أدخل اسم الطبقة."
                cadLayers.any { it.name.equals(name, true) } -> status = "اسم الطبقة موجود مسبقاً."
                else -> { cadLayers = cadLayers + CadLayer(name, newLayerColor); currentCadLayer = name; showNewLayerDialog = false; status = "تم إنشاء الطبقة $name وأصبحت الحالية." }
            }
        }) { Text("إنشاء") } },
        dismissButton = { TextButton(onClick = { showNewLayerDialog = false }) { Text("إلغاء") } }
    )

    if (showClearConfirm) AlertDialog(
        onDismissRequest = { showClearConfirm = false },
        title = { Text("تأكيد مسح الرسم") },
        text = { Text("سيتم حذف عناصر الرسم الحالية والبيانات المستوردة من مساحة العمل. لن يتم حذف ملفات DWG أو DXF الأصلية من الهاتف. هل تريد المتابعة؟") },
        confirmButton = {
            TextButton(onClick = {
                pipes = emptyList()
                manholes = emptyList()
                cadEntities = emptyList()
                cadRedo = emptyList()
                cadEditSelection = null
                cadDraft = emptyList()
                pipeStart = null
                pendingManhole = null
                importedKml = null
                importedSummary = ""
                importedLayerLengths = emptyMap()
                importedManholeCount = 0
                importedManholes = emptyList()
                importedPaths = emptyList()
                importedDiameterLengths = emptyMap()
                importedUnclassifiedGeometryLength = 0.0
                dxfFileName = "لم يتم اختيار ملف"
                mode = "PIPE"
                showClearConfirm = false
                status = "تم مسح الرسم والبيانات من مساحة العمل."
            }) { Text("نعم، امسح") }
        },
        dismissButton = {
            TextButton(onClick = { showClearConfirm = false }) { Text("إلغاء") }
        }
    )

    if(showManholeDialog) AlertDialog(
        onDismissRequest={showManholeDialog=false}, title={Text("بيانات المنهول")},
        text={ Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(mhId,{mhId=it},label={Text("رقم المنهول")},singleLine=true)
            OutlinedTextField(mhGroundLevel,{mhGroundLevel=it},label={Text("GL / منسوب سطح الأرض")},singleLine=true)
            OutlinedTextField(mhInvertLevel,{mhInvertLevel=it},label={Text("INV / منسوب قاع الأنبوب (اتركه فارغاً للحساب التلقائي)")},singleLine=true)
            val previewDepth = mhGroundLevel.toDoubleOrNull()?.let { gl -> mhInvertLevel.toDoubleOrNull()?.let { inv -> gl - inv } } ?: 0.0
            val automaticType = when { previewDepth <= 0.0 -> "بانتظار INV"; previewDepth <= 1.70 -> "AS"; previewDepth <= 3.00 -> "BS"; previewDepth <= 4.00 -> "CS"; else -> "CD" }
            OutlinedTextField(value = automaticType, onValueChange = {}, label = { Text("نوع المنهولة (تلقائي)") }, modifier = Modifier.fillMaxWidth(), readOnly = true, singleLine = true)
            Text("اضبط نسبة الميل من الخيار الرابع داخل حافظة شبكة الصرف الصحي. أدخل GL لكل منهول وINV للمنهول الأول؛ ويمكن ترك INV للمناهيل التالية فارغاً ليحسبه البرنامج وفق الميل وطول الأنبوب. نوع المنهول يُحدد تلقائياً.", color = Color.LightGray, fontSize = 11.sp)
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

    if (showGeoDialog) AlertDialog(
        onDismissRequest = { showGeoDialog = false },
        title = { Text("النقطة المرجعية UTM / Zone ${utmZone}N") },
        text = { Column {
            Text("أدخل إحداثيات الزاوية العليا اليسرى. اختر رقم منطقة UTM الصحيح للمشروع (1 إلى 60). الافتراضي للعراق Zone 38N.")
            OutlinedTextField(utmZone, { utmZone = it.filter(Char::isDigit).take(2) }, label = { Text("UTM Zone (1–60)") }, singleLine = true)
            OutlinedTextField(referenceEasting, { referenceEasting = it }, label = { Text("Easting (E) بالمتر") }, singleLine = true)
            OutlinedTextField(referenceNorthing, { referenceNorthing = it }, label = { Text("Northing (N) بالمتر") }, singleLine = true)
            Text("يلزم إدخال نقطة مرجعية معروفة؛ المسافات وحدها لا تحدد الموقع الحقيقي.")
        } },
        confirmButton = { TextButton(onClick = {
            val e0 = referenceEasting.toDoubleOrNull()
            val n0 = referenceNorthing.toDoubleOrNull()
            if (e0 == null || n0 == null || e0 !in 100000.0..900000.0 || n0 !in 0.0..10000000.0 || (utmZone.toIntOrNull() ?: 0) !in 1..60) {
                status = "أدخل إحداثيات UTM صحيحة."
            } else {
                manholes = manholes.map { mh ->
                    val (e, n) = localToUtm(mh.point, e0, n0)
                    mh.copy(easting = e, northing = n, coordinatesKnown = true)
                }
                showGeoDialog = false
                status = "تم ضبط مرجع UTM Zone ${utmZone}N وتحديث إحداثيات المناهيل."
            }
        }) { Text("حفظ المرجع") } },
        dismissButton = { TextButton(onClick = { showGeoDialog = false }) { Text("إلغاء") } }
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


private fun buildArkanDxf(
    pipes: List<Pipe>,
    manholes: List<Manhole>,
    cadEntities: List<CadEntity>,
    originEasting: Double,
    originNorthing: Double,
    importedPaths: List<ImportedPath> = emptyList(),
    importedCoordinatesAreUtm: Boolean = false
): String {
    val out = StringBuilder()
    fun pair(code: Int, value: Any) { out.append(code).append("\r\n").append(value).append("\r\n") }
    fun entity(type: String, layer: String) { pair(0, type); pair(8, layer) }
    pair(0, "SECTION"); pair(2, "HEADER")
    pair(9, "$" + "INSUNITS"); pair(70, 6)
    pair(9, "$" + "MEASUREMENT"); pair(70, 1)
    pair(0, "ENDSEC"); pair(0, "SECTION"); pair(2, "ENTITIES")
    if (importedCoordinatesAreUtm) {
        importedPaths.forEach { path ->
            if (path.points.size >= 2) {
                entity("LWPOLYLINE", path.layer.ifBlank { "SEWER" })
                pair(90, path.points.size); pair(70, 0)
                path.points.forEach { (easting, northing) -> pair(10, easting); pair(20, northing) }
            }
        }
        manholes.forEach { mh ->
            val x = mh.easting
            val y = mh.northing
            if (mh.isCircular) {
                entity("CIRCLE", "MANHOLES")
                pair(10, x); pair(20, y); pair(30, mh.altitude); pair(40, 0.7318 / 2.0)
            } else {
                val hx = 0.70 / 2.0; val hy = 1.10 / 2.0
                entity("LWPOLYLINE", "MANHOLES")
                pair(90, 4); pair(70, 1)
                listOf(x-hx to y-hy, x+hx to y-hy, x+hx to y+hy, x-hx to y+hy).forEach { (vx, vy) ->
                    pair(10, vx); pair(20, vy)
                }
            }
            entity("TEXT", "MANHOLE-LABELS")
            pair(10, x); pair(20, y + 0.45); pair(30, mh.altitude); pair(40, 0.20); pair(1, mh.id)
        }
    } else {
        pipes.forEach { pipe ->
            entity("LINE", pipe.layer.ifBlank { "SEWER" })
            val (ax, ay) = localToUtm(pipe.a, originEasting, originNorthing)
            val (bx, by) = localToUtm(pipe.b, originEasting, originNorthing)
            pair(10, ax); pair(20, ay); pair(30, 0)
            pair(11, bx); pair(21, by); pair(31, 0)
        }
    }
    cadEntities.forEach { cad ->
        val pts = when (cad.type) {
            "ARC" -> arcPoints(cad.points)
            "RECTANGLE" -> if (cad.points.size >= 2) {
                val a = cad.points[0]; val b = cad.points[1]
                listOf(Offset(minOf(a.x,b.x),minOf(a.y,b.y)), Offset(maxOf(a.x,b.x),minOf(a.y,b.y)), Offset(maxOf(a.x,b.x),maxOf(a.y,b.y)), Offset(minOf(a.x,b.x),maxOf(a.y,b.y)))
            } else emptyList()
            else -> cad.points
        }
        when (cad.type) {
            "LINE" -> if (pts.size >= 2) {
                entity("LINE", cad.layer)
                val (x1,y1)=localToUtm(pts[0],originEasting,originNorthing)
                val (x2,y2)=localToUtm(pts[1],originEasting,originNorthing)
                pair(10,x1); pair(20,y1); pair(30,0); pair(11,x2); pair(21,y2); pair(31,0)
            }
            "CIRCLE" -> if (pts.size >= 2) {
                val (x,y)=localToUtm(pts[0],originEasting,originNorthing)
                val radius=hypot((pts[1].x-pts[0].x).toDouble(),(pts[1].y-pts[0].y).toDouble())/PX_PER_METER
                entity("CIRCLE",cad.layer); pair(10,x); pair(20,y); pair(30,0); pair(40,radius)
            }
            "TEXT" -> if (cad.points.isNotEmpty()) {
                val (x,y)=localToUtm(cad.points[0],originEasting,originNorthing)
                entity("TEXT",cad.layer); pair(10,x); pair(20,y); pair(30,0); pair(40,0.40); pair(1,cad.text)
            }
            "POLYLINE","RECTANGLE","ARC" -> if (pts.size >= 2) {
                entity("LWPOLYLINE",cad.layer); pair(90,pts.size); pair(70,if(cad.type=="RECTANGLE") 1 else 0)
                pts.forEach { p -> val (x,y)=localToUtm(p,originEasting,originNorthing); pair(10,x); pair(20,y) }
            }
        }
    }
    if (!importedCoordinatesAreUtm) manholes.forEach { mh ->
        val (x, y) = localToUtm(mh.point, originEasting, originNorthing)
        if (mh.isCircular) {
            entity("CIRCLE", "MANHOLES")
            pair(10, x); pair(20, y); pair(30, 0); pair(40, 0.7318 / 2.0)
        } else {
            val hx = 0.70 / 2.0; val hy = 1.10 / 2.0
            entity("LWPOLYLINE", "MANHOLES")
            pair(90, 4); pair(70, 1)
            listOf(x-hx to y-hy, x+hx to y-hy, x+hx to y+hy, x-hx to y+hy).forEach { (vx, vy) ->
                pair(10, vx); pair(20, vy)
            }
        }
        entity("TEXT", "MANHOLE-LABELS")
        pair(10, x); pair(20, y + 0.45); pair(30, 0); pair(40, 0.20); pair(1, mh.id)
    }
    pair(0, "ENDSEC"); pair(0, "EOF")
    return out.toString()
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
            ArkanXlsxCell("مجموع الأطوال المعلنة حسب القطر"),            ArkanXlsxCell("", diameterLengths.values.sum(), 3),
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
        val paths: List<ImportedPath>,
        val diameterLengths: Map<Int, Double>,
        val unclassifiedGeometryLength: Double
    )
    private data class PairCode(val code: Int, val value: String)
    private data class GeoPoint(val lon: Double, val lat: Double, val alt: Double)
    private data class DxfPosition(val east: Double, val north: Double, val alt: Double)
    private data class DxfLabel(val east: Double, val north: Double, val text: String)
    fun convertManual(manholes: List<Manhole>, pipes: List<Pipe>, zone: Int): Pair<String, Int> {
        require(manholes.isNotEmpty()) { "لا توجد مناهيل للتصدير" }
        val placemarks = StringBuilder()
        manholes.forEach { mh ->
            val geo = utmToWgs84(mh.easting, mh.northing, mh.altitude, zone)
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
                    val a = utmToWgs84(start.easting, start.northing, start.altitude, zone)
                    val b = utmToWgs84(end.easting, end.northing, end.altitude, zone)
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

    fun convert(raw: String, zone: Int): ImportResult {
        require(zone in 1..60) { "رقم منطقة UTM يجب أن يكون بين 1 و60." }
        // DXF is a sequence of group-code/value lines. Be tolerant of BOM,
        // CRLF/LF, blank lines and malformed records instead of losing pair alignment.
        val normalized = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n').toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        require(lines.size % 2 == 0) {
            "ملف DXF النصي غير مكتمل: عدد أسطر الأكواد والقيم غير متطابق."
        }
        val pairs = mutableListOf<PairCode>()
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().removePrefix("\uFEFF").toIntOrNull()
                ?: error("صيغة DXF غير صحيحة عند السطر ${i + 1}: كود المجموعة ليس رقماً.")
            pairs += PairCode(code, lines[i + 1].trim())
            i += 2
        }

        // A section starts with 0/SECTION followed by 2/ENTITIES.
        val sectionMarker = pairs.indices.firstOrNull { index ->
            pairs[index].code == 0 && pairs[index].value.equals("SECTION", true) &&                index + 1 < pairs.size && pairs[index + 1].code == 2 &&
                pairs[index + 1].value.equals("ENTITIES", true)
        }
        require(sectionMarker != null) {
            val preview = normalized.take(100).replace("\n", " ").replace("\t", " ")
            "تعذر العثور على قسم ENTITIES في DXF النصي. بداية الملف: $preview"
        }

        val entities = mutableListOf<List<PairCode>>()
        var current = mutableListOf<PairCode>()
        var inEntities = false
        for (p in pairs.drop(sectionMarker + 2)) {
            if (!inEntities) inEntities = true
            if (p.code == 0 && p.value.equals("ENDSEC", true)) {
                if (current.isNotEmpty()) entities.add(current)
                break
            }
            if (p.code == 0) {
                if (current.isNotEmpty()) entities.add(current)
                current = mutableListOf(p)
            } else if (inEntities) {
                current.add(p)
            }
        }
        // Join classic POLYLINE records with their following VERTEX records.
        val expandedEntities = mutableListOf<List<PairCode>>()
        var entityIndex = 0
        while (entityIndex < entities.size) {
            val entity = entities[entityIndex]
            val entityName = entity.firstOrNull()?.value?.uppercase(Locale.ROOT)
            if (entityName == "POLYLINE") {
                val combined = entity.toMutableList()
                var vertexIndex = entityIndex + 1
                while (vertexIndex < entities.size) {
                    val next = entities[vertexIndex]
                    val nextType = next.firstOrNull()?.value?.uppercase(Locale.ROOT)
                    if (nextType == "SEQEND") break
                    if (nextType != "VERTEX") break
                    next.filter { it.code == 10 || it.code == 20 }.forEach { combined.add(it) }
                    vertexIndex++
                }
                expandedEntities.add(combined)
                entityIndex = if (vertexIndex < entities.size &&
                    entities[vertexIndex].firstOrNull()?.value.equals("SEQEND", true)) vertexIndex + 1 else vertexIndex
            } else {
                if (entityName != "VERTEX" && entityName != "SEQEND") expandedEntities.add(entity)
                entityIndex++
            }
        }
        entities.clear()
        entities.addAll(expandedEntities)

        // Expand ordinary DXF block references (INSERT) so drawings made from
        // reusable symbols are visible in the plan view, not silently discarded.
        data class BlockDefinition(val baseX: Double, val baseY: Double, val records: List<List<PairCode>>)
        val blockDefinitions = mutableMapOf<String, BlockDefinition>()
        val blocksSection = pairs.indices.firstOrNull { index ->
            pairs[index].code == 0 && pairs[index].value.equals("SECTION", true) &&
                index + 1 < pairs.size && pairs[index + 1].code == 2 &&
                pairs[index + 1].value.equals("BLOCKS", true)
        }
        if (blocksSection != null) {
            var cursor = blocksSection + 2
            while (cursor < pairs.size && !(pairs[cursor].code == 0 && pairs[cursor].value.equals("ENDSEC", true))) {
                if (pairs[cursor].code != 0 || !pairs[cursor].value.equals("BLOCK", true)) {
                    cursor++
                    continue
                }
                val headerStart = cursor
                cursor++
                val header = mutableListOf<PairCode>()
                while (cursor < pairs.size && pairs[cursor].code != 0) header.add(pairs[cursor++])
                val blockName = header.firstOrNull { it.code == 2 }?.value.orEmpty()
                val baseX = header.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull() ?: 0.0
                val baseY = header.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull() ?: 0.0
                val records = mutableListOf<List<PairCode>>()
                var record = mutableListOf<PairCode>()
                while (cursor < pairs.size &&
                    !(pairs[cursor].code == 0 && pairs[cursor].value.equals("ENDBLK", true))) {
                    val item = pairs[cursor++]
                    if (item.code == 0) {
                        if (record.isNotEmpty()) records.add(record)
                        record = mutableListOf(item)
                    } else if (record.isNotEmpty()) record.add(item)
                }
                if (record.isNotEmpty()) records.add(record)
                if (blockName.isNotBlank()) blockDefinitions[blockName.uppercase(Locale.ROOT)] =
                    BlockDefinition(baseX, baseY, records.filter { rec ->
                        rec.firstOrNull()?.value?.uppercase(Locale.ROOT) in
                            setOf("LINE", "LWPOLYLINE", "POLYLINE", "CIRCLE", "ARC", "ELLIPSE", "SPLINE", "POINT", "SOLID", "TRACE", "3DFACE")
                    })
                if (cursor < pairs.size) cursor++ // ENDBLK
                if (cursor == headerStart) cursor++
            }
        }
        val expandedBlocks = mutableListOf<List<PairCode>>()
        entities.filter { it.firstOrNull()?.value.equals("INSERT", true) }.forEach { insert ->
            val blockName = insert.firstOrNull { it.code == 2 }?.value?.uppercase(Locale.ROOT) ?: return@forEach
            val definition = blockDefinitions[blockName] ?: return@forEach
            val tx = insert.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull() ?: 0.0
            val ty = insert.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull() ?: 0.0
            val sx = insert.firstOrNull { it.code == 41 }?.value?.toDoubleOrNull() ?: 1.0
            val sy = insert.firstOrNull { it.code == 42 }?.value?.toDoubleOrNull() ?: 1.0
            val angle = Math.toRadians(insert.firstOrNull { it.code == 50 }?.value?.toDoubleOrNull() ?: 0.0)
            val cs = kotlin.math.cos(angle)
            val sn = kotlin.math.sin(angle)
            val insertLayer = insert.firstOrNull { it.code == 8 }?.value ?: "0"
            definition.records.forEach { child ->
                val childType = child.firstOrNull()?.value?.uppercase(Locale.ROOT) ?: return@forEach
                val coordinateCodes = mapOf(10 to 20, 11 to 21, 12 to 22, 13 to 23, 14 to 24)
                val transformed = child.map { pair ->
                    val yCode = coordinateCodes[pair.code]
                    if (yCode == null) pair else {
                        val x = pair.value.toDoubleOrNull()
                        val y = child.firstOrNull { it.code == yCode }?.value?.toDoubleOrNull()
                        if (x == null || y == null) pair else {
                            val localX = (x - definition.baseX) * sx
                            val localY = (y - definition.baseY) * sy
                            PairCode(pair.code, (tx + localX * cs - localY * sn).toString())
                        }
                    }
                }.map { pair ->
                    val xCode = coordinateCodes.entries.firstOrNull { it.value == pair.code }?.key
                    if (xCode == null) pair else {
                        val x = child.firstOrNull { it.code == xCode }?.value?.toDoubleOrNull()
                        val y = pair.value.toDoubleOrNull()
                        if (x == null || y == null) pair else {
                            val localX = (x - definition.baseX) * sx
                            val localY = (y - definition.baseY) * sy
                            PairCode(pair.code, (ty + localX * sn + localY * cs).toString())
                        }
                    }
                }.map { pair ->
                    if (pair.code == 8 && pair.value == "0") PairCode(8, insertLayer) else pair
                }
                expandedBlocks.add(listOf(PairCode(0, childType)) + transformed.drop(1))
            }
        }
        entities.addAll(expandedBlocks)

        val placemarks = StringBuilder()
        var pipeCount = 0
        val layerLengths = mutableMapOf<String, Double>()
        val importedPaths = mutableListOf<ImportedPath>()
        // Use named pipe layers to exclude site boundaries and decorative geometry from pipe quantities.
        val hasNamedPipeLayers = entities.any { entity ->
            val type = entity.firstOrNull()?.value?.uppercase(Locale.ROOT) ?: ""
            val layerName = entity.firstOrNull { it.code == 8 }?.value.orEmpty()
            type in setOf("LINE", "LWPOLYLINE", "POLYLINE", "CIRCLE", "ARC", "SPLINE", "ELLIPSE") &&
                isPipeLayerName(layerName)
        }
        val structurePositions = entities.filter { e ->
            val block = e.firstOrNull { it.code == 2 }?.value.orEmpty()
            val layer = e.firstOrNull { it.code == 8 }?.value.orEmpty()
            e.firstOrNull()?.value.equals("INSERT", true) && (block + " " + layer).contains(Regex("(?i)(MH|MANHOLE|STRUCT|SSWR|SEWER|CHAMBER)"))
        }.mapNotNull { e ->
            val east = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val north = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val alt = e.firstOrNull { it.code == 30 }?.value?.toDoubleOrNull() ?: 0.0
            DxfPosition(east, north, alt)
        }
        val labels = entities.filter { e ->
            val type = e.firstOrNull()?.value.orEmpty().uppercase(Locale.ROOT)
            type == "TEXT" || type == "MTEXT"
        }.mapNotNull { e ->
            val east = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val north = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull() ?: return@mapNotNull null
            val labelText = e.filter { it.code == 3 || it.code == 1 }.joinToString("") { it.value }.ifBlank { return@mapNotNull null }
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
            // Only associate nearby annotation labels; never reuse a distant label for every manhole.
            val nearestStructure = labels.filter { it.text.contains("STRUCTURE", true) }
                .map { it to hypot(it.east - pos.east, it.north - pos.north) }
                .filter { it.second <= 8.0 }.minByOrNull { it.second }?.first
            val nearestDepth = labels.filter { Regex("""(?i)h\s*:\s*[-+]?\d+(?:\.\d+)?""").containsMatchIn(it.text) }
                .map { it to hypot(it.east - pos.east, it.north - pos.north) }
                .filter { it.second <= 8.0 }.minByOrNull { it.second }?.first
            val extractedId = nearestStructure?.text?.let { Regex("""\(([^)]+)\)""").find(it)?.groupValues?.getOrNull(1) }
            val extractedDepth = nearestDepth?.text?.let { Regex("""(?i)h\s*:\s*([-+]?\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() } ?: 0.0
            Manhole(extractedId?.let { "MH-$it" } ?: "MH-%02d".format(Locale.US, index + 1), Offset.Zero, extractedDepth, if (extractedDepth >= 1.70) 0.7318 else 1.10, if (extractedDepth >= 1.70) 0.7318 else 0.70, pos.north, pos.east, pos.alt, coordinatesKnown = false)
        }
        importedManholes.forEach { mh ->
            val geo = utmToWgs84(mh.easting, mh.northing, mh.altitude, zone)
            val safeName = xmlEscape(mh.id)
            placemarks.append("<Placemark><name>$safeName</name><Point><coordinates>${fmt(geo.lon)},${fmt(geo.lat)},${fmt(geo.alt)}</coordinates></Point></Placemark>")
        }
        val mhCount = importedManholes.size
        for (e in entities) {
            val type = e.firstOrNull()?.value?.uppercase(Locale.ROOT) ?: continue
            val layer = e.firstOrNull { it.code == 8 }?.value ?: ""
            if (type.equals("LINE", true) || type.equals("LWPOLYLINE", true) || type.equals("POLYLINE", true) || type.equals("CIRCLE", true) || type.equals("ARC", true) || type.equals("SPLINE", true) || type.equals("ELLIPSE", true)) {
                val pts = mutableListOf<Pair<Double, Double>>()
                if (type.equals("LINE", true)) {
                    val x1 = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull()
                    val y1 = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull()
                    val x2 = e.firstOrNull { it.code == 11 }?.value?.toDoubleOrNull()
                    val y2 = e.firstOrNull { it.code == 21 }?.value?.toDoubleOrNull()
                    if (x1 != null && y1 != null && x2 != null && y2 != null) { pts.add(x1 to y1); pts.add(x2 to y2) }
                } else if (type.equals("CIRCLE", true) || type.equals("ARC", true)) {
                    val cx = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull()
                    val cy = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull()
                    val radius = e.firstOrNull { it.code == 40 }?.value?.toDoubleOrNull()
                    if (cx != null && cy != null && radius != null && radius > 0.0) {
                        val startAngle = if (type.equals("ARC", true)) e.firstOrNull { it.code == 50 }?.value?.toDoubleOrNull() ?: 0.0 else 0.0
                        val endAngle = if (type.equals("ARC", true)) e.firstOrNull { it.code == 51 }?.value?.toDoubleOrNull() ?: 360.0 else 360.0
                        var sweep = endAngle - startAngle
                        while (sweep <= 0.0) sweep += 360.0
                        val steps = (sweep / 7.5).toInt().coerceIn(8, 96)
                        for (step in 0..steps) {
                            val angle = Math.toRadians(startAngle + sweep * step / steps)
                            pts.add((cx + radius * kotlin.math.cos(angle)) to (cy + radius * kotlin.math.sin(angle)))
                        }
                    }
                } else if (type.equals("ELLIPSE", true)) {
                    val cx = e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull()
                    val cy = e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull()
                    val majorX = e.firstOrNull { it.code == 11 }?.value?.toDoubleOrNull()
                    val majorY = e.firstOrNull { it.code == 21 }?.value?.toDoubleOrNull()
                    val ratio = e.firstOrNull { it.code == 40 }?.value?.toDoubleOrNull()
                    if (cx != null && cy != null && majorX != null && majorY != null && ratio != null && ratio > 0.0) {
                        val startParam = e.firstOrNull { it.code == 41 }?.value?.toDoubleOrNull() ?: 0.0
                        val endParam = e.firstOrNull { it.code == 42 }?.value?.toDoubleOrNull() ?: (2.0 * Math.PI)
                        var sweep = endParam - startParam
                        while (sweep <= 0.0) sweep += 2.0 * Math.PI
                        val steps = (sweep / (Math.PI / 24.0)).toInt().coerceIn(8, 192)
                        val minorX = -majorY * ratio
                        val minorY = majorX * ratio
                        for (step in 0..steps) {
                            val angle = startParam + sweep * step / steps
                            pts.add((cx + majorX * kotlin.math.cos(angle) + minorX * kotlin.math.sin(angle)) to
                                (cy + majorY * kotlin.math.cos(angle) + minorY * kotlin.math.sin(angle)))
                        }
                    }
                } else if (type.equals("SPLINE", true)) {
                    fun collectPoints(codeX: Int, codeY: Int): List<Pair<Double, Double>> {
                        val found = mutableListOf<Pair<Double, Double>>()
                        var x: Double? = null
                        for (p in e.drop(1)) {
                            if (p.code == codeX) x = p.value.toDoubleOrNull()
                            if (p.code == codeY && x != null) {
                                p.value.toDoubleOrNull()?.let { found.add(x!! to it) }
                                x = null
                            }
                        }
                        return found
                    }
                    val fitPoints = collectPoints(11, 21)
                    val controlPoints = collectPoints(10, 20)
                    pts.addAll(if (fitPoints.size >= 2) fitPoints else controlPoints)
                    val flags = e.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0
                    if ((flags and 1) != 0 && pts.size > 2) pts.add(pts.first())
                } else {
                    var x: Double? = null
                    for (p in e.drop(1)) {
                        if (p.code == 10) x = p.value.toDoubleOrNull()
                        if (p.code == 20 && x != null) {
                            val y = p.value.toDoubleOrNull()
                            if (y != null) pts.add(x!! to y)
                            x = null
                        }
                    }
                    val flags = e.firstOrNull { it.code == 70 }?.value?.toIntOrNull() ?: 0
                    if ((flags and 1) != 0 && pts.size > 2) pts.add(pts.first())
                }
                if (pts.size >= 2) {
                    val length = pts.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
                    val isPipeGeometry = !hasNamedPipeLayers || isPipeLayerName(layer)
                    if (isPipeGeometry && length.isFinite() && length > 0.0 && length < 100000000.0) {
                        layerLengths[layer] = (layerLengths[layer] ?: 0.0) + length
                        importedPaths += ImportedPath(layer, pts.toList())
                        val coords = pts.joinToString(" ") { (east, north) ->
                            val g = utmToWgs84(east, north, 0.0, zone)
                            "${fmt(g.lon)},${fmt(g.lat)},${fmt(g.alt)}"
                        }
                        pipeCount++
                        placemarks.append("<Placemark><name>Sewer pipe $pipeCount</name><Style><LineStyle><color>ff00ffff</color><width>3</width></LineStyle></Style><LineString><tessellate>1</tessellate><coordinates>$coords</coordinates></LineString></Placemark>")
                    }
                }
            }
        }
        val entityTypes = entities.mapNotNull { it.firstOrNull()?.value?.uppercase(Locale.ROOT) }.groupingBy { it }.eachCount().toSortedMap()
        require(pipeCount > 0 || mhCount > 0) {
            "تمت قراءة قسم ENTITIES (" + entities.size + " عنصر) لكن لم تُستخرج هندسة مدعومة. الأنواع الموجودة: " +
                entityTypes.entries.joinToString { it.key + "=" + it.value } +
                ". يدعم LINE وLWPOLYLINE وPOLYLINE وCIRCLE وARC وELLIPSE وSPLINE؛ ويتطلب INSERT تسمية بلوك/طبقة منهول."
        }
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>ARKAN Sewer Network</name>$placemarks</Document></kml>"""
        val geometricCenterlineLength = layerLengths.values.sum()
        val labelledDiameterLength = diameterLengths.values.sum()
        val unclassifiedGeometryLength = geometricCenterlineLength - labelledDiameterLength
        return ImportResult(
            kml,
            "خطوط: $pipeCount | مناهيل: $mhCount | أقطار موسومة: ${diameterLengths.size} | كيانات DXF: ${entities.size}",
            layerLengths,
            mhCount,
            importedManholes,
            importedPaths,
            diameterLengths,
            unclassifiedGeometryLength
        )
    }
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun fmt(v: Double) = String.format(Locale.US, "%.8f", v)
    private fun utmToWgs84(easting: Double, northing: Double, altitude: Double, zone: Int): GeoPoint {
        require(zone in 1..60) { "رقم منطقة UTM غير صالح." }
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
        val lon = Math.toRadians(zone * 6.0 - 183.0) + (q5 - q6 + q7) / kotlin.math.cos(fp)
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