using System.Collections.Generic;

namespace AstralTyper.Models
{
    public class BrushStrokePoint
    {
        public double X { get; set; }
        public double Y { get; set; }
        public double Size { get; set; } = 10;
        public string Color { get; set; } = "#FFFFFF";
        public bool IsEraser { get; set; } = false;
    }

    public class BrushStrokePath
    {
        public List<BrushStrokePoint> Points { get; set; } = new List<BrushStrokePoint>();
        public double Size { get; set; } = 10;
        public string Color { get; set; } = "#FFFFFF";
        public bool IsEraser { get; set; } = false;
    }

    public class BrushLayer : Layer
    {
        public List<BrushStrokePath> Paths { get; set; } = new List<BrushStrokePath>();

        public override Layer Clone()
        {
            var cloned = new BrushLayer
            {
                Name = Name + " Copy",
                X = X,
                Y = Y,
                ScaleX = ScaleX,
                ScaleY = ScaleY,
                Rotation = Rotation,
                Opacity = Opacity,
                IsVisible = IsVisible
            };

            foreach (var p in Paths)
            {
                var newPath = new BrushStrokePath
                {
                    Size = p.Size,
                    Color = p.Color,
                    IsEraser = p.IsEraser
                };
                foreach (var pt in p.Points)
                {
                    newPath.Points.Add(new BrushStrokePoint
                    {
                        X = pt.X,
                        Y = pt.Y,
                        Size = pt.Size,
                        Color = pt.Color,
                        IsEraser = pt.IsEraser
                    });
                }
                cloned.Paths.Add(newPath);
            }

            return cloned;
        }
    }
}
