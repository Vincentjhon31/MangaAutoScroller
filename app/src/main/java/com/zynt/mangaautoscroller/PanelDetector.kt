package com.zynt.mangaautoscroller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log

/**
 * Utility class for detecting panel boundaries in manga/comics
 */
class PanelDetector {
    companion object {
        private const val TAG = "PanelDetector"

        /**
         * Detect panel boundaries in a manga/comic page
         * @param bitmap The screen capture bitmap
         * @return List of panel boundary rectangles
         */
        fun detectPanels(bitmap: Bitmap, screenWidth: Int, screenHeight: Int): List<Rect> {
            try {
                // Convert to grayscale for easier processing
                val width = bitmap.width
                val height = bitmap.height
                val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grayBitmap)
                val paint = Paint()
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)

                // Create a binary image to highlight panel boundaries
                val threshold = 220 // Threshold for white space detection

                // Scan for white space (panel boundaries are usually white)
                val pixels = IntArray(width * height)
                grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                // Count white pixels in rows and columns to detect gutters
                val rowWhiteCount = IntArray(height)
                val colWhiteCount = IntArray(width)

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = pixels[y * width + x]
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val gray = (0.3 * r + 0.59 * g + 0.11 * b).toInt()

                        if (gray > threshold) {
                            rowWhiteCount[y]++
                            colWhiteCount[x]++
                        }
                    }
                }

                // Detect horizontal gutters (spaces between panels in vertical direction)
                val horizontalGutters = mutableListOf<Int>()
                var inGutter = false
                val gutterThreshold = width * 0.5 // 50% of width should be white to be a gutter

                for (y in 0 until height) {
                    if (rowWhiteCount[y] > gutterThreshold) {
                        if (!inGutter) {
                            horizontalGutters.add(y)
                            inGutter = true
                        }
                    } else {
                        if (inGutter) {
                            horizontalGutters.add(y - 1)
                            inGutter = false
                        }
                    }
                }

                // If we ended in a gutter, close it
                if (inGutter) {
                    horizontalGutters.add(height - 1)
                }

                // Detect vertical gutters (spaces between panels in horizontal direction)
                val verticalGutters = mutableListOf<Int>()
                inGutter = false
                val vGutterThreshold = height * 0.5 // 50% of height should be white to be a gutter

                for (x in 0 until width) {
                    if (colWhiteCount[x] > vGutterThreshold) {
                        if (!inGutter) {
                            verticalGutters.add(x)
                            inGutter = true
                        }
                    } else {
                        if (inGutter) {
                            verticalGutters.add(x - 1)
                            inGutter = false
                        }
                    }
                }

                // If we ended in a gutter, close it
                if (inGutter) {
                    verticalGutters.add(width - 1)
                }

                // Convert gutters to panel rectangles
                val panels = mutableListOf<Rect>()

                // Ensure we have start and end gutters
                if (horizontalGutters.isEmpty() || horizontalGutters.size % 2 != 0) {
                    if (horizontalGutters.isEmpty()) {
                        horizontalGutters.add(0)
                        horizontalGutters.add(height - 1)
                    } else if (horizontalGutters.size % 2 != 0) {
                        horizontalGutters.add(0, 0)
                    }
                }

                if (verticalGutters.isEmpty() || verticalGutters.size % 2 != 0) {
                    if (verticalGutters.isEmpty()) {
                        verticalGutters.add(0)
                        verticalGutters.add(width - 1)
                    } else if (verticalGutters.size % 2 != 0) {
                        verticalGutters.add(0, 0)
                    }
                }

                // Create panels from the gutters
                for (i in 0 until horizontalGutters.size step 2) {
                    if (i + 1 < horizontalGutters.size) {
                        val top = horizontalGutters[i]
                        val bottom = horizontalGutters[i + 1]

                        for (j in 0 until verticalGutters.size step 2) {
                            if (j + 1 < verticalGutters.size) {
                                val left = verticalGutters[j]
                                val right = verticalGutters[j + 1]

                                // Create a panel rect if it's big enough
                                val panelWidth = right - left
                                val panelHeight = bottom - top
                                if (panelWidth > width/10 && panelHeight > height/10) {
                                    panels.add(Rect(left, top, right, bottom))
                                }
                            }
                        }
                    }
                }

                // Clean up
                grayBitmap.recycle()

                // If no panels detected or detection failed, create a simple grid as fallback
                if (panels.isEmpty()) {
                    val rowCount = 3
                    val colCount = 2
                    val rowHeight = height / rowCount
                    val colWidth = width / colCount

                    for (row in 0 until rowCount) {
                        for (col in 0 until colCount) {
                            panels.add(Rect(
                                col * colWidth,
                                row * rowHeight,
                                (col + 1) * colWidth,
                                (row + 1) * rowHeight
                            ))
                        }
                    }
                }

                Log.d(TAG, "Panel detection found ${panels.size} panels")
                return panels

            } catch (e: Exception) {
                Log.e(TAG, "Error detecting panels", e)
                return emptyList()
            }
        }

        /**
         * Calculate complexity of the image for scrolling speed adjustment
         */
        fun calculateImageComplexity(bitmap: Bitmap): Float {
            try {
                // Convert to grayscale for processing
                val width = bitmap.width
                val height = bitmap.height
                val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grayBitmap)
                val paint = Paint()
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)

                // Calculate edge density using a simple edge detection approach
                var edgeCount = 0
                val pixels = IntArray(width * height)
                grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                // Edge detection by checking neighboring pixel differences
                for (y in 1 until height - 1) {
                    for (x in 1 until width - 1) {
                        val currentPixel = pixels[y * width + x]
                        val rightPixel = pixels[y * width + x + 1]
                        val bottomPixel = pixels[(y + 1) * width + x]

                        val currentGray = Color.red(currentPixel) // Since it's grayscale, r=g=b
                        val rightGray = Color.red(rightPixel)
                        val bottomGray = Color.red(bottomPixel)

                        // If significant difference, count as edge
                        if (Math.abs(currentGray - rightGray) > 20 ||
                            Math.abs(currentGray - bottomGray) > 20) {
                            edgeCount++
                        }
                    }
                }

                // Calculate color variance as another complexity factor
                var redSum = 0
                var greenSum = 0
                var blueSum = 0
                var redSqSum = 0
                var greenSqSum = 0
                var blueSqSum = 0

                // Sample at 1/4 resolution for performance
                for (y in 0 until height step 4) {
                    for (x in 0 until width step 4) {
                        val pixel = bitmap.getPixel(x, y)

                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)

                        redSum += r
                        greenSum += g
                        blueSum += b

                        redSqSum += r * r
                        greenSqSum += g * g
                        blueSqSum += b * b
                    }
                }

                val pixelCount = (width / 4) * (height / 4)

                // Calculate variance for each channel
                val redMean = redSum.toFloat() / pixelCount
                val greenMean = greenSum.toFloat() / pixelCount
                val blueMean = blueSum.toFloat() / pixelCount

                val redVariance = redSqSum.toFloat() / pixelCount - (redMean * redMean)
                val greenVariance = greenSqSum.toFloat() / pixelCount - (greenMean * greenMean)
                val blueVariance = blueSqSum.toFloat() / pixelCount - (blueMean * blueMean)

                // Average variance across channels
                val colorVariance = (redVariance + greenVariance + blueVariance) / 3

                // Clean up
                grayBitmap.recycle()

                // Normalize edge density to 0-1 range
                val normalizedEdgeDensity = edgeCount.toFloat() / (width * height)

                // Normalize color variance (typical range is 0-10000)
                val normalizedColorVariance = Math.min(1.0f, colorVariance / 5000.0f)

                // Combined complexity score (weighted)
                val complexity = (normalizedEdgeDensity * 0.7f) + (normalizedColorVariance * 0.3f)

                Log.d(TAG, "Image complexity: $complexity (edges: $normalizedEdgeDensity, color variance: $normalizedColorVariance)")
                return complexity

            } catch (e: Exception) {
                Log.e(TAG, "Error calculating image complexity", e)
                return 0.5f // Default medium complexity on error
            }
        }
    }
}
