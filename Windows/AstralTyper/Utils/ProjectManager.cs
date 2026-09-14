using System;
using System.Collections.Generic;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AstralTyper.Models;
using Newtonsoft.Json;

namespace AstralTyper.Utils
{
    public class ProjectData
    {
        public string Version { get; set; } = "1.6.5";
        public double CanvasWidth { get; set; } = 1080;
        public double CanvasHeight { get; set; } = 1080;
        public string BackgroundColor { get; set; } = "#FFFFFF";
        public List<Layer> Layers { get; set; } = new List<Layer>();
    }

    public static class ProjectManager
    {
        private static readonly JsonSerializerSettings JsonSettings = new JsonSerializerSettings
        {
            TypeNameHandling = TypeNameHandling.Auto,
            Formatting = Formatting.Indented
        };

        public static bool SaveProject(string filePath, ProjectData project)
        {
            try
            {
                string json = JsonConvert.SerializeObject(project, JsonSettings);
                File.WriteAllText(filePath, json);
                return true;
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Error saving project: {ex.Message}", "Save Project", MessageBoxButton.OK, MessageBoxImage.Error);
                return false;
            }
        }

        public static ProjectData? LoadProject(string filePath)
        {
            try
            {
                if (!File.Exists(filePath)) return null;
                string json = File.ReadAllText(filePath);
                return JsonConvert.DeserializeObject<ProjectData>(json, JsonSettings);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Error loading project: {ex.Message}", "Open Project", MessageBoxButton.OK, MessageBoxImage.Error);
                return null;
            }
        }

        public static void ExportToBitmap(FrameworkElement element, string filePath, double width, double height, bool isPng)
        {
            try
            {
                RenderTargetBitmap renderBitmap = new RenderTargetBitmap(
                    (int)width, (int)height, 96, 96, PixelFormats.Pbgra32);

                element.Measure(new Size(width, height));
                element.Arrange(new Rect(new Size(width, height)));
                renderBitmap.Render(element);

                BitmapEncoder encoder = isPng ? new PngBitmapEncoder() : new JpegBitmapEncoder();
                encoder.Frames.Add(BitmapFrame.Create(renderBitmap));

                using (Stream fileStream = File.Create(filePath))
                {
                    encoder.Save(fileStream);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Error exporting image: {ex.Message}", "Export Image", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }
}
