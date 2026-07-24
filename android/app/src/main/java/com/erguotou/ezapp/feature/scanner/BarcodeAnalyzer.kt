package com.erguotou.ezapp.feature.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onDetected: (String, String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val processing = AtomicBoolean(false)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.let { onDetected(it.rawValue!!, it.formatLabel()) }
            }
            .addOnCompleteListener {
                processing.set(false)
                imageProxy.close()
            }
    }
}
