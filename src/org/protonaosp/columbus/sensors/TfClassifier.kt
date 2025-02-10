package org.protonaosp.columbus.sensors

import android.content.res.AssetManager
import android.util.Log
import java.io.FileInputStream
import java.lang.reflect.Array
import java.nio.channels.FileChannel
import java.util.ArrayList
import java.util.HashMap
import org.tensorflow.lite.Interpreter

class TfClassifier(assetManager: AssetManager, assetFileName: String) {

    companion object {
        private const val TAG = "columbus/TfClassifier"
    }

    private var interpreter: Interpreter? = null

    init {
        try {
            val assetFd = assetManager.openFd(assetFileName)
            interpreter =
                Interpreter(
                    FileInputStream(assetFd.fileDescriptor)
                        .channel
                        .map(
                            FileChannel.MapMode.READ_ONLY,
                            assetFd.startOffset,
                            assetFd.declaredLength,
                        )
                )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tflite file: ${e.message}")
        }
    }

    fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        val interpreter = interpreter ?: return ArrayList()

        val tfliteIn =
            Array(1) { Array(input.size) { Array(1) { FloatArray(1) } } }
                .apply {
                    for (i in input.indices) {
                        this[0][i][0][0] = input[i]
                    }
                }

        val tfliteOut = HashMap<Int, Any>().apply { this[0] = Array(1) { FloatArray(size) } }

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(tfliteIn), tfliteOut)
        } catch (e: Exception) {
            Log.e(TAG, "Error running inference: ${e.message}")
            return ArrayList()
        }

        if (tfliteOut.isEmpty()) {
            return ArrayList()
        }

        val tfliteContent = tfliteOut[0]
        if (tfliteContent !is Array) {
            return ArrayList()
        }

        val output =
            arrayListOf(
                ArrayList<Float>().apply {
                    for (i in 0 until size) {
                        add((tfliteContent as FloatArray)[i])
                    }
                }
            )

        return output
    }
}
