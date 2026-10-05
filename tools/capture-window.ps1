<#
.SYNOPSIS  Saves a picture of the Minecraft window only (not the desktop). Default: logs\window.png.
#>
param([string]$Out = (Join-Path $PSScriptRoot '..\logs\window.png'))
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class W { [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
 public struct RECT { public int L,T,R,B; } }
"@
$p = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $p) { 'no window'; exit 1 }
$r = New-Object W+RECT; [W]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
$bmp = New-Object System.Drawing.Bitmap ($r.R-$r.L), ($r.B-$r.T)
$g = [System.Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc(); [W]::PrintWindow($p.MainWindowHandle, $dc, 2) | Out-Null; $g.ReleaseHdc($dc)
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
$bmp.Save($Out); "saved $Out"
