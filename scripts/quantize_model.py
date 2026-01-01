"""
Script to quantize the Comic Text Detector ONNX model.
This reduces the model size from ~90MB to ~23MB and improves inference speed.

Usage:
    1. Make sure you have the full model at: app/src/main/assets/models/comictextdetector.pt.onnx
    2. Run: python scripts/quantize_model.py
    3. The quantized model will be created at: app/src/main/assets/models/comictextdetector_quantized.onnx
"""

import os
import sys

def main():
    # Check if onnxruntime is installed
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        import onnx
    except ImportError:
        print("❌ Missing required packages. Install them with:")
        print("   pip install onnxruntime onnx")
        sys.exit(1)
    
    # Paths
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    models_dir = os.path.join(project_root, "app", "src", "main", "assets", "models")
    
    input_model = os.path.join(models_dir, "comictextdetector.pt.onnx")
    output_model = os.path.join(models_dir, "comictextdetector_quantized.onnx")
    
    # Check if input exists
    if not os.path.exists(input_model):
        print(f"❌ Input model not found: {input_model}")
        print("\nPlease download the model first:")
        print("1. Go to: https://github.com/zyddnys/manga-image-translator/releases")
        print("2. Download: comictextdetector.pt.onnx")
        print(f"3. Place it in: {models_dir}")
        sys.exit(1)
    
    input_size = os.path.getsize(input_model) / (1024 * 1024)
    print(f"📥 Input model: {input_model}")
    print(f"   Size: {input_size:.1f} MB")
    
    print("\n⏳ Quantizing model (this may take a few minutes)...")
    
    try:
        # Perform dynamic quantization
        quantize_dynamic(
            model_input=input_model,
            model_output=output_model,
            weight_type=QuantType.QUInt8
        )
        
        output_size = os.path.getsize(output_model) / (1024 * 1024)
        reduction = (1 - output_size / input_size) * 100
        
        print(f"\n✅ Quantization complete!")
        print(f"📤 Output model: {output_model}")
        print(f"   Size: {output_size:.1f} MB")
        print(f"   Reduction: {reduction:.0f}%")
        print(f"\n🎉 The app will automatically use the quantized model!")
        
    except Exception as e:
        print(f"\n❌ Quantization failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
