import re

with open('app/src/main/java/com/astral/typer/models/ShapeLayer.kt', 'r') as f:
    content = f.read()

# Problem 1: `isDrawingStrokePass` shouldn't force alpha to 1f or 0f using color matrix, it should preserve it.
content = content.replace("0f, 0f, 0f, 100f, -250f", "0f, 0f, 0f, 1f, 0f")

with open('app/src/main/java/com/astral/typer/models/ShapeLayer.kt', 'w') as f:
    f.write(content)
