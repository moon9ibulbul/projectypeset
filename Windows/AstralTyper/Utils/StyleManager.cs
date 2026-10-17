using System.Collections.Generic;
using AstralTyper.Models;

namespace AstralTyper.Utils
{
    public class StyleModel
    {
        public string Name { get; set; } = "Preset Style";
        public string FillColor { get; set; } = "#FFFFFF";
        public string StrokeColor { get; set; } = "#000000";
        public double StrokeWidth { get; set; } = 2;
        public bool IsBold { get; set; } = false;
        public bool IsItalic { get; set; } = false;
        public bool EnableDropShadow { get; set; } = false;
        public bool EnableNeonGlow { get; set; } = false;
    }

    public static class StyleManager
    {
        private static readonly List<StyleModel> DefaultStyles = new List<StyleModel>
        {
            new StyleModel { Name = "Classic White", FillColor = "#FFFFFF", StrokeColor = "#000000", StrokeWidth = 2, IsBold = true },
            new StyleModel { Name = "Neon Cyan", FillColor = "#00FFFF", StrokeColor = "#005555", StrokeWidth = 1, EnableNeonGlow = true },
            new StyleModel { Name = "Gold Accent", FillColor = "#FFD700", StrokeColor = "#8B6508", StrokeWidth = 2, IsBold = true },
            new StyleModel { Name = "Dark Shadow", FillColor = "#E0E0E0", StrokeColor = "#111111", StrokeWidth = 1, EnableDropShadow = true }
        };

        public static List<StyleModel> GetPresetStyles()
        {
            return DefaultStyles;
        }

        public static void ApplyStyleToLayer(StyleModel style, TextLayer textLayer)
        {
            textLayer.FillColor = style.FillColor;
            textLayer.StrokeColor = style.StrokeColor;
            textLayer.StrokeWidth = style.StrokeWidth;
            textLayer.IsBold = style.IsBold;
            textLayer.IsItalic = style.IsItalic;
            textLayer.EnableDropShadow = style.EnableDropShadow;
            textLayer.EnableNeonGlow = style.EnableNeonGlow;
        }
    }
}
