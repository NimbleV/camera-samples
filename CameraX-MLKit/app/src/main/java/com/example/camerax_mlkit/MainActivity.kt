/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.camerax_mlkit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerax_mlkit.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        //  화면 깨우기 및 잠금 해제 설정 (안드로이드 10 / API 29 이상)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        var cameraController = LifecycleCameraController(baseContext)

        val previewView: PreviewView = viewBinding.viewFinder
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        //FIT_CENTER로 해야 이미지가 짤리지 않고 보여진다. 대신 검은부분이 생길 수 있다.
        //FILL_CENTER로 하면 화면을 꽉 채우는 대신 일부가 안 보일 수 있다.

        val options = BarcodeScannerOptions.Builder()
            //.setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_39)
            .enableAllPotentialBarcodes()
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        val mlKitAnalyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            COORDINATE_SYSTEM_VIEW_REFERENCED,
            ContextCompat.getMainExecutor(this)
        ) { result: MlKitAnalyzer.Result? ->
            val barcodeResults = result?.getValue(barcodeScanner)

            if (barcodeResults.isNullOrEmpty()) {
                previewView.overlay.clear()
                previewView.setOnTouchListener { _, _ -> false } //no-op
                return@MlKitAnalyzer
            }

            previewView.overlay.clear()
            val qrCodeViewModels = barcodeResults.filterNotNull().map { QrCodeViewModel(it) }
            qrCodeViewModels.forEachIndexed { i, viewModel ->
                previewView.overlay.add(QrCodeDrawable(viewModel))
                Log.d(TAG, "barcode detected($i):" + viewModel.qrContent)
            }
            Log.d(TAG, "==============")

            previewView.setOnTouchListener { v, e ->
                qrCodeViewModels.any { viewModel ->
                    viewModel.qrCodeTouchCallback(v, e)
                }
            }
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                //ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                //ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                //ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        cameraController.imageAnalysisResolutionSelector = resolutionSelector

        cameraController.setImageAnalysisAnalyzer (
            ContextCompat.getMainExecutor(this),
            object : ImageAnalysis.Analyzer {
                override fun analyze(imageProxy: ImageProxy) {
                    val width = imageProxy.width
                    val height = imageProxy.height
                    Log.d(TAG, "Image size: ${width}x${height}")

                    val imgBuffer = imageProxy.planes[0].buffer
                    //여기서 이미지 버퍼이용.

                    mlKitAnalyzer.analyze(imageProxy)
                    //imageProxy.close()
                }

                override fun updateTransform(matrix: android.graphics.Matrix?) {
                    mlKitAnalyzer.updateTransform(matrix)
                }

                override fun getDefaultTargetResolution(): android.util.Size {
                    return mlKitAnalyzer.defaultTargetResolution
                }

                override fun getTargetCoordinateSystem(): Int {
                    return mlKitAnalyzer.targetCoordinateSystem
                }
            }
        )

        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    companion object {
        private const val TAG = "CameraX-MLKit"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA
            ).toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}