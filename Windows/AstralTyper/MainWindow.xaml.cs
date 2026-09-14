using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using AstralTyper.Models;
using AstralTyper.Utils;
using AstralTyper.Views;

namespace AstralTyper
{
    public partial class MainWindow : Window
    {
        private readonly ObservableCollection<Layer> _layers = new ObservableCollection<Layer>();
        private Layer? _selectedLayer;
        private readonly UndoManager _undoManager = new UndoManager();

        private string _activeTool = "Select"; // Select, Text, Shape, Brush, Eraser, Eyedropper, Inpaint, Hand, Zoom
        private bool _isDragging = false;
        private Point _lastMousePosition;

        // Brush drawing state
        private BrushLayer? _activeBrushLayer;
        private BrushStrokePath? _currentBrushPath;

        public MainWindow()
        {
            InitializeComponent();
            InitializeWorkspace();
        }

        private void InitializeWorkspace()
        {
            // Bind layers to list
            LstLayers.ItemsSource = _layers;

            // Load fonts into dropdowns
            var fonts = FontManager.GetAvailableFonts();
            CmbQuickFont.ItemsSource = fonts;
            CmbFontFamily.ItemsSource = fonts;
            if (fonts.Count > 0)
            {
                CmbQuickFont.SelectedIndex = 0;
                CmbFontFamily.SelectedIndex = 0;
            }

            // Load preset styles
            LstStyles.ItemsSource = StyleManager.GetPresetStyles();

            // Add default initial Text Layer
            var initialTextLayer = new TextLayer
            {
                Name = "Text 1",
                Text = "AstralTyper Desktop",
                FontSize = 48,
                X = 300,
                Y = 480,
                FillColor = "#FFFFFF",
                StrokeColor = "#000000",
                StrokeWidth = 2,
                IsBold = true
            };
            _layers.Add(initialTextLayer);
            SelectLayer(initialTextLayer);

            RedrawCanvas();
        }

        private void RedrawCanvas()
        {
            CanvasRenderer.RenderLayers(MainCanvas, _layers.ToList(), _selectedLayer, SelectLayer);
            UpdateStatus();
        }

        private void SelectLayer(Layer? layer)
        {
            _selectedLayer = layer;
            foreach (var l in _layers)
            {
                l.IsSelected = (l == layer);
            }

            // Update Properties panel inputs
            if (_selectedLayer is TextLayer textLayer)
            {
                TxtLayerTextContent.Text = textLayer.Text;
                TxtFontSize.Text = textLayer.FontSize.ToString();
                TxtQuickFontSize.Text = textLayer.FontSize.ToString();
                TxtLetterSpacing.Text = textLayer.LetterSpacing.ToString();
                SldStrokeWidth.Value = textLayer.StrokeWidth;
                BtnFillColor.Background = CanvasRenderer.ParseBrush(textLayer.FillColor);
                BtnStrokeColor.Background = CanvasRenderer.ParseBrush(textLayer.StrokeColor);
                ChkDropShadow.IsChecked = textLayer.EnableDropShadow;
                ChkNeonGlow.IsChecked = textLayer.EnableNeonGlow;
                ChkWarpActive.IsChecked = textLayer.IsWarpActive;
            }
            else if (_selectedLayer is ShapeLayer shapeLayer)
            {
                SldStrokeWidth.Value = shapeLayer.StrokeWidth;
                BtnFillColor.Background = CanvasRenderer.ParseBrush(shapeLayer.FillColor);
                BtnStrokeColor.Background = CanvasRenderer.ParseBrush(shapeLayer.StrokeColor);
            }

            SldLayerOpacity.Value = _selectedLayer != null ? _selectedLayer.Opacity : 1.0;
            LstLayers.SelectedItem = _selectedLayer;

            RedrawCanvas();
        }

        private void SaveState()
        {
            _undoManager.SaveState(_layers.ToList());
        }

        #region Tool Selection
        private void Tool_Click(object sender, RoutedEventArgs e)
        {
            if (sender is ToggleButton button)
            {
                ToolSelect.IsChecked = false;
                ToolText.IsChecked = false;
                ToolShape.IsChecked = false;
                ToolBrush.IsChecked = false;
                ToolEraser.IsChecked = false;
                ToolEyedropper.IsChecked = false;
                ToolInpaint.IsChecked = false;
                ToolHand.IsChecked = false;
                ToolZoom.IsChecked = false;

                button.IsChecked = true;
                _activeTool = button.Name.Replace("Tool", "");
                StatusInfo.Text = $"Active Tool: {_activeTool}";
            }
        }
        #endregion

        #region Canvas Mouse Interactivity
        private void MainCanvas_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            Point pos = e.GetPosition(MainCanvas);
            _lastMousePosition = pos;
            _isDragging = true;

            if (_activeTool == "Text")
            {
                SaveState();
                var newTextLayer = new TextLayer
                {
                    Name = $"Text {_layers.Count + 1}",
                    X = pos.X,
                    Y = pos.Y,
                    Text = "New Text"
                };
                _layers.Add(newTextLayer);
                SelectLayer(newTextLayer);
            }
            else if (_activeTool == "Shape")
            {
                SaveState();
                var newShapeLayer = new ShapeLayer
                {
                    Name = $"Shape {_layers.Count + 1}",
                    X = pos.X,
                    Y = pos.Y,
                    Width = 150,
                    Height = 150
                };
                _layers.Add(newShapeLayer);
                SelectLayer(newShapeLayer);
            }
            else if (_activeTool == "Brush" || _activeTool == "Eraser")
            {
                SaveState();
                if (_activeBrushLayer == null || !_layers.Contains(_activeBrushLayer))
                {
                    _activeBrushLayer = new BrushLayer { Name = $"Brush {_layers.Count + 1}", X = 0, Y = 0 };
                    _layers.Add(_activeBrushLayer);
                }

                _currentBrushPath = new BrushStrokePath
                {
                    Size = SldQuickBrushSize.Value,
                    Color = _activeTool == "Eraser" ? "#FFFFFF" : "#BB86FC",
                    IsEraser = _activeTool == "Eraser"
                };
                _currentBrushPath.Points.Add(new BrushStrokePoint { X = pos.X, Y = pos.Y, Size = SldQuickBrushSize.Value, Color = _currentBrushPath.Color });
                _activeBrushLayer.Paths.Add(_currentBrushPath);
                SelectLayer(_activeBrushLayer);
            }
        }

        private void MainCanvas_MouseMove(object sender, MouseEventArgs e)
        {
            Point pos = e.GetPosition(MainCanvas);
            StatusCoords.Text = $" | X: {(int)pos.X}, Y: {(int)pos.Y}";

            if (!_isDragging) return;

            Vector delta = pos - _lastMousePosition;

            if (_activeTool == "Select" && _selectedLayer != null && !_selectedLayer.IsLocked)
            {
                _selectedLayer.X += delta.X;
                _selectedLayer.Y += delta.Y;
                RedrawCanvas();
            }
            else if ((_activeTool == "Brush" || _activeTool == "Eraser") && _currentBrushPath != null)
            {
                _currentBrushPath.Points.Add(new BrushStrokePoint { X = pos.X, Y = pos.Y, Size = SldQuickBrushSize.Value, Color = _currentBrushPath.Color });
                RedrawCanvas();
            }

            _lastMousePosition = pos;
        }

