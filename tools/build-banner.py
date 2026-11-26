"""Generate docs/gallery/voolkan-banner.png from self-contained HTML/SVG via headless Chromium."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "docs" / "gallery" / "voolkan-banner.png"
FONTS_DIR = REPO_ROOT / "tools" / "fonts"


def find_browser(explicit: str | None) -> str:
    if explicit:
        return explicit

    candidates = [
        shutil.which("chrome"),
        shutil.which("google-chrome"),
        shutil.which("chromium"),
        shutil.which("msedge"),
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate
    raise FileNotFoundError(
        "No Chromium-based browser found. Pass --browser /path/to/chrome."
    )


def iso_point(gx, gy, gz, ox=305.0, oy=515.0, sx=46.0, sy=26.5, sz=38.0):
    x = ox + (gx - gy) * sx
    y = oy + (gx + gy) * sy - gz * sz
    return x, y


def pts(coords):
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in coords)


def iso_cube(gx, gy, gz, h=1.0, style="dark"):
    t0 = iso_point(gx, gy, gz + h)
    t1 = iso_point(gx + 1, gy, gz + h)
    t2 = iso_point(gx + 1, gy + 1, gz + h)
    t3 = iso_point(gx, gy + 1, gz + h)
    b1 = iso_point(gx + 1, gy, gz)
    b2 = iso_point(gx + 1, gy + 1, gz)
    b3 = iso_point(gx, gy + 1, gz)

    if style == "ember":
        top_fill = "url(#voxelTopEmber)"
        left_fill = "url(#voxelLeftEmber)"
        right_fill = "url(#voxelRightEmber)"
        stroke = "rgba(255, 138, 124, 0.72)"
        sw = "1.2"
    elif style == "hot":
        top_fill = "#ff6a5c"
        left_fill = "#cc1d36"
        right_fill = "#8c0f24"
        stroke = "rgba(255, 198, 190, 0.88)"
        sw = "1.25"
    else:
        top_fill = "#261d22"
        left_fill = "#181216"
        right_fill = "#110c0f"
        stroke = "rgba(255, 228, 224, 0.16)"
        sw = "1.0"

    return f'''
      <g>
        <polygon points="{pts([t0, t1, t2, t3])}" fill="{top_fill}" stroke="{stroke}" stroke-width="{sw}" stroke-linejoin="round"/>
        <polygon points="{pts([t3, t2, b2, b3])}" fill="{left_fill}" stroke="{stroke}" stroke-width="{sw}" stroke-linejoin="round"/>
        <polygon points="{pts([t2, t1, b1, b2])}" fill="{right_fill}" stroke="{stroke}" stroke-width="{sw}" stroke-linejoin="round"/>
      </g>
    '''


def build_html(version_label: str) -> str:
    bricolage = (FONTS_DIR / "BricolageGrotesque.ttf").as_uri()
    inter = (FONTS_DIR / "Inter.ttf").as_uri()

    n = 5
    grid_lines = []
    for i in range(n + 1):
        a = iso_point(i, 0, 0)
        b = iso_point(i, n, 0)
        grid_lines.append(
            f'<line x1="{a[0]:.1f}" y1="{a[1]:.1f}" x2="{b[0]:.1f}" y2="{b[1]:.1f}"/>'
        )
        c = iso_point(0, i, 0)
        d = iso_point(n, i, 0)
        grid_lines.append(
            f'<line x1="{c[0]:.1f}" y1="{c[1]:.1f}" x2="{d[0]:.1f}" y2="{d[1]:.1f}"/>'
        )

    voxels_spec = [
        (0, 0, 0, 0.60, "dark"),
        (1, 0, 0, 1.05, "dark"),
        (2, 0, 0, 0.50, "dark"),
        (0, 1, 0, 0.85, "dark"),
        (1, 1, 0, 1.45, "ember"),
        (2, 1, 0, 1.00, "dark"),
        (3, 1, 0, 0.58, "dark"),
        (0, 2, 0, 0.48, "dark"),
        (1, 2, 0, 1.10, "dark"),
        (2, 2, 0, 1.85, "hot"),
        (3, 2, 0, 1.20, "ember"),
        (4, 2, 0, 0.52, "dark"),
        (1, 3, 0, 0.68, "dark"),
        (2, 3, 0, 1.30, "ember"),
        (3, 3, 0, 0.90, "dark"),
        (2, 4, 0, 0.52, "dark"),
        (3, 4, 0, 0.44, "dark"),
    ]
    voxels_spec.sort(key=lambda v: (v[0] + v[1], v[2]))
    voxels_svg = "\n".join(iso_cube(*v) for v in voxels_spec)

    def iso_plane_corners(z, pad=0.0):
        return [
            iso_point(-pad, -pad, z),
            iso_point(n + pad, -pad, z),
            iso_point(n + pad, n + pad, z),
            iso_point(-pad, n + pad, z),
        ]

    mid_z = 4.25
    top_z = 7.45

    mid_corners = iso_plane_corners(mid_z, pad=0.14)
    mid_grid = []
    for i in range(1, n):
        a = iso_point(i, -0.14, mid_z)
        b = iso_point(i, n + 0.14, mid_z)
        mid_grid.append(
            f'<line x1="{a[0]:.1f}" y1="{a[1]:.1f}" x2="{b[0]:.1f}" y2="{b[1]:.1f}"/>'
        )
        c = iso_point(-0.14, i, mid_z)
        d = iso_point(n + 0.14, i, mid_z)
        mid_grid.append(
            f'<line x1="{c[0]:.1f}" y1="{c[1]:.1f}" x2="{d[0]:.1f}" y2="{d[1]:.1f}"/>'
        )

    active_tiles = [(1, 1), (2, 1), (2, 2), (3, 2), (2, 3)]
    mid_tiles_svg = []
    for tx, ty in active_tiles:
        tc = [
            iso_point(tx + 0.08, ty + 0.08, mid_z),
            iso_point(tx + 0.92, ty + 0.08, mid_z),
            iso_point(tx + 0.92, ty + 0.92, mid_z),
            iso_point(tx + 0.08, ty + 0.92, mid_z),
        ]
        opacity = "0.46" if (tx, ty) == (2, 2) else "0.22"
        stroke = (
            "rgba(255, 118, 104, 0.8)"
            if (tx, ty) == (2, 2)
            else "rgba(255, 90, 77, 0.38)"
        )
        mid_tiles_svg.append(
            f'<polygon points="{pts(tc)}" fill="rgba(255, 74, 62, {opacity})" stroke="{stroke}" stroke-width="1.25"/>'
        )

    top_corners = iso_plane_corners(top_z, pad=0.14)

    top_grid_lines = []
    for v in [2.35, 3.00, 3.65]:
        ga = iso_point(0.45, v, top_z + 0.01)
        gb = iso_point(4.55, v, top_z + 0.01)
        top_grid_lines.append(
            f'<line x1="{ga[0]:.1f}" y1="{ga[1]:.1f}" x2="{gb[0]:.1f}" y2="{gb[1]:.1f}" '
            f'stroke="rgba(255, 232, 228, 0.11)" stroke-width="1" stroke-dasharray="3 5"/>'
        )

    trace_samples = [
        (0.45, 2.98),
        (0.80, 2.93),
        (1.15, 2.96),
        (1.50, 2.89),
        (1.85, 2.94),
        (2.20, 2.90),
        (2.55, 2.92),
        (2.90, 2.88),
        (3.25, 2.91),
        (3.60, 2.87),
        (3.95, 2.91),
        (4.30, 2.88),
        (4.58, 2.89),
    ]
    trace_pts = [iso_point(u, v, top_z + 0.04) for u, v in trace_samples]
    trace_d = "M " + " L ".join(f"{x:.1f} {y:.1f}" for x, y in trace_pts)

    area_pts = trace_pts + [
        iso_point(4.58, 3.65, top_z + 0.02),
        iso_point(0.45, 3.65, top_z + 0.02),
    ]
    area_d = "M " + " L ".join(f"{x:.1f} {y:.1f}" for x, y in area_pts) + " Z"

    hud_a = iso_point(0.55, 1.05, top_z + 0.02)
    hud_b = iso_point(2.55, 1.05, top_z + 0.02)
    hud_c = iso_point(0.55, 1.62, top_z + 0.02)
    hud_d = iso_point(3.75, 1.62, top_z + 0.02)

    pillar_lines = []
    for gx, gy in [(0, 0), (n, 0), (n, n), (0, n)]:
        p_bot = iso_point(gx, gy, 0)
        p_top = iso_point(gx, gy, top_z)
        pillar_lines.append(
            f'<line x1="{p_bot[0]:.1f}" y1="{p_bot[1]:.1f}" x2="{p_top[0]:.1f}" y2="{p_top[1]:.1f}" '
            f'stroke="rgba(255, 108, 96, 0.24)" stroke-width="1" stroke-dasharray="4 6"/>'
        )

    lbl_bot = iso_point(n + 0.14, -0.14, 0.0)
    lbl_mid = iso_point(n + 0.14, -0.14, mid_z)
    lbl_top = iso_point(n + 0.14, -0.14, top_z)

    return f'''<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
  @font-face {{
    font-family: "Bricolage Grotesque";
    src: url("{bricolage}") format("truetype");
    font-weight: 200 900;
    font-style: normal;
  }}
  @font-face {{
    font-family: "Inter";
    src: url("{inter}") format("truetype");
    font-weight: 100 900;
    font-style: normal;
  }}

  * {{
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }}

  html, body {{
    width: 1920px;
    height: 1080px;
    overflow: hidden;
    background: #090709;
    color: #f6f2f4;
    font-family: "Inter", -apple-system, BlinkMacSystemFont, sans-serif;
    -webkit-font-smoothing: antialiased;
    text-rendering: geometricPrecision;
  }}

  .canvas {{
    position: relative;
    width: 1920px;
    height: 1080px;
    background:
      radial-gradient(1020px 820px at 74% 48%, rgba(214, 34, 54, 0.22) 0%, rgba(140, 15, 34, 0.08) 44%, rgba(9, 7, 9, 0) 74%),
      radial-gradient(820px 620px at 14% 30%, rgba(255, 90, 77, 0.09) 0%, rgba(9, 7, 9, 0) 68%),
      linear-gradient(135deg, #0d0a0c 0%, #09070a 54%, #110a0d 100%);
    overflow: hidden;
  }}

  .bg-grid {{
    position: absolute;
    inset: 0;
    background-image:
      linear-gradient(to right, rgba(255, 232, 228, 0.032) 1px, transparent 1px),
      linear-gradient(to bottom, rgba(255, 232, 228, 0.032) 1px, transparent 1px);
    background-size: 64px 64px;
    -webkit-mask-image: radial-gradient(ellipse 84% 80% at 55% 50%, #000 35%, transparent 95%);
    mask-image: radial-gradient(ellipse 84% 80% at 55% 50%, #000 35%, transparent 95%);
  }}

  .bg-slash {{
    position: absolute;
    inset: 0;
    pointer-events: none;
  }}

  .grain {{
    position: absolute;
    inset: 0;
    opacity: 0.032;
    mix-blend-mode: overlay;
    pointer-events: none;
  }}

  .frame-marks {{
    position: absolute;
    inset: 44px 56px;
    pointer-events: none;
  }}
  .corner-meta {{
    position: absolute;
    font-size: 13px;
    font-weight: 550;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: rgba(218, 204, 210, 0.44);
    display: flex;
    align-items: center;
    gap: 12px;
  }}
  .corner-meta.tl {{ top: 0; left: 0; }}
  .corner-meta.tr {{ top: 0; right: 0; }}
  .corner-meta.bl {{ bottom: 0; left: 0; }}
  .corner-meta.br {{ bottom: 0; right: 0; }}

  .cross {{
    color: rgba(255, 96, 84, 0.72);
    font-weight: 600;
    letter-spacing: 0;
  }}

  .content {{
    position: relative;
    z-index: 2;
    width: 100%;
    height: 100%;
    display: grid;
    grid-template-columns: 1060px 860px;
    align-items: center;
  }}

  .left {{
    padding: 0 32px 0 116px;
    display: flex;
    flex-direction: column;
    justify-content: center;
  }}

  .eyebrow {{
    display: inline-flex;
    align-items: center;
    gap: 14px;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0.17em;
    text-transform: uppercase;
    color: #bfb0b6;
    margin-bottom: 34px;
  }}
  .eyebrow-dot {{
    width: 9px;
    height: 9px;
    transform: rotate(45deg);
    background: linear-gradient(135deg, #ff6457, #c21630);
    box-shadow: 0 0 14px rgba(255, 90, 77, 0.75);
  }}
  .eyebrow .sep {{
    color: rgba(255, 90, 77, 0.58);
    font-weight: 400;
  }}

  .brand-row {{
    display: flex;
    align-items: center;
    gap: 32px;
    margin-bottom: 34px;
  }}

  .logo-badge {{
    position: relative;
    width: 152px;
    height: 152px;
    flex-shrink: 0;
    filter: drop-shadow(0 24px 44px rgba(0, 0, 0, 0.74)) drop-shadow(0 0 40px rgba(255, 74, 62, 0.25));
  }}

  .wordmark-wrap {{
    display: flex;
    align-items: center;
    gap: 24px;
  }}

  .wordmark {{
    font-family: "Bricolage Grotesque", sans-serif;
    font-size: 150px;
    font-weight: 800;
    line-height: 0.88;
    letter-spacing: -0.048em;
    color: #faf7f8;
    text-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
  }}

  .version-pill {{
    display: inline-flex;
    align-items: center;
    padding: 10px 22px 11px;
    border-radius: 999px;
    background: linear-gradient(180deg, rgba(255, 90, 77, 0.18) 0%, rgba(179, 18, 43, 0.13) 100%);
    border: 1.5px solid rgba(255, 102, 90, 0.48);
    color: #ff8075;
    font-size: 27px;
    font-weight: 700;
    letter-spacing: -0.015em;
    transform: translateY(12px);
    box-shadow: inset 0 1px 0 rgba(255, 200, 194, 0.22), 0 8px 24px rgba(179, 18, 43, 0.22);
  }}

  .tagline {{
    font-size: 40px;
    font-weight: 500;
    line-height: 1.28;
    letter-spacing: -0.026em;
    color: #c8bdc2;
    max-width: 840px;
    margin-bottom: 56px;
  }}
  .tagline strong {{
    color: #f8f4f6;
    font-weight: 650;
  }}

  .specs {{
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 0;
    border-top: 1px solid rgba(255, 228, 224, 0.12);
    padding-top: 32px;
    max-width: 890px;
  }}

  .spec-item {{
    padding-right: 26px;
  }}
  .spec-item + .spec-item {{
    padding-left: 28px;
    border-left: 1px solid rgba(255, 228, 224, 0.10);
  }}

  .spec-kicker {{
    font-size: 13.5px;
    font-weight: 700;
    letter-spacing: 0.15em;
    text-transform: uppercase;
    color: #ff6e61;
    margin-bottom: 10px;
  }}

  .spec-title {{
    font-size: 24px;
    font-weight: 650;
    letter-spacing: -0.02em;
    color: #f5eff2;
    margin-bottom: 7px;
  }}

  .spec-sub {{
    font-size: 17px;
    font-weight: 450;
    line-height: 1.4;
    color: #a89ba2;
  }}

  .right {{
    position: relative;
    height: 1080px;
    display: flex;
    align-items: center;
    justify-content: flex-start;
    padding-left: 18px;
  }}

  .pipeline-svg {{
    width: 820px;
    height: 900px;
    overflow: visible;
  }}
</style>
</head>
<body>
<div class="canvas">
  <div class="bg-grid"></div>

  <svg class="bg-slash" viewBox="0 0 1920 1080" preserveAspectRatio="none">
    <defs>
      <linearGradient id="slashGrad" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#ff5a4d" stop-opacity="0.11"/>
        <stop offset="55%" stop-color="#b3122b" stop-opacity="0.055"/>
        <stop offset="100%" stop-color="#b3122b" stop-opacity="0"/>
      </linearGradient>
      <linearGradient id="slashEdge" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#ff5a4d" stop-opacity="0"/>
        <stop offset="48%" stop-color="#ff695c" stop-opacity="0.34"/>
        <stop offset="100%" stop-color="#b3122b" stop-opacity="0"/>
      </linearGradient>
    </defs>
    <polygon points="1105,0 1375,0 925,1080 655,1080" fill="url(#slashGrad)"/>
    <line x1="1105" y1="0" x2="655" y2="1080" stroke="url(#slashEdge)" stroke-width="1.2"/>
  </svg>

  <div class="frame-marks">
    <div class="corner-meta tl"><span class="cross">+</span><span>VOOLKAN RENDER ENGINE</span></div>
    <div class="corner-meta tr"><span>VK 1.2 · MOLTENVK · TBDR</span><span class="cross">+</span></div>
    <div class="corner-meta bl"><span class="cross">+</span><span>GITHUB.COM/RVYYV-N/VOOLKAN</span></div>
    <div class="corner-meta br"><span>OPEN SOURCE · LGPL-3.0</span><span class="cross">+</span></div>
  </div>

  <div class="content">
    <div class="left">
      <div class="eyebrow">
        <span class="eyebrow-dot"></span>
        <span>Fabric 1.21.11</span>
        <span class="sep">/</span>
        <span>Vulkan 1.2+</span>
        <span class="sep">/</span>
        <span>Open Source</span>
      </div>

      <div class="brand-row">
        <svg class="logo-badge" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
          <defs>
            <linearGradient id="iconBg" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#22191d"/>
              <stop offset="1" stop-color="#0e0a0d"/>
            </linearGradient>
            <linearGradient id="iconV" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#ff6254"/>
              <stop offset="1" stop-color="#b3122b"/>
            </linearGradient>
            <linearGradient id="iconRim" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#ffffff" stop-opacity="0.28"/>
              <stop offset="0.45" stop-color="#ff7a6e" stop-opacity="0.08"/>
              <stop offset="1" stop-color="#ffffff" stop-opacity="0.02"/>
            </linearGradient>
          </defs>
          <rect x="24" y="24" width="976" height="976" rx="236" fill="url(#iconBg)"/>
          <rect x="24" y="24" width="976" height="976" rx="236" fill="none" stroke="url(#iconRim)" stroke-width="10"/>
          <path d="M200 252 H372 L512 600 L652 252 H824 L580 790 H444 Z" fill="url(#iconV)"/>
          <path d="M512 600 L652 252 H700 L548 640 Z" fill="#000" opacity="0.2"/>
          <rect x="300" y="842" width="424" height="24" rx="12" fill="#ff5a4d" opacity="0.72"/>
        </svg>

        <div class="wordmark-wrap">
          <h1 class="wordmark">voolkan</h1>
          <span class="version-pill">{version_label}</span>
        </div>
      </div>

      <p class="tagline">
        <strong>High-FPS Vulkan renderer for Minecraft.</strong><br/>
        Built for legacy Windows GPUs and Apple Silicon.
      </p>

      <div class="specs">
        <div class="spec-item">
          <div class="spec-kicker">Windows</div>
          <div class="spec-title">Legacy &amp; Modern</div>
          <div class="spec-sub">Automatic VkRenderPass fallback on Vulkan 1.2 GPUs</div>
        </div>
        <div class="spec-item">
          <div class="spec-kicker">macOS</div>
          <div class="spec-title">Apple Silicon</div>
          <div class="spec-sub">MoltenVK TBDR &amp; live 1× scaling toggle</div>
        </div>
        <div class="spec-item">
          <div class="spec-kicker">Engine</div>
          <div class="spec-title">Steady Pacing</div>
          <div class="spec-sub">Precise frame cap, shader cache &amp; async uploads</div>
        </div>
      </div>
    </div>

    <div class="right">
      <svg class="pipeline-svg" viewBox="0 0 820 900" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <radialGradient id="stackGlow" cx="42%" cy="50%" r="48%">
            <stop offset="0%" stop-color="#ff463a" stop-opacity="0.28"/>
            <stop offset="52%" stop-color="#a81229" stop-opacity="0.09"/>
            <stop offset="100%" stop-color="#090709" stop-opacity="0"/>
          </radialGradient>

          <linearGradient id="voxelTopEmber" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#ff5e50" stop-opacity="0.76"/>
            <stop offset="100%" stop-color="#c41a32" stop-opacity="0.62"/>
          </linearGradient>
          <linearGradient id="voxelLeftEmber" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#a3152a" stop-opacity="0.75"/>
            <stop offset="100%" stop-color="#4e0b16" stop-opacity="0.86"/>
          </linearGradient>
          <linearGradient id="voxelRightEmber" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#700e1e" stop-opacity="0.80"/>
            <stop offset="100%" stop-color="#350710" stop-opacity="0.90"/>
          </linearGradient>

          <linearGradient id="midPlaneFill" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#ff5a4d" stop-opacity="0.16"/>
            <stop offset="50%" stop-color="#28161c" stop-opacity="0.54"/>
            <stop offset="100%" stop-color="#140d11" stop-opacity="0.70"/>
          </linearGradient>

          <linearGradient id="topPlaneFill" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#2c1d23" stop-opacity="0.78"/>
            <stop offset="100%" stop-color="#120c10" stop-opacity="0.86"/>
          </linearGradient>

          <linearGradient id="traceGrad" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#ff8c7f"/>
            <stop offset="50%" stop-color="#ff5244"/>
            <stop offset="100%" stop-color="#ff998c"/>
          </linearGradient>

          <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#ff5a4d" stop-opacity="0.24"/>
            <stop offset="100%" stop-color="#ff5a4d" stop-opacity="0.0"/>
          </linearGradient>

          <filter id="emberGlow" x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="5" result="blur"/>
            <feMerge>
              <feMergeNode in="blur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
        </defs>

        <ellipse cx="305" cy="465" rx="340" ry="300" fill="url(#stackGlow)"/>
        <g>{"".join(pillar_lines)}</g>

        <g id="stage-mesh">
          <polygon points="{pts(iso_plane_corners(0, pad=0.14))}"
            fill="rgba(22, 16, 20, 0.80)"
            stroke="rgba(255, 224, 220, 0.18)" stroke-width="1.35"/>
          <g stroke="rgba(255, 224, 220, 0.09)" stroke-width="1">
            {"".join(grid_lines)}
          </g>
          {voxels_svg}
        </g>

        <g id="stage-pass">
          <polygon points="{pts(mid_corners)}"
            fill="url(#midPlaneFill)"
            stroke="rgba(255, 102, 90, 0.58)" stroke-width="1.6"/>
          <g stroke="rgba(255, 124, 112, 0.17)" stroke-width="1">
            {"".join(mid_grid)}
          </g>
          {"".join(mid_tiles_svg)}
        </g>

        <g id="stage-present">
          <polygon points="{pts(top_corners)}"
            fill="url(#topPlaneFill)"
            stroke="rgba(255, 234, 230, 0.30)" stroke-width="1.5"/>
          <line x1="{top_corners[0][0]:.1f}" y1="{top_corners[0][1]:.1f}"
                x2="{top_corners[1][0]:.1f}" y2="{top_corners[1][1]:.1f}"
                stroke="rgba(255, 138, 126, 0.72)" stroke-width="2.2"/>
          <line x1="{hud_a[0]:.1f}" y1="{hud_a[1]:.1f}" x2="{hud_b[0]:.1f}" y2="{hud_b[1]:.1f}"
                stroke="rgba(255, 238, 235, 0.32)" stroke-width="3.2" stroke-linecap="round"/>
          <line x1="{hud_c[0]:.1f}" y1="{hud_c[1]:.1f}" x2="{hud_d[0]:.1f}" y2="{hud_d[1]:.1f}"
                stroke="rgba(255, 238, 235, 0.16)" stroke-width="2.6" stroke-linecap="round"/>
          {"".join(top_grid_lines)}
          <path d="{area_d}" fill="url(#areaGrad)"/>
          <path d="{trace_d}" fill="none" stroke="url(#traceGrad)" stroke-width="3.4"
                stroke-linecap="round" stroke-linejoin="round" filter="url(#emberGlow)"/>
          <circle cx="{trace_pts[-1][0]:.1f}" cy="{trace_pts[-1][1]:.1f}" r="10"
                  fill="rgba(255, 90, 77, 0.24)"/>
          <circle cx="{trace_pts[-1][0]:.1f}" cy="{trace_pts[-1][1]:.1f}" r="4.5"
                  fill="#fff4f2" stroke="#ff5a4d" stroke-width="2.5"/>
        </g>

        <g font-family="Inter, sans-serif">
          <circle cx="{lbl_top[0] + 12:.1f}" cy="{lbl_top[1]:.1f}" r="3.2" fill="#ff6a5c"/>
          <line x1="{lbl_top[0] + 15:.1f}" y1="{lbl_top[1]:.1f}" x2="{lbl_top[0] + 64:.1f}" y2="{lbl_top[1]:.1f}"
                stroke="rgba(255, 108, 96, 0.48)" stroke-width="1.25"/>
          <text x="{lbl_top[0] + 74:.1f}" y="{lbl_top[1] - 6:.1f}" fill="#ff6e61" font-size="12.5" font-weight="700" letter-spacing="2.2">03 · PRESENT</text>
          <text x="{lbl_top[0] + 74:.1f}" y="{lbl_top[1] + 15:.1f}" fill="#e6dce0" font-size="16.5" font-weight="550">Low-Latency Queue</text>

          <circle cx="{lbl_mid[0] + 12:.1f}" cy="{lbl_mid[1]:.1f}" r="3.2" fill="#ff6a5c"/>
          <line x1="{lbl_mid[0] + 15:.1f}" y1="{lbl_mid[1]:.1f}" x2="{lbl_mid[0] + 64:.1f}" y2="{lbl_mid[1]:.1f}"
                stroke="rgba(255, 108, 96, 0.48)" stroke-width="1.25"/>
          <text x="{lbl_mid[0] + 74:.1f}" y="{lbl_mid[1] - 6:.1f}" fill="#ff6e61" font-size="12.5" font-weight="700" letter-spacing="2.2">02 · PIPELINE</text>
          <text x="{lbl_mid[0] + 74:.1f}" y="{lbl_mid[1] + 15:.1f}" fill="#e6dce0" font-size="16.5" font-weight="550">VkRenderPass / TBDR</text>

          <circle cx="{lbl_bot[0] + 12:.1f}" cy="{lbl_bot[1]:.1f}" r="3.2" fill="#ff6a5c"/>
          <line x1="{lbl_bot[0] + 15:.1f}" y1="{lbl_bot[1]:.1f}" x2="{lbl_bot[0] + 64:.1f}" y2="{lbl_bot[1]:.1f}"
                stroke="rgba(255, 108, 96, 0.48)" stroke-width="1.25"/>
          <text x="{lbl_bot[0] + 74:.1f}" y="{lbl_bot[1] - 6:.1f}" fill="#ff6e61" font-size="12.5" font-weight="700" letter-spacing="2.2">01 · GEOMETRY</text>
          <text x="{lbl_bot[0] + 74:.1f}" y="{lbl_bot[1] + 15:.1f}" fill="#e6dce0" font-size="16.5" font-weight="550">Async Chunk Meshing</text>
        </g>
      </svg>
    </div>
  </div>

  <svg class="grain" viewBox="0 0 1920 1080" preserveAspectRatio="none">
    <filter id="noiseFilter">
      <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" stitchTiles="stitch"/>
    </filter>
    <rect width="100%" height="100%" filter="url(#noiseFilter)"/>
  </svg>
</div>
</body>
</html>
'''


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Render the voolkan 1920x1080 banner PNG."
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=DEFAULT_OUT,
        help="Output PNG path (default: docs/gallery/voolkan-banner.png)",
    )
    parser.add_argument(
        "--version",
        default="v1.5",
        help="Version pill text (default: v1.5)",
    )
    parser.add_argument(
        "--browser",
        default=None,
        help="Path to Chromium/Chrome/Edge executable",
    )
    parser.add_argument(
        "--thumb",
        type=Path,
        default=None,
        help="Optional 480x270 thumbnail output path",
    )
    args = parser.parse_args(argv)

    browser = find_browser(args.browser)
    out_path = args.out.resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    html = build_html(args.version)
    with tempfile.TemporaryDirectory(prefix="voolkan-banner-") as tmpdir:
        html_path = Path(tmpdir) / "banner.html"
        html_path.write_text(html, encoding="utf-8")
        cmd = [
            browser,
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--window-size=1920,1080",
            f"--screenshot={out_path}",
            html_path.as_uri(),
        ]
        subprocess.run(cmd, capture_output=True, text=True, check=True)

    with Image.open(out_path) as im:
        print(f"Rendered: {out_path} {im.size} {im.mode}")
        if args.thumb:
            thumb_path = args.thumb.resolve()
            thumb_path.parent.mkdir(parents=True, exist_ok=True)
            im.resize((480, 270), Image.Resampling.LANCZOS).save(thumb_path)
            print(f"Thumbnail: {thumb_path} (480, 270)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
