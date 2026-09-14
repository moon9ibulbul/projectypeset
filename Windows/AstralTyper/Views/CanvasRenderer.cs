using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using AstralTyper.Models;
using AstralTyper.Utils;

namespace AstralTyper.Views
{
    public static class CanvasRenderer
    {
        public static void RenderLayers(Canvas canvas, List<Layer> layers, Layer? selectedLayer, Action<Layer>? onSelectLayer)
        {
            canvas.Children.Clear();

            foreach (var layer in layers)
            {
                if (!layer.IsVisible) continue;

                UIElement? element = RenderSingleLayer(layer);
                if (element == null) continue;

                // Position container
                Canvas.SetLeft(element, layer.X);
                Canvas.SetTop(element, layer.Y);

                // Apply transforms (scale & rotation)
                var transformGroup = new TransformGroup();
                transformGroup.Children.Add(new ScaleTransform(layer.ScaleX, layer.ScaleY));
                transformGroup.Children.Add(new RotateTransform(layer.Rotation));
                element.RenderTransform = transformGroup;

                element.Opacity = layer.Opacity;

                // Attach click event for selection
                element.MouseLeftButtonDown += (s, e) =>
                {
                    onSelectLayer?.Invoke(layer);
                };

                canvas.Children.Add(element);

                // If selected, render transform selection overlay handles
                if (layer == selectedLayer)
                {
                    RenderSelectionHandles(canvas, layer, element);
                }
            }
        }

        private static UIElement? RenderSingleLayer(Layer layer)
        {
            if (layer is TextLayer textLayer)
            {
                return RenderTextLayer(textLayer);
            }
            else if (layer is ShapeLayer shapeLayer)
            {
                return RenderShapeLayer(shapeLayer);
            }
            else if (layer is ImageLayer imageLayer)
            {
                return RenderImageLayer(imageLayer);
            }
            else if (layer is BrushLayer brushLayer)
            {
                return RenderBrushLayer(brushLayer);
            }

            return null;
        }

        public static UIElement RenderTextLayer(TextLayer textLayer)
        {
            var textBlock = new TextBlock
            {
                Text = textLayer.Text,
                FontSize = textLayer.FontSize,
                FontFamily = new FontFamily(textLayer.FontFamilyName),
                Foreground = ParseBrush(textLayer.FillColor),
                FontWeight = textLayer.IsBold ? FontWeights.Bold : FontWeights.Normal,
                FontStyle = textLayer.IsItalic ? FontStyles.Italic : FontStyles.Normal,
                TextAlignment = textLayer.Alignment switch
                {
                    "Left" => TextAlignment.Left,
                    "Right" => TextAlignment.Right,
                    _ => TextAlignment.Center
                }
            };

            var container = new Border
            {
                Background = Brushes.Transparent,
                Padding = new Thickness(textLayer.StrokeWidth > 0 ? textLayer.StrokeWidth : 4)
            };

            if (textLayer.StrokeWidth > 0)
            {
                // Stroke wrapper
                var grid = new Grid();
                var outlineBlock = new TextBlock
                {
                    Text = textLayer.Text,
                    FontSize = textLayer.FontSize,
                    FontFamily = new FontFamily(textLayer.FontFamilyName),
                    Foreground = ParseBrush(textLayer.StrokeColor),
                    FontWeight = textLayer.IsBold ? FontWeights.Bold : FontWeights.Normal,
                    FontStyle = textLayer.IsItalic ? FontStyles.Italic : FontStyles.Normal,
                    TextAlignment = textBlock.TextAlignment
                };
                grid.Children.Add(outlineBlock);
                grid.Children.Add(textBlock);
                container.Child = grid;
            }
            else
            {
                container.Child = textBlock;
            }

            // Drop Shadow or Neon Glow
            if (textLayer.EnableDropShadow)
            {
                container.Effect = EffectEngine.CreateDropShadowEffect(textLayer.ShadowBlur, textLayer.ShadowOffsetX, textLayer.ShadowOffsetY, textLayer.ShadowColor);
            }
            else if (textLayer.EnableNeonGlow)
            {
                container.Effect = EffectEngine.CreateNeonGlowEffect(textLayer.NeonRadius, textLayer.NeonColor);
            }

            // Apply Warp Transform if active
            if (textLayer.IsWarpActive && textLayer.WarpPreset != "None")
            {
                var warpTransform = EffectEngine.CalculateWarpTransform(textLayer.WarpPreset, textLayer.WarpStrength, 200, 100);
                container.LayoutTransform = warpTransform;
            }

            return container;
        }

