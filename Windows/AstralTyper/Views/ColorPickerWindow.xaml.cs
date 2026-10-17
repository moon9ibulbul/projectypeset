using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AstralTyper.Views
{
    public partial class ColorPickerWindow : Window
    {
        public string SelectedHexColor { get; private set; } = "#FFFFFF";

        private static readonly string[] PresetColors = new string[]
        {
            "#FFFFFF", "#000000", "#FF0000", "#00FF00", "#0000FF", "#FFFF00",
            "#00FFFF", "#FF00FF", "#BB86FC", "#007ACC", "#FF9800", "#4CAF50",
            "#9C27B0", "#E91E63", "#795548", "#607D8B", "#3F51B5", "#00BCD4",
            "#8BC34A", "#FFC107", "#FF5722", "#9E9E9E", "#333333", "#121212"
        };

        public ColorPickerWindow(string initialHex = "#FFFFFF")
        {
            InitializeComponent();
            SelectedHexColor = initialHex;
            TxtHexColor.Text = initialHex;
            UpdatePreview(initialHex);

            foreach (var hex in PresetColors)
            {
                var btn = new Button
                {
                    Background = (SolidColorBrush)new BrushConverter().ConvertFromString(hex)!,
                    Margin = new Thickness(2),
                    Tag = hex
                };
                btn.Click += (s, e) =>
                {
                    if (s is Button b && b.Tag is string colorHex)
                    {
                        SelectedHexColor = colorHex;
                        TxtHexColor.Text = colorHex;
                        UpdatePreview(colorHex);
                    }
                };
                ColorPaletteGrid.Children.Add(btn);
            }
        }

        private void UpdatePreview(string hex)
        {
            try
            {
                BorderPreview.Background = (SolidColorBrush)new BrushConverter().ConvertFromString(hex)!;
            }
            catch { }
        }

        private void BtnOk_Click(object sender, RoutedEventArgs e)
        {
            SelectedHexColor = TxtHexColor.Text.Trim();
            DialogResult = true;
            Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }
    }
}
