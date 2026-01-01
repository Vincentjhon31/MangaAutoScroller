# ML Bubble Detection Setup

This document explains how to set up the advanced ML-based speech bubble detection feature in MangaAutoScroller.

## Overview

MangaAutoScroller supports two detection modes:

1. **ML Kit OCR (Default)** - Uses Google's ML Kit for general text recognition
2. **ML Bubble Detection (Advanced)** - Uses a manga-trained ONNX model for accurate speech bubble detection

The ML Bubble Detection mode provides:

- Better accuracy for manga/manhwa/manhua content
- Detects speech bubbles, not just text
- Works entirely offline
- Faster inference for adaptive scrolling

## Downloading the Model

### Option 1: Comic Text Detector (Recommended)

1. Go to the [manga-image-translator releases](https://github.com/zyddnys/manga-image-translator/releases)

2. Download `comictextdetector.pt.onnx` (~90 MB)

3. Place the file in:
   ```
   app/src/main/assets/models/comictextdetector.pt.onnx
   ```

### Option 2: Quantized Model (Smaller, ~23 MB)

For a smaller APK size, you can create a quantized version:

1. Install ONNX Runtime tools:

   ```bash
   pip install onnxruntime onnx
   ```

2. Quantize the model:

   ```python
   from onnxruntime.quantization import quantize_dynamic, QuantType

   quantize_dynamic(
       "comictextdetector.pt.onnx",
       "comictextdetector_quantized.onnx",
       weight_type=QuantType.QUInt8
   )
   ```

3. Place in:
   ```
   app/src/main/assets/models/comictextdetector_quantized.onnx
   ```

The app will automatically prefer the quantized model if available.

## Directory Structure

After adding the model, your assets folder should look like:

```
app/src/main/assets/
└── models/
    ├── comictextdetector.pt.onnx      # Full model (~90 MB)
    └── comictextdetector_quantized.onnx  # Quantized (optional, ~23 MB)
```

## Enabling ML Bubble Detection

1. Open MangaAutoScroller app
2. Tap the **Settings** icon (gear)
3. Enable **"🤖 ML Bubble Detection"** toggle
4. Start the scroller service

The app will show "ML Bubble Detection" mode in the toast when starting.

## How It Works

The Comic Text Detector model:

1. **Input**: Takes a screen capture (resized to 1024x1024)
2. **Processing**: Runs through a YOLOv5-based neural network
3. **Output**: Returns bounding boxes for detected speech bubbles
4. **Scoring**: Calculates text density based on:
   - Number of detected bubbles
   - Total area coverage of bubbles
   - Whether it's a dialogue-heavy or action panel

This density score is then used to automatically adjust scroll speed:

- **High density** (many bubbles) → Slower scrolling for reading
- **Low density** (action scenes) → Faster scrolling

## Model Details

| Property       | Value               |
| -------------- | ------------------- |
| Model          | Comic Text Detector |
| Architecture   | YOLOv5 + DBNet      |
| Input Size     | 1024 x 1024         |
| Format         | ONNX                |
| Full Size      | ~90 MB              |
| Quantized Size | ~23 MB              |
| License        | GPL-3.0             |

## Troubleshooting

### Model Not Loading

If the ML detection toggle doesn't work:

1. Check that the model file is in the correct location
2. Verify the filename is exactly `comictextdetector.pt.onnx`
3. Check logcat for errors with tag `BubbleDetector`

### Slow Performance

If detection is slow:

1. Use the quantized model
2. Enable GPU acceleration in BubbleDetectorConfig (experimental)
3. Increase the text analysis interval in settings

### APK Too Large

The model adds ~90 MB to your APK. To reduce:

1. Use the quantized model (~23 MB instead)
2. Use Android App Bundle (AAB) for Play Store distribution
3. Consider on-demand model download (future feature)

## Alternative Models

Other compatible models (may require code adjustments):

1. **Manga-OCR** - Japanese text focused
2. **Manga-Text-Segmentation** - Pixel-level segmentation
3. **Custom trained** - Train your own with Manga109 dataset

## License

The Comic Text Detector model is licensed under GPL-3.0.
See: https://github.com/dmMaze/comic-text-detector
