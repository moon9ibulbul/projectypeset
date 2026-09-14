using System;
using System.Collections.Generic;
using AstralTyper.Models;

namespace AstralTyper.Utils
{
    public class UndoManager
    {
        private readonly Stack<List<Layer>> _undoStack = new Stack<List<Layer>>();
        private readonly Stack<List<Layer>> _redoStack = new Stack<List<Layer>>();
        private const int MaxHistory = 30;

        public bool CanUndo => _undoStack.Count > 0;
        public bool CanRedo => _redoStack.Count > 0;

        public void SaveState(List<Layer> currentLayers)
        {
            var snapshot = CloneLayerList(currentLayers);
            _undoStack.Push(snapshot);
            _redoStack.Clear();

            if (_undoStack.Count > MaxHistory)
            {
                // Remove oldest
                var list = new List<List<Layer>>(_undoStack);
                list.RemoveAt(list.Count - 1);
                _undoStack.Clear();
                for (int i = list.Count - 1; i >= 0; i--)
                {
                    _undoStack.Push(list[i]);
                }
            }
        }

        public List<Layer>? Undo(List<Layer> currentLayers)
        {
            if (!CanUndo) return null;

            _redoStack.Push(CloneLayerList(currentLayers));
            return _undoStack.Pop();
        }

        public List<Layer>? Redo(List<Layer> currentLayers)
        {
            if (!CanRedo) return null;

            _undoStack.Push(CloneLayerList(currentLayers));
            return _redoStack.Pop();
        }

        public void Clear()
        {
            _undoStack.Clear();
            _redoStack.Clear();
        }

        private static List<Layer> CloneLayerList(List<Layer> layers)
        {
            var list = new List<Layer>();
            foreach (var layer in layers)
            {
                list.Add(layer.Clone());
            }
            return list;
        }
    }
}