        public static UIElement RenderShapeLayer(ShapeLayer shapeLayer)
        {
            Shape shapeElement;

            if (shapeLayer.ShapeType == "Oval")
            {
                shapeElement = new Ellipse
                {
                    Width = shapeLayer.Width,
                    Height = shapeLayer.Height
                };
            }
            else
            {
                shapeElement = new Rectangle
                {
                    Width = shapeLayer.Width,
                    Height = shapeLayer.Height,
                    RadiusX = shapeLayer.CornerRadius,
                    RadiusY = shapeLayer.CornerRadius
                };
            }

            shapeElement.Fill = ParseBrush(shapeLayer.FillColor);
            shapeElement.Stroke = ParseBrush(shapeLayer.StrokeColor);
            shapeElement.StrokeThickness = shapeLayer.StrokeWidth;

            return shapeElement;
        }

        public static UIElement RenderImageLayer(ImageLayer imageLayer)
        {
            var image = new Image
            {
                Width = imageLayer.Width,
                Height = imageLayer.Height,
                Stretch = Stretch.Uniform
            };

            if (imageLayer.ImageData != null && imageLayer.ImageData.Length > 0)
            {
                try
                {
                    var bmp = new BitmapImage();
                    using (var ms = new System.IO.MemoryStream(imageLayer.ImageData))
                    {
                        bmp.BeginInit();
                        bmp.CacheOption = BitmapCacheOption.OnLoad;
                        bmp.StreamSource = ms;
                        bmp.EndInit();
                    }
                    image.Source = bmp;
                }
                catch { }
            }

            return image;
        }

        public static UIElement RenderBrushLayer(BrushLayer brushLayer)
        {
            var canvas = new Canvas
            {
                Width = 1080,
                Height = 1080,
                IsHitTestVisible = true,
                Background = Brushes.Transparent
            };

            foreach (var path in brushLayer.Paths)
            {
                if (path.Points.Count < 2) continue;

                var polyline = new Polyline
                {
                    Stroke = ParseBrush(path.Color),
                    StrokeThickness = path.Size,
                    StrokeLineJoin = PenLineJoin.Round,
                    StrokeStartLineCap = PenLineCap.Round,
                    StrokeEndLineCap = PenLineCap.Round
                };

                var pointCol = new PointCollection();
                foreach (var pt in path.Points)
                {
                    pointCol.Add(new Point(pt.X, pt.Y));
                }
                polyline.Points = pointCol;

                canvas.Children.Add(polyline);
            }

            return canvas;
        }

        private static void RenderSelectionHandles(Canvas canvas, Layer layer, UIElement element)
        {
            // Measure element bounds
            element.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            double width = element.DesiredSize.Width > 0 ? element.DesiredSize.Width : 100;
            double height = element.DesiredSize.Height > 0 ? element.DesiredSize.Height : 50;

            if (layer is ShapeLayer sl)
            {
                width = sl.Width;
                height = sl.Height;
            }
            else if (layer is ImageLayer il)
            {
                width = il.Width;
                height = il.Height;
            }

            // Outline box
            var border = new Rectangle
            {
                Width = width * layer.ScaleX,
                Height = height * layer.ScaleY,
                Stroke = Brushes.DeepSkyBlue,
                StrokeThickness = 1.5,
                StrokeDashArray = new DoubleCollection { 4, 2 },
                IsHitTestVisible = false
            };

            Canvas.SetLeft(border, layer.X);
            Canvas.SetTop(border, layer.Y);
            var tg = new TransformGroup();
            tg.Children.Add(new RotateTransform(layer.Rotation));
            border.RenderTransform = tg;

            canvas.Children.Add(border);
        }

        public static SolidColorBrush ParseBrush(string colorHex)
        {
            try
            {
                return new SolidColorBrush((Color)ColorConverter.ConvertFromString(colorHex));
            }
            catch
            {
                return Brushes.White;
            }
        }
    }
}
