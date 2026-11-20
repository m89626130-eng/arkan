package com.arkan.cadpro

import android.content.Context
import android.net.Uri
import java.io.File

internal object LibreDwgRunner {
    private fun converter(context: Context, name: String): File {
        val libraryName = when (name) {
            "dwg2dxf" -> "libdwg2dxf.so"
            "dxf2dwg" -> "libdxf2dwg.so"
            else -> error("اسم محول DWG غير صالح.")
        }
        // Android extracts JNI libraries into the app's nativeLibraryDir,
        // a location intended for executable native code.
        val executable = File(context.applicationInfo.nativeLibraryDir, libraryName)
        check(executable.isFile && executable.length() > 0L) {
            "أداة DWG $name غير موجودة داخل مجلد المكتبات الأصلي للتطبيق."
        }
        check(executable.canExecute()) {
            "لا يسمح Android بتشغيل أداة DWG $name من مجلد المكتبات."
        }
        return executable
    }

    private fun run(executable: File, input: File, output: File, minimal: Boolean = false) {
        // LibreDWG accepts -m to emit a minimal DXF containing ENTITIES.
        // Keep the full conversion as the first attempt; use minimal only as a fallback.
        val command = mutableListOf(executable.absolutePath)
        if (minimal) command += "-m"
        command += listOf("-o", output.absolutePath, "-y", input.absolutePath)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0 && output.isFile && output.length() > 0L) {
            "فشل محول DWG (رمز $exit): ${log.take(1200)}"
        }
    }

    fun convertDwgToDxf(context: Context, uri: Uri): File {
        val stamp = System.currentTimeMillis()
        val input = File(context.cacheDir, "input-$stamp.dwg")
        context.contentResolver.openInputStream(uri)?.use { source ->
            input.outputStream().use { target -> source.copyTo(target) }
        } ?: error("تعذر قراءة ملف DWG من موفر الملفات.")
        val executable = converter(context, "dwg2dxf")
        val output = File(context.cacheDir, "converted-$stamp.dxf")
        run(executable, input, output)
        if (hasEntitiesSection(output)) return output

        // Some DWG files produce an incomplete full DXF from LibreDWG 0.14.
        // Retry with LibreDWG's documented minimal mode before reporting failure.
        val minimalOutput = File(context.cacheDir, "converted-$stamp-minimal.dxf")
        run(executable, input, minimalOutput, minimal = true)
        check(hasEntitiesSection(minimalOutput)) {
            "LibreDWG لم يُنتج قسم ENTITIES حتى في الوضع المختصر. " +
                "ملف DXF الكامل: ${output.length()} بايت، والمختصر: ${minimalOutput.length()} بايت."
        }
        return minimalOutput
    }

    private fun hasEntitiesSection(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val lines = file.readLines(Charsets.UTF_8).map { it.trim().removePrefix("\uFEFF") }
        for (i in 0 until lines.size - 3) {
            if (lines[i] == "0" && lines[i + 1].equals("SECTION", true) &&
                lines[i + 2] == "2" && lines[i + 3].equals("ENTITIES", true)) return true
        }
        return false
    }

    fun convertDxfToDwg(context: Context, dxfText: String): File {
        val stamp = System.currentTimeMillis()
        val input = File(context.cacheDir, "export-$stamp.dxf")
        input.writeText(dxfText, Charsets.UTF_8)
        val output = File(context.cacheDir, "export-$stamp.dwg")
        run(converter(context, "dxf2dwg"), input, output)
        return output
    }
}