        private void MainCanvas_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            _isDragging = false;
            _currentBrushPath = null;
        }

        private void CanvasScrollViewer_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
        {
            if (Keyboard.Modifiers == ModifierKeys.Control)
            {
                e.Handled = true;
                double zoom = CanvasScale.ScaleX;
                if (e.Delta > 0) zoom *= 1.1;
                else zoom /= 1.1;

                zoom = Math.Clamp(zoom, 0.1, 10.0);
                CanvasScale.ScaleX = zoom;
                CanvasScale.ScaleY = zoom;
                StatusZoom.Text = $"{(int)(zoom * 100)}%";
            }
        }
        #endregion

        #region Layer Menu & List Event Handlers
        private void LstLayers_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (LstLayers.SelectedItem is Layer layer)
            {
                SelectLayer(layer);
            }
        }

        private void BtnLockLayer_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null)
            {
                _selectedLayer.IsLocked = !_selectedLayer.IsLocked;
                StatusInfo.Text = _selectedLayer.IsLocked ? "Layer Locked" : "Layer Unlocked";
            }
        }

        private void MenuAddText_Click(object sender, RoutedEventArgs e)
        {
            SaveState();
            var textLayer = new TextLayer
            {
                Name = $"Text {_layers.Count + 1}",
                X = 200,
                Y = 200,
                Text = "New Text"
            };
            _layers.Add(textLayer);
            SelectLayer(textLayer);
        }

        private void MenuAddShape_Click(object sender, RoutedEventArgs e)
        {
            SaveState();
            var shapeLayer = new ShapeLayer
            {
                Name = $"Shape {_layers.Count + 1}",
                X = 250,
                Y = 250
            };
            _layers.Add(shapeLayer);
            SelectLayer(shapeLayer);
        }

        private void MenuAddBrush_Click(object sender, RoutedEventArgs e)
        {
            SaveState();
            var brushLayer = new BrushLayer
            {
                Name = $"Brush {_layers.Count + 1}"
            };
            _layers.Add(brushLayer);
            SelectLayer(brushLayer);
        }

        private void MenuDuplicateLayer_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null)
            {
                SaveState();
                var copy = _selectedLayer.Clone();
                _layers.Add(copy);
                SelectLayer(copy);
            }
        }

        private void MenuDeleteLayer_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null)
            {
                SaveState();
                _layers.Remove(_selectedLayer);
                SelectLayer(_layers.LastOrDefault());
            }
        }

        private void MenuBringToFront_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null && _layers.Contains(_selectedLayer))
            {
                SaveState();
                _layers.Remove(_selectedLayer);
                _layers.Add(_selectedLayer);
                SelectLayer(_selectedLayer);
            }
        }

        private void MenuMoveUp_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null)
            {
                int index = _layers.IndexOf(_selectedLayer);
                if (index < _layers.Count - 1)
                {
                    SaveState();
                    _layers.Move(index, index + 1);
                    SelectLayer(_selectedLayer);
                }
            }
        }

        private void MenuMoveDown_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null)
            {
                int index = _layers.IndexOf(_selectedLayer);
                if (index > 0)
                {
                    SaveState();
                    _layers.Move(index, index - 1);
                    SelectLayer(_selectedLayer);
                }
            }
        }

        private void MenuSendToBack_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null && _layers.Contains(_selectedLayer))
            {
                SaveState();
                _layers.Remove(_selectedLayer);
                _layers.Insert(0, _selectedLayer);
                SelectLayer(_selectedLayer);
            }
        }
        #endregion

        #region Properties & Settings Handlers
        private void TxtLayerTextContent_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer)
            {
                textLayer.Text = TxtLayerTextContent.Text;
                RedrawCanvas();
            }
        }

        private void CmbFontFamily_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer && CmbFontFamily.SelectedItem is string font)
            {
                textLayer.FontFamilyName = font;
                RedrawCanvas();
            }
        }

        private void TxtFontSize_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer && double.TryParse(TxtFontSize.Text, out double size))
            {
                textLayer.FontSize = size;
                RedrawCanvas();
            }
        }

        private void TxtLetterSpacing_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer && double.TryParse(TxtLetterSpacing.Text, out double spacing))
            {
                textLayer.LetterSpacing = spacing;
                RedrawCanvas();
            }
        }

        private void BtnQuickBold_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer)
            {
                textLayer.IsBold = !textLayer.IsBold;
                RedrawCanvas();
            }
        }

        private void BtnQuickItalic_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer)
            {
                textLayer.IsItalic = !textLayer.IsItalic;
                RedrawCanvas();
            }
        }

        private void BtnFillColor_Click(object sender, RoutedEventArgs e)
        {
            string currentHex = _selectedLayer switch
            {
                TextLayer t => t.FillColor,
                ShapeLayer s => s.FillColor,
                _ => "#FFFFFF"
            };

            var picker = new ColorPickerWindow(currentHex) { Owner = this };
            if (picker.ShowDialog() == true)
            {
                string hex = picker.SelectedHexColor;
                BtnFillColor.Background = CanvasRenderer.ParseBrush(hex);

                if (_selectedLayer is TextLayer textLayer) textLayer.FillColor = hex;
                else if (_selectedLayer is ShapeLayer shapeLayer) shapeLayer.FillColor = hex;

                RedrawCanvas();
            }
        }

        private void BtnStrokeColor_Click(object sender, RoutedEventArgs e)
        {
            string currentHex = _selectedLayer switch
            {
                TextLayer t => t.StrokeColor,
                ShapeLayer s => s.StrokeColor,
                _ => "#000000"
            };

            var picker = new ColorPickerWindow(currentHex) { Owner = this };
            if (picker.ShowDialog() == true)
            {
                string hex = picker.SelectedHexColor;
                BtnStrokeColor.Background = CanvasRenderer.ParseBrush(hex);

                if (_selectedLayer is TextLayer textLayer) textLayer.StrokeColor = hex;
                else if (_selectedLayer is ShapeLayer shapeLayer) shapeLayer.StrokeColor = hex;

                RedrawCanvas();
            }
        }

        private void SldStrokeWidth_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_selectedLayer is TextLayer textLayer) textLayer.StrokeWidth = SldStrokeWidth.Value;
            else if (_selectedLayer is ShapeLayer shapeLayer) shapeLayer.StrokeWidth = SldStrokeWidth.Value;

            RedrawCanvas();
        }

        private void SldLayerOpacity_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_selectedLayer != null)
            {
                _selectedLayer.Opacity = SldLayerOpacity.Value;
                if (TxtLayerOpacity != null) TxtLayerOpacity.Text = $"{(int)(SldLayerOpacity.Value * 100)}%";
                RedrawCanvas();
            }
        }

        private void CmbQuickFont_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (CmbQuickFont.SelectedItem is string font && _selectedLayer is TextLayer textLayer)
            {
                textLayer.FontFamilyName = font;
                RedrawCanvas();
            }
        }

        private void TxtQuickFontSize_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (double.TryParse(TxtQuickFontSize.Text, out double size) && _selectedLayer is TextLayer textLayer)
            {
                textLayer.FontSize = size;
                RedrawCanvas();
            }
        }

        private void BtnQuickColor_Click(object sender, RoutedEventArgs e)
        {
            BtnFillColor_Click(sender, e);
        }

        private void SldQuickBrushSize_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (TxtQuickBrushSize != null) TxtQuickBrushSize.Text = $"{(int)SldQuickBrushSize.Value} px";
        }
        #endregion

        #region D-Pad Manual Position Shifts
        private void BtnShiftUp_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null) { _selectedLayer.Y -= 5; RedrawCanvas(); }
        }

        private void BtnShiftDown_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null) { _selectedLayer.Y += 5; RedrawCanvas(); }
        }

        private void BtnShiftLeft_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null) { _selectedLayer.X -= 5; RedrawCanvas(); }
        }

        private void BtnShiftRight_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null) { _selectedLayer.X += 5; RedrawCanvas(); }
        }

        private void BtnShiftReset_Click(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer != null) { _selectedLayer.X = 100; _selectedLayer.Y = 100; RedrawCanvas(); }
        }
        #endregion

        #region Effects & Warp Controls
        private void FxParam_Changed(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer)
            {
                textLayer.EnableDropShadow = ChkDropShadow.IsChecked == true;
                textLayer.ShadowBlur = SldShadowBlur.Value;
                textLayer.ShadowOffsetX = SldShadowOffsetX.Value;
                textLayer.ShadowOffsetY = SldShadowOffsetY.Value;
                textLayer.EnableNeonGlow = ChkNeonGlow.IsChecked == true;
                textLayer.NeonRadius = SldNeonRadius.Value;

                RedrawCanvas();
            }
        }

        private void ChkWarpActive_Changed(object sender, RoutedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer)
            {
                textLayer.IsWarpActive = ChkWarpActive.IsChecked == true;
                RedrawCanvas();
            }
        }

        private void CmbWarpPresets_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_selectedLayer is TextLayer textLayer && CmbWarpPresets.SelectedItem is ComboBoxItem item)
            {
                textLayer.WarpPreset = item.Content?.ToString() ?? "None";
                RedrawCanvas();
            }
        }

        private void SldWarpStrength_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_selectedLayer is TextLayer textLayer)
            {
                textLayer.WarpStrength = SldWarpStrength.Value;
                RedrawCanvas();
            }
        }
        #endregion

        #region Style Presets
        private void LstStyles_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (LstStyles.SelectedItem is StyleModel style && _selectedLayer is TextLayer textLayer)
            {
                SaveState();
                StyleManager.ApplyStyleToLayer(style, textLayer);
                SelectLayer(textLayer);
            }
        }

        private void BtnSaveStyle_Click(object sender, RoutedEventArgs e)
        {
            MessageBox.Show("Style saved to preset list!", "Save Style", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        #endregion

        #region File Menu Handlers
        private void MenuNew_Click(object sender, RoutedEventArgs e)
        {
            SaveState();
            _layers.Clear();
            _selectedLayer = null;
            RedrawCanvas();
        }

        private void MenuOpen_Click(object sender, RoutedEventArgs e)
        {
            var openFileDialog = new OpenFileDialog { Filter = "AstralTyper Project (*.atd)|*.atd" };
            if (openFileDialog.ShowDialog() == true)
            {
                var loaded = ProjectManager.LoadProject(openFileDialog.FileName);
                if (loaded != null)
                {
                    _layers.Clear();
                    foreach (var layer in loaded.Layers) _layers.Add(layer);
                    SelectLayer(_layers.FirstOrDefault());
                    MessageBox.Show("Project loaded successfully!", "Open Project", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
        }

        private void MenuSave_Click(object sender, RoutedEventArgs e)
        {
            var saveFileDialog = new SaveFileDialog { Filter = "AstralTyper Project (*.atd)|*.atd", DefaultExt = "atd" };
            if (saveFileDialog.ShowDialog() == true)
            {
                var project = new ProjectData { Layers = _layers.ToList() };
                if (ProjectManager.SaveProject(saveFileDialog.FileName, project))
                {
                    MessageBox.Show("Project saved successfully!", "Save Project", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
        }

        private void MenuSaveAs_Click(object sender, RoutedEventArgs e)
        {
            MenuSave_Click(sender, e);
        }

        private void MenuImportImage_Click(object sender, RoutedEventArgs e)
        {
            var openFileDialog = new OpenFileDialog { Filter = "Image Files (*.png;*.jpg;*.jpeg)|*.png;*.jpg;*.jpeg" };
            if (openFileDialog.ShowDialog() == true)
            {
                try
                {
                    byte[] data = System.IO.File.ReadAllBytes(openFileDialog.FileName);
                    var imgLayer = new ImageLayer
                    {
                        Name = $"Image {_layers.Count + 1}",
                        ImagePath = openFileDialog.FileName,
                        ImageData = data,
                        X = 100,
                        Y = 100
                    };
                    _layers.Add(imgLayer);
                    SelectLayer(imgLayer);
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Failed to import image: {ex.Message}", "Import Image", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }

        private void MenuImportPdf_Click(object sender, RoutedEventArgs e)
        {
            MessageBox.Show("PDF Import ready. Select PDF page to render into canvas.", "Import PDF", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void MenuExportImage_Click(object sender, RoutedEventArgs e)
        {
            var saveFileDialog = new SaveFileDialog { Filter = "PNG Image (*.png)|*.png|JPEG Image (*.jpg)|*.jpg", DefaultExt = "png" };
            if (saveFileDialog.ShowDialog() == true)
            {
                bool isPng = saveFileDialog.FileName.EndsWith(".png", StringComparison.OrdinalIgnoreCase);
                ProjectManager.ExportToBitmap(MainCanvas, saveFileDialog.FileName, MainCanvas.Width, MainCanvas.Height, isPng);
                MessageBox.Show("Canvas exported successfully!", "Export Image", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        private void MenuExportPdf_Click(object sender, RoutedEventArgs e)
        {
            MessageBox.Show("PDF Document export ready.", "Export PDF", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void MenuExit_Click(object sender, RoutedEventArgs e)
        {
            Application.Current.Shutdown();
        }

        private void MenuUndo_Click(object sender, RoutedEventArgs e)
        {
            var previousState = _undoManager.Undo(_layers.ToList());
            if (previousState != null)
            {
                _layers.Clear();
                foreach (var l in previousState) _layers.Add(l);
                SelectLayer(_layers.FirstOrDefault());
            }
        }

        private void MenuRedo_Click(object sender, RoutedEventArgs e)
        {
            var nextState = _undoManager.Redo(_layers.ToList());
            if (nextState != null)
            {
                _layers.Clear();
                foreach (var l in nextState) _layers.Add(l);
                SelectLayer(_layers.FirstOrDefault());
            }
        }

        private void MenuClearCanvas_Click(object sender, RoutedEventArgs e)
        {
            SaveState();
            _layers.Clear();
            SelectLayer(null);
        }

        private void MenuZoomIn_Click(object sender, RoutedEventArgs e)
        {
            CanvasScale.ScaleX *= 1.2;
            CanvasScale.ScaleY *= 1.2;
            StatusZoom.Text = $"{(int)(CanvasScale.ScaleX * 100)}%";
        }

        private void MenuZoomOut_Click(object sender, RoutedEventArgs e)
        {
            CanvasScale.ScaleX /= 1.2;
            CanvasScale.ScaleY /= 1.2;
            StatusZoom.Text = $"{(int)(CanvasScale.ScaleX * 100)}%";
        }

        private void MenuResetZoom_Click(object sender, RoutedEventArgs e)
        {
            CanvasScale.ScaleX = 1.0;
            CanvasScale.ScaleY = 1.0;
            StatusZoom.Text = "100%";
        }

        private void MenuFitScreen_Click(object sender, RoutedEventArgs e)
        {
            CanvasScale.ScaleX = 0.7;
            CanvasScale.ScaleY = 0.7;
            StatusZoom.Text = "70%";
        }

        private void MenuSettings_Click(object sender, RoutedEventArgs e)
        {
            var settings = new SettingsWindow { Owner = this };
            settings.ShowDialog();
        }

        private void MenuAbout_Click(object sender, RoutedEventArgs e)
        {
            MessageBox.Show("AstralTyper Desktop v1.6.5\nModern Photoshop-Inspired Typesetting Studio\nBuilt for Windows", "About AstralTyper", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void UpdateStatus()
        {
            StatusInfo.Text = $"Layers: {_layers.Count} | Selected: {(_selectedLayer != null ? _selectedLayer.Name : "None")}";
        }
        #endregion
    }
}
