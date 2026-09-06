using System;

namespace AstralTyper.Models
{
    public class TextLayer : Layer
    {
        private string _text = "AstralTyper";
        private string _fontFamily = "Segoe UI";
        private double _fontSize = 48;
        private string _fillColor = "#FFFFFF";
        private string _strokeColor = "#000000";
        private double _strokeWidth = 0;
        private bool _isBold = false;
        private bool _isItalic = false;
        private double _letterSpacing = 0;
        private double _lineSpacing = 1.0;
        private string _alignment = "Center"; // Left, Center, Right

        // Effect parameters
        public bool EnableDropShadow { get; set; } = false;
        public double ShadowBlur { get; set; } = 10;
        public double ShadowOffsetX { get; set; } = 5;
        public double ShadowOffsetY { get; set; } = 5;
        public string ShadowColor { get; set; } = "#000000";

        public bool EnableNeonGlow { get; set; } = false;
        public double NeonRadius { get; set; } = 15;
        public string NeonColor { get; set; } = "#00FFFF";

        // Warp parameters
        public bool IsWarpActive { get; set; } = false;
        public string WarpPreset { get; set; } = "None"; // Arc Up, Arc Down, Bulge, Pinch, Wave, Flag, S-Curve
        public double WarpStrength { get; set; } = 30;

        public string Text
        {
            get => _text;
            set { _text = value; OnPropertyChanged(); }
        }

        public string FontFamilyName
        {
            get => _fontFamily;
            set { _fontFamily = value; OnPropertyChanged(); }
        }

        public double FontSize
        {
            get => _fontSize;
            set { _fontSize = value; OnPropertyChanged(); }
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

        public bool IsBold
        {
            get => _isBold;
            set { _isBold = value; OnPropertyChanged(); }
        }

        public bool IsItalic
        {
            get => _isItalic;
            set { _isItalic = value; OnPropertyChanged(); }
        }

        public double LetterSpacing
        {
            get => _letterSpacing;
            set { _letterSpacing = value; OnPropertyChanged(); }
        }

        public double LineSpacing
        {
            get => _lineSpacing;
            set { _lineSpacing = value; OnPropertyChanged(); }
        }

        public string Alignment
        {
            get => _alignment;
            set { _alignment = value; OnPropertyChanged(); }
        }

        public override Layer Clone()
        {
            return new TextLayer
            {
                Name = Name + " Copy",
                X = X + 20,
                Y = Y + 20,
                ScaleX = ScaleX,
                ScaleY = ScaleY,
                Rotation = Rotation,
                Opacity = Opacity,
                IsVisible = IsVisible,
                Text = Text,
                FontFamilyName = FontFamilyName,
                FontSize = FontSize,
                FillColor = FillColor,
                StrokeColor = StrokeColor,
                StrokeWidth = StrokeWidth,
                IsBold = IsBold,
                IsItalic = IsItalic,
                LetterSpacing = LetterSpacing,
                LineSpacing = LineSpacing,
                Alignment = Alignment,
                EnableDropShadow = EnableDropShadow,
                ShadowBlur = ShadowBlur,
                ShadowOffsetX = ShadowOffsetX,
                ShadowOffsetY = ShadowOffsetY,
                ShadowColor = ShadowColor,
                EnableNeonGlow = EnableNeonGlow,
                NeonRadius = NeonRadius,
                NeonColor = NeonColor,
                IsWarpActive = IsWarpActive,
                WarpPreset = WarpPreset,
                WarpStrength = WarpStrength
            };
        }
    }
}
