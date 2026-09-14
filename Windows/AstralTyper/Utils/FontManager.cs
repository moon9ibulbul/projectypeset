using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows.Media;

namespace AstralTyper.Utils
{
    public static class FontManager
    {
        private static List<string>? _cachedFonts;

        public static List<string> GetAvailableFonts()
        {
            if (_cachedFonts != null) return _cachedFonts;

            var list = new HashSet<string>();

            // Add System Fonts
            foreach (FontFamily font in Fonts.SystemFontFamilies)
            {
                if (!string.IsNullOrEmpty(font.Source))
                {
                    list.Add(font.Source);
                }
            }

            // Default standard fallbacks if list is empty
            if (list.Count == 0)
            {
                list.Add("Segoe UI");
                list.Add("Arial");
                list.Add("Times New Roman");
                list.Add("Courier New");
                list.Add("Comic Sans MS");
                list.Add("Impact");
            }

            _cachedFonts = list.OrderBy(f => f).ToList();
            return _cachedFonts;
        }

        public static void LoadCustomFont(string filePath)
        {
            if (!File.Exists(filePath)) return;

            try
            {
                var customFont = new FontFamily(new Uri(filePath), $"#{Path.GetFileNameWithoutExtension(filePath)}");
                if (!string.IsNullOrEmpty(customFont.Source))
                {
                    GetAvailableFonts().Insert(0, customFont.Source);
                }
            }
            catch { }
        }
    }
}
