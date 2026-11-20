package com.arkan.cadpro

import android.content.Context
import android.net.Uri
import java.io.File

internal object LibreDwgRunner {
    private fun converter(context: Context, name: String): File {
        require(name == "dwg2dxf" || name == "dxf2dwg") { "اسم محول DWG غير صالح." }
        val directory = File(context.noBackupFilesDir, "dwg/arm64-v8a")
        check(directory.exists() || directory.mkdirs()) { "تعذر تجهيز مجلد أدوات DWG." }
        val destination = File(directory, name)
        if (!destination.isFile || destination.length() == 0L) {
            context.assets.open("dwg/arm64-v8a/$name").use { source ->
                destination.outputStream().use { target -> source.copyTo(target) }
            }
        }
        check(destination.isFile && destination.length() > 0L) {
            "أداة DWG $name غير موجودة داخل التطبيق."
        }
        check(destination.setExecutable(true, true) || destination.canExecute()) {
            "تعذر تفعيل أداة DWG $name."
        }
        return destination
    }

    private fun run(executable: File, input: File, output: File) {
        val process = ProcessBuilder(executable.absolutePath, "-o", output.absolutePath, input.absolutePath)
            .redirectErrorStream(true).start()
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
        } ?: error("تعذر قراءة ملف DWG")
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
