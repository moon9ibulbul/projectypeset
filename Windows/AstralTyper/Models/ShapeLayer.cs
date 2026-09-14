namespace AstralTyper.Models
{
    public class ShapeLayer : Layer
    {
        private string _shapeType = "Rectangle"; // Rectangle, Oval, Star, Polygon
        private double _width = 200;
        private double _height = 150;
        private string _fillColor = "#BB86FC";
        private string _strokeColor = "#FFFFFF";
        private double _strokeWidth = 2;
        private double _cornerRadius = 0;

        public string ShapeType
        {
            get => _shapeType;
            set { _shapeType = value; OnPropertyChanged(); }
        }

        public double Width
        {
            get => _width;
            set { _width = value; OnPropertyChanged(); }
        }

        public double Height
        {
            get => _height;
            set { _height = value; OnPropertyChanged(); }
        }

        public string FillColor
        {
            get => _fillColor;
            set { _fillColor = value; OnPropertyChanged(); }
        }

        public string StrokeColor
        {
            get => _strokeColor;
            set { _strokeColor = value; OnPropertyChanged(); }
        }

        public double StrokeWidth
        {
            get => _strokeWidth;
            set { _strokeWidth = value; OnPropertyChanged(); }
        }

        public double CornerRadius
        {
            get => _cornerRadius;
            set { _cornerRadius = value; OnPropertyChanged(); }
        }

        public override Layer Clone()
        {
            return new ShapeLayer
            {
                Name = Name + " Copy",
                X = X + 20,
                Y = Y + 20,
                ScaleX = ScaleX,
                ScaleY = ScaleY,
                Rotation = Rotation,
                Opacity = Opacity,
                IsVisible = IsVisible,
                ShapeType = ShapeType,
                Width = Width,
                Height = Height,
                FillColor = FillColor,
                StrokeColor = StrokeColor,
                StrokeWidth = StrokeWidth,
                CornerRadius = CornerRadius
            };
        }
    }
}
