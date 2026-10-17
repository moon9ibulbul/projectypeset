using System;
using System.Collections.Generic;

namespace AstralTyper.Utils
{
    public static class WarpPresetManager
    {
        public static List<string> GetBuiltInPresets()
        {
            return new List<string>
            {
                "None",
                "Arc Up",
                "Arc Down",
                "Bulge",
                "Pinch",
                "Wave",
                "Flag",
                "S-Curve"
            };
        }
    }
}
