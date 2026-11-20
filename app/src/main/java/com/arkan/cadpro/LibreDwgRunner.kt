package com.arkan.cadpro

import android.content.Context
import android.net.Uri
import java.io.File

internal object LibreDwgRunner {
    private fun converter(context: Context, name: String): File {
        val libraryName = when (name) {
            "dwg2dxf" -> "libdwg2dxf.so"
            "dxf2dwg" -> "libdxf2dwg.so"
            "dwgread" -> "libdwgread.so"
            else -> error("اسم محول DWG غير صالح.")
        }
        val executable = File(context.applicationInfo.nativeLibraryDir, libraryName)
        check(executable.isFile && executable.length() > 0L && executable.canExecute()) {
            "أداة DWG $name غير موجودة أو لا يسمح Android بتشغيلها."
        }
        return executable
    }

    private fun execute(command: List<String>, output: File): String {
        output.delete()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0 && output.isFile && output.length() > 0L) {
            "فشل التحويل (رمز $exit): ${log.take(1600)}"
        }
        return log
    }

    private fun runDwg2Dxf(exe: File, input: File, output: File, minimal: Boolean): String {
        val cmd = mutableListOf(exe.absolutePath)
        cmd += "-v3"
        if (minimal) cmd += "-m"
        cmd += listOf("-o", output.absolutePath, "-y", input.absolutePath)
        return execute(cmd, output)
    }

    private fun runDwgRead(exe: File, input: File, output: File): String =
        execute(listOf(exe.absolutePath, "-O", "DXF", "-o", output.absolutePath, input.absolutePath), output)

    fun convertDwgToDxf(context: Context, uri: Uri): File {
        val stamp = System.currentTimeMillis()
        val input = File(context.cacheDir, "input-$stamp.dwg")
        context.contentResolver.openInputStream(uri)?.use { source ->
            input.outputStream().use { target -> source.copyTo(target) }
        } ?: error("تعذر قراءة ملف DWG من موفر الملفات.")
        check(input.length() >= 16L) { "ملف DWG فارغ أو غير مكتمل." }

        val output = File(context.cacheDir, "converted-$stamp.dxf")
        val minimal = File(context.cacheDir, "converted-$stamp-minimal.dxf")
        val readOutput = File(context.cacheDir, "converted-$stamp-dwgread.dxf")
        val errors = mutableListOf<String>()

        try {
            runDwg2Dxf(converter(context, "dwg2dxf"), input, output, minimal = false)
            if (hasUsableEntities(output)) return output
            errors += "dwg2dxf الكامل: ${output.length()} بايت، بلا كيانات قابلة للقراءة."
        } catch (e: Exception) { errors += "dwg2dxf: ${e.message}" }

        try {
            runDwg2Dxf(converter(context, "dwg2dxf"), input, minimal, minimal = true)
            if (hasUsableEntities(minimal)) return minimal
            errors += "dwg2dxf المختصر: ${minimal.length()} بايت، بلا كيانات قابلة للقراءة."
        } catch (e: Exception) { errors += "dwg2dxf المختصر: ${e.message}" }

        try {
            runDwgRead(converter(context, "dwgread"), input, readOutput)
            if (hasUsableEntities(readOutput)) return readOutput
            errors += "dwgread: ${readOutput.length()} بايت، بلا كيانات قابلة للقراءة."
        } catch (e: Exception) { errors += "dwgread: ${e.message}" }

        error("تعذر استخراج هندسة DWG بعد تجربة محولين. " + errors.joinToString(" | "))
    }

    private fun hasUsableEntities(file: File): Boolean {
        if (!file.isFile || file.length() < 30L) return false
        val lines = runCatching { file.readLines(Charsets.UTF_8).map { it.trim().removePrefix("\uFEFF") } }
            .getOrNull() ?: return false
        var inEntities = false
        var entityCount = 0
        var i = 0
        while (i + 1 < lines.size) {
            if (lines[i] == "0" && lines[i + 1].equals("SECTION", true) &&
                i + 3 < lines.size && lines[i + 2] == "2" && lines[i + 3].equals("ENTITIES", true)) {
                inEntities = true
                i += 4
                continue
            }
            if (inEntities && lines[i] == "0") {
                val type = lines[i + 1].uppercase()
                if (type == "ENDSEC") break
                if (type in setOf("LINE", "LWPOLYLINE", "POLYLINE", "CIRCLE", "ARC", "SPLINE", "ELLIPSE", "INSERT", "POINT")) {
                    entityCount++
                }
            }
            i++
        }
        return inEntities && entityCount > 0
    }

    fun convertDxfToDwg(context: Context, dxfText: String): File {
        val stamp = System.currentTimeMillis()
        val input = File(context.cacheDir, "export-$stamp.dxf")
        input.writeText(dxfText, Charsets.UTF_8)
        val output = File(context.cacheDir, "export-$stamp.dwg")
        execute(listOf(converter(context, "dxf2dwg").absolutePath, "-o", output.absolutePath, "-y", input.absolutePath), output)
        return output
    }
}
