namespace AstralTyper.Models
{
    public class ImageLayer : Layer
    {
        private string _imagePath = string.Empty;
        private double _width = 300;
        private double _height = 300;
        private byte[]? _imageData;

        public string ImagePath
        {
            get => _imagePath;
            set { _imagePath = value; OnPropertyChanged(); }
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

        public byte[]? ImageData
        {
            get => _imageData;
            set { _imageData = value; OnPropertyChanged(); }
        }

        public override Layer Clone()
        {
            return new ImageLayer
            {
                Name = Name + " Copy",
                X = X + 20,
                Y = Y + 20,
                ScaleX = ScaleX,
                ScaleY = ScaleY,
                Rotation = Rotation,
                Opacity = Opacity,
                IsVisible = IsVisible,
                ImagePath = ImagePath,
                Width = Width,
                Height = Height,
                ImageData = ImageData != null ? (byte[])ImageData.Clone() : null
            };
        }
    }
}
