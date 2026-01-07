import tensorflow as tf

try:
    interpreter = tf.lite.Interpreter(model_path="./app/src/main/assets/inpaint_model.tflite")
    interpreter.allocate_tensors()

    print("=== INPUTS ===")
    for i, detail in enumerate(interpreter.get_input_details()):
        print(f"Input {i}: {detail['name']}, Shape: {detail['shape']}, Type: {detail['dtype']}, Index: {detail['index']}")

    print("\n=== OUTPUTS ===")
    for i, detail in enumerate(interpreter.get_output_details()):
        print(f"Output {i}: {detail['name']}, Shape: {detail['shape']}, Type: {detail['dtype']}, Index: {detail['index']}")

except Exception as e:
    print(f"Error: {e}")
