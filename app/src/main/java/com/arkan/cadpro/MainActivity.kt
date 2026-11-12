package com.arkan.cadpro

import android.net.Uri
import android.os.Bundle
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

private const val PX_PER_METER = 40f
private data class Pipe(val a: Offset, val b: Offset, val layer: String)
private data class Manhole(val id: String, val point: Offset, val depth: Double, val length: Double, val width: Double, val northing: Double, val easting: Double) {
    val type: String get() = when { depth <= 1.70 -> "AS"; depth <= 3.00 -> "BS"; depth <= 4.00 -> "CD"; else -> "خارج التصنيف" }
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
    var lispName by remember { mutableStateOf("لم يتم اختيار ملف LSP") }
    var status by remember { mutableStateOf("اختر أداة ثم المس مساحة الرسم.") }
    var showManholeDialog by remember { mutableStateOf(false) }
    var showKmlDialog by remember { mutableStateOf(false) }
    var longitude by remember { mutableStateOf("46.257") }
    var latitude by remember { mutableStateOf("31.057") }
    var mhId by remember { mutableStateOf("MH-01") }
    var mhDepth by remember { mutableStateOf("1.50") }
    var mhLength by remember { mutableStateOf("1.10") }
    var mhWidth by remember { mutableStateOf("1.10") }
    var mhNorthing by remember { mutableStateOf("0.0") }
    var mhEasting by remember { mutableStateOf("0.0") }
    var pendingManhole by remember { mutableStateOf<Manhole?>(null) }
    val context = LocalContext.current

    val lispPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { lispName = uri.lastPathSegment ?: "LSP file selected"; status = "تم اختيار ملف LISP. شغّله في AutoCAD المكتبي باستخدام APPLOAD." }
    }
    val kmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri: Uri? ->
        if (uri != null) try {
            val lon = longitude.toDouble(); val lat = latitude.toDouble()
            require(lon in -180.0..180.0 && lat in -90.0..90.0)
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(KmlPointWriter.point("ARKAN reference point", lon, lat)) } }
            status = "تم حفظ نقطة KML المرجعية. الرسم المحلي لم يُحوّل إلى WGS84."
        } catch (_: Exception) { status = "إحداثيات WGS84 غير صحيحة." }
    }

    val totalLength = pipes.sumOf { hypot((it.b.x-it.a.x).toDouble(), (it.b.y-it.a.y).toDouble()) / PX_PER_METER }
    val totalExcavation = manholes.sumOf { it.excavation }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00C8A0), background = Color(0xFF101418), surface = Color(0xFF1B2329))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(10.dp)) {
            Text("ARKAN CAD PRO", color = Color(0xFF00C8A0), fontSize = 24.sp)
            Text("V1.1 • Sewer drafting & quantities", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { mode = "PIPE"; pipeStart = null }) { Text("رسم أنبوب") }
                Button(onClick = { showManholeDialog = true }) { Text("إضافة منهول") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(pipeLayer, { pipeLayer = it }, label = { Text("Layer الأنبوب") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { lispPicker.launch(arrayOf("*/*")) }) { Text("LOAD LSP") }
            }
            Text(if (mode == "PIPE") "المس نقطتين لرسم مقطع أنبوب." else "المس مساحة الرسم لتحديد موقع المنهول.", color = Color(0xFFB8C7CE), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080B0E), RoundedCornerShape(8.dp)).pointerInput(mode, pendingManhole, pipeLayer) {
                detectTapGestures { tap ->
                    when {
                        pendingManhole != null -> {
                            val mh = pendingManhole!!.copy(point = tap)
                            manholes = manholes + mh; pendingManhole = null; status = "تمت إضافة ${mh.id} (${mh.type})"
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
            Text("الأطوال: %.2f م | المناهيل: %d | حفر المناهيل التقديري: %.2f م³".format(Locale.US,totalLength,manholes.size,totalExcavation),color=Color(0xFF00C8A0),fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick={showKmlDialog=true}) { Text("EXPORT KML") }
                Button(onClick={pipes=emptyList();manholes=emptyList();pipeStart=null;pendingManhole=null;status="تم مسح الرسم."}) { Text("CLEAR") }
            }
            Text("ملف LSP: $lispName",color=Color.LightGray,fontSize=10.sp)
            if(manholes.isNotEmpty()) {
                Text("جدول المناهيل",color=Color.White,fontSize=14.sp)
                Column(Modifier.heightIn(max=110.dp).verticalScroll(rememberScrollState())) {
                    manholes.forEach { mh -> Text("${mh.id} | ${mh.type} | عمق ${mh.depth}م | N ${mh.northing} | E ${mh.easting} | حفر %.2fم³".format(Locale.US,mh.excavation),color=Color(0xFFB8C7CE),fontSize=11.sp) }
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
            Text("التصنيف: AS حتى 1.70م، BS حتى 3.00م، CD حتى 4.00م.")
        }},
        confirmButton={ TextButton(onClick={
            try {
                val d=mhDepth.toDouble(); val l=mhLength.toDouble(); val w=mhWidth.toDouble()
                require(d>0 && l>0 && w>0 && d<=4.0)
                pendingManhole=Manhole(mhId.ifBlank{"MH-${manholes.size+1}"},Offset.Zero,d,l,w,mhNorthing.toDouble(),mhEasting.toDouble())
                mode="MANHOLE";showManholeDialog=false;status="المس مساحة الرسم لتحديد موقع المنهول."
            } catch(_:Exception) { status="تحقق من الأرقام؛ العمق يجب ألا يتجاوز 4.00م." }
        }) { Text("التالي: تحديد الموقع") }},
        dismissButton={TextButton(onClick={showManholeDialog=false}) { Text("إلغاء") }}
    )

    if(showKmlDialog) AlertDialog(
        onDismissRequest={showKmlDialog=false},title={Text("نقطة مرجعية KML / WGS84")},
        text={Column {
            OutlinedTextField(longitude,{longitude=it},label={Text("Longitude")})
            OutlinedTextField(latitude,{latitude=it},label={Text("Latitude")})
            Text("حالياً يصدر التطبيق نقطة مرجعية فقط. لا تدخل N/E هنا؛ تحويل UTM سيضاف بعد تحديد المنطقة ونظام الإحداثيات.")
        }},
        confirmButton={TextButton(onClick={showKmlDialog=false;kmlSaver.launch("ARKAN_reference.kml")}) { Text("حفظ KML") }},
        dismissButton={TextButton(onClick={showKmlDialog=false}) { Text("إلغاء") }}
    )
}

private object KmlPointWriter {
    fun point(name:String,longitude:Double,latitude:Double):String {
        val safe=name.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>$safe</name><Point><coordinates>${String.format(Locale.US,"%.8f,%.8f,0",longitude,latitude)}</coordinates></Point></Placemark></Document></kml>
"""
    }
}
