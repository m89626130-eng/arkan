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

    private fun run(executable: File, input: File, output: File) {
        // Put options before positional filenames so the converter parses them on Android.
        val process = ProcessBuilder(
            executable.absolutePath, "-o", output.absolutePath, "-y", input.absolutePath
        ).redirectErrorStream(true).start()
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
        val output = File(context.cacheDir, "converted-$stamp.dxf")
        run(converter(context, "dwg2dxf"), input, output)
        return output
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
