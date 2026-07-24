package com.erguotou.ezapp.feature.scanner

import com.google.mlkit.vision.barcode.common.Barcode

fun Barcode.formatLabel(): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "二维码"
    Barcode.FORMAT_AZTEC -> "Aztec"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    else -> "条形码"
}
