using System;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Effects;
using AstralTyper.Models;

namespace AstralTyper.Utils
{
    public static class EffectEngine
    {
        public static Effect? CreateDropShadowEffect(double blurRadius, double offsetX, double offsetY, string hexColor)
        {
            try
            {
                Color color = (Color)ColorConverter.ConvertFromString(hexColor);
                double angle = Math.Atan2(offsetY, offsetX) * (180 / Math.PI);
                double depth = Math.Sqrt(offsetX * offsetX + offsetY * offsetY);

                return new DropShadowEffect
                {
                    BlurRadius = blurRadius,
                    Direction = angle,
                    ShadowDepth = depth,
                    Color = color,
                    Opacity = 0.8
                };
            }
            catch
            {
                return null;
            }
        }

        public static Effect? CreateNeonGlowEffect(double radius, string hexColor)
        {
            try
            {
                Color color = (Color)ColorConverter.ConvertFromString(hexColor);
                return new DropShadowEffect
                {
                    BlurRadius = radius,
                    ShadowDepth = 0,
                    Color = color,
                    Opacity = 1.0
                };
            }
            catch
            {
                return null;
            }
        }

        public static TransformGroup CalculateWarpTransform(string preset, double strength, double width, double height)
        {
            var group = new TransformGroup();

            if (preset == "None" || strength == 0) return group;

            double normalizedStrength = strength / 100.0;

            switch (preset)
            {
                case "Arc Up":
                    group.Children.Add(new SkewTransform(0, -10 * normalizedStrength));
                    break;
                case "Arc Down":
                    group.Children.Add(new SkewTransform(0, 10 * normalizedStrength));
                    break;
                case "Bulge":
                    group.Children.Add(new ScaleTransform(1.0 + 0.3 * normalizedStrength, 1.0 + 0.3 * normalizedStrength));
                    break;
                case "Pinch":
                    group.Children.Add(new ScaleTransform(1.0 - 0.2 * normalizedStrength, 1.0 - 0.2 * normalizedStrength));
                    break;
                case "Wave":
                    group.Children.Add(new SkewTransform(15 * normalizedStrength, -10 * normalizedStrength));
                    break;
                case "Flag":
                    group.Children.Add(new SkewTransform(10 * normalizedStrength, 5 * normalizedStrength));
                    break;
                case "S-Curve":
                    group.Children.Add(new SkewTransform(-12 * normalizedStrength, 12 * normalizedStrength));
                    break;
            }

            return group;
        }
    }
}
