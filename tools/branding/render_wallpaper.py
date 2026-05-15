#!/usr/bin/env python3
"""
Render the Mythara splash mark as a phone wallpaper PNG.

Layered composition (bottom → top):

  1. Vertical purple-black gradient — near-pure black with a violet
     undertone at the top (#06040C) deepening into Mythara purple
     toward the bottom (#2A1740). Reads "deep space" rather than
     flat grey, while staying dark enough that lockscreen text +
     the petal colors pop.

  2. Light-purple node-graph mesh (optional, on by default) —
     procedural neural-net constellation. Jittered grid points
     connected to their nearest neighbors with thin lines. Subtle:
     low alpha so it sits as a backdrop, not a focal element.

  3. Character silhouette (optional, --character <path>) —
     background-removed via colour-distance threshold against the
     image's corner-sampled background, tinted light purple, alpha
     dialled down so the figure reads as a guardian watermark
     rather than overpowering the brand mark.

  4. Geometric rose — same 10 petals + cyan hexagon as
     splash_icon.xml. 5 large purple petals (#6B50FF) at
     0/72/144/216/288 deg, 5 smaller lavender petals (#9B86FF)
     interleaved at 36/108/180/252/324 deg, hexagon nucleus
     (#68FFD6).

  5. Wordmark — "MYTHARA" (JetBrains Mono Bold, lavender, letter-
     spaced) sits just below the rose, with a smaller "1.0"
     (JetBrains Mono Regular, cyan) underneath as the version
     stamp, and the seven-word backronym "Mind-Yoked Tonal-Haptic
     Adaptive Resonant Assistant" beneath that in a muted lavender
     — one word per MYTHARA letter, mapping the brand to what the
     system actually ships:
       M ind         the personalized LearningVault
       Y oked        tied to YOUR state, not generic
       T onal        Music Mode + Resonance binaural / isochronic
       H aptic       watch buzz on insights, reminders, sessions
       A daptive     learns continuously from your data
       R esonant     Resonance Mode closed-loop biometric regulation
       A ssistant    the agent that runs commands & talks back

Default output resolution: 1280 x 2856 (Pixel 10 Pro physical).
Override via --width / --height for other devices, --out for a
different output path. Fonts are pulled from the :app module so the
wordmark matches the watch-face / chat-UI typography.

Usage:
  python3 tools/branding/render_wallpaper.py
  python3 tools/branding/render_wallpaper.py --out /tmp/wp.png \\
      --width 1080 --height 2424   # Pixel 9 Pro Fold
  python3 tools/branding/render_wallpaper.py \\
      --character "~/Downloads/shared image.jpeg" --no-mesh

After rendering, push to a paired device and apply via the
WallpaperApplyReceiver shipped in :app:

  adb -s <serial> push /tmp/mythara_wallpaper.png \\
      /sdcard/Pictures/mythara_wallpaper.png
  adb -s <serial> shell am broadcast \\
      -a com.mythara.action.APPLY_WALLPAPER \\
      -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \\
      --es path /sdcard/Pictures/mythara_wallpaper.png \\
      --es target both

Requires: Pillow >= 10, numpy >= 1.20 (for the silhouette / mesh paths).
"""
import argparse
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont


# ─── Palette (matches splash_icon.xml + the watch face) ──────────────
BG_TOP = (0x06, 0x04, 0x0C)       # near-pure black with a violet bias
BG_BOT = (0x2A, 0x17, 0x40)       # deeper Mythara purple
PURPLE = (0x6B, 0x50, 0xFF)       # large petals
LAVENDER = (0x9B, 0x86, 0xFF)     # small petals + wordmark
CYAN = (0x68, 0xFF, 0xD6)         # hexagon nucleus + version stamp
SUBTITLE_COLOR = (0x76, 0x68, 0xB8)  # muted lavender for the tagline
MESH_COLOR = (0xB8, 0xA8, 0xE8)      # light purple for the node graph
SILHOUETTE_COLOR = (0xB8, 0xA8, 0xE8)  # same light purple — guardian watermark


# ─── Repo-anchored asset paths ───────────────────────────────────────
REPO_ROOT = Path(__file__).resolve().parents[2]
FONT_BOLD = REPO_ROOT / "app/src/main/res/font/jetbrains_mono_bold.ttf"
FONT_REG = REPO_ROOT / "app/src/main/res/font/jetbrains_mono_regular.ttf"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--width", type=int, default=1280, help="output width in px (default 1280, Pixel 10 Pro)")
    p.add_argument("--height", type=int, default=2856, help="output height in px (default 2856, Pixel 10 Pro)")
    p.add_argument(
        "--out",
        type=Path,
        default=Path("/tmp/mythara_wallpaper.png"),
        help="output PNG path (default /tmp/mythara_wallpaper.png)",
    )
    p.add_argument(
        "--character",
        type=str,
        default=None,
        help="optional character image to extract a guardian silhouette from",
    )
    p.add_argument(
        "--silhouette-alpha",
        type=int,
        default=70,
        help="max alpha for the silhouette overlay (0-255, default 70 — watermark)",
    )
    p.add_argument(
        "--no-mesh",
        action="store_true",
        help="skip the node-graph mesh background layer",
    )
    p.add_argument(
        "--mesh-seed",
        type=int,
        default=42,
        help="RNG seed for the node-graph mesh layout (default 42 — reproducible)",
    )
    p.add_argument(
        "--no-rose",
        action="store_true",
        help="skip the rose layer (used to bake static layers for the live "
             "wallpaper, which draws an animated rose on top of this PNG)",
    )
    return p.parse_args()


def rotate(px: float, py: float, deg: float) -> tuple[float, float]:
    """Rotate (px,py) around the origin by `deg` degrees."""
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return (px * c - py * s, px * s + py * c)


def render_petal(
    draw: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    scale: float,
    deg: float,
    color: tuple[int, int, int],
    p_local: tuple[tuple[float, float], ...],
) -> None:
    """
    Render one petal. Source paths are diamond polygons in 108-space
    centered on (54,54). p_local is a tuple of (dx,dy) offsets from
    that center *before* rotation, in source units.
    """
    pts = []
    for px, py in p_local:
        rx, ry = rotate(px, py, deg)
        pts.append((cx + rx * scale, cy + ry * scale))
    draw.polygon(pts, fill=color)


def render_gradient(img: Image.Image) -> None:
    """Vertical linear gradient from BG_TOP to BG_BOT, one row at a
    time. Pillow has no native gradient primitive but full-canvas
    line draws are cheap at phone resolutions."""
    w, h = img.size
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        r = round(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t)
        g = round(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t)
        b = round(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t)
        draw.line([(0, y), (w, y)], fill=(r, g, b))


def render_node_mesh(img: Image.Image, seed: int = 42) -> None:
    """
    Composite a subtle "neural-net constellation" onto the gradient.

    Strategy: lay a regular grid of cells, jitter each cell's centre
    by up to half a cell so points avoid an obvious lattice look, then
    connect each point to every other point within MAX_LINK_DIST.
    Because the grid bounds connection candidates, this stays O(n)
    even for thousands of nodes — no all-pairs sweep needed.

    Drawn into a separate RGBA layer so the mesh's per-segment alpha
    can vary without disturbing the gradient pixels (longer links
    fade out, giving a depth-of-field feel).
    """
    rng = random.Random(seed)
    w, h = img.size

    # Tune density relative to the canvas — ~22 cells across a
    # 1280-wide canvas gives ~330 nodes total at 2856 tall, dense
    # enough to read as a network without becoming busy.
    cells_x = max(8, w // 60)
    cell_size = w / cells_x
    cells_y = math.ceil(h / cell_size)
    max_link_dist = cell_size * 1.6  # diag-ish — a few neighbours per node

    points = []
    for ix in range(cells_x):
        for iy in range(cells_y):
            jitter_x = rng.uniform(-0.5, 0.5) * cell_size
            jitter_y = rng.uniform(-0.5, 0.5) * cell_size
            px = (ix + 0.5) * cell_size + jitter_x
            py = (iy + 0.5) * cell_size + jitter_y
            points.append((px, py, ix, iy))

    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # Bucket lookup by cell so we only check immediate neighbour cells
    # for link candidates.
    by_cell = {}
    for p in points:
        by_cell.setdefault((p[2], p[3]), []).append(p)

    base_alpha = 70  # 0-255 — central segment opacity at zero distance
    for x1, y1, ix, iy in points:
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                bucket = by_cell.get((ix + dx, iy + dy), ())
                for x2, y2, _, _ in bucket:
                    if (x2, y2) <= (x1, y1):
                        continue  # dedupe pairs
                    d = math.hypot(x2 - x1, y2 - y1)
                    if d > max_link_dist:
                        continue
                    # Linear fade from base_alpha at d=0 to 0 at the
                    # max link distance, so wide-angle links read as
                    # softer / further away.
                    a = round(base_alpha * (1 - d / max_link_dist))
                    if a <= 0:
                        continue
                    draw.line(
                        [(x1, y1), (x2, y2)],
                        fill=(*MESH_COLOR, a),
                        width=1,
                    )
        # Tiny dot at each node — slightly brighter than the lines.
        draw.ellipse(
            [(x1 - 1.5, y1 - 1.5), (x1 + 1.5, y1 + 1.5)],
            fill=(*MESH_COLOR, 130),
        )

    img.alpha_composite(layer) if img.mode == "RGBA" else img.paste(
        layer, (0, 0), layer
    )


def _extract_silhouette_mask(src: Image.Image) -> np.ndarray:
    """
    Build a binary foreground mask from a character illustration with a
    near-uniform background. Strategy: flood-fill the four corners
    with a sentinel colour using a generous similarity tolerance,
    then mark every non-sentinel pixel as foreground.

    This catches the entire connected background region (including
    arbitrarily-shaped concavities around limbs / between feet)
    without needing per-pixel distance thresholds — which fail when
    the character's skin / white clothing happens to be similar to
    the background colour, as is the case here.

    Returns a (H, W) uint8 array — 255 = foreground, 0 = background.
    """
    w, h = src.size
    work = src.convert("RGB").copy()
    # Pick a sentinel that is extremely unlikely to appear in the
    # source (RGB channel triple of 1/2/3 is essentially black).
    sentinel = (1, 2, 3)
    # Tolerance is "max per-channel distance" — 35 is generous enough
    # to swallow the JPEG-compression noise in cream backgrounds
    # without bleeding into pigmented character regions.
    for cx, cy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        ImageDraw.floodfill(work, (cx, cy), sentinel, thresh=35)
    arr = np.array(work)
    is_bg = (arr[..., 0] == sentinel[0]) & (arr[..., 1] == sentinel[1]) & (arr[..., 2] == sentinel[2])
    mask = np.where(is_bg, 0, 255).astype(np.uint8)
    return mask


def render_silhouette(
    img: Image.Image,
    source_path: Path,
    target_y_top: int,
    max_alpha: int = 70,
    target_height_frac: float = 0.62,
    vertically_center: bool = False,
) -> None:
    """
    Background-remove `source_path`, tint the result light purple, and
    composite it onto `img` as a guardian watermark.

    Background removal: corner-flood-fill (see _extract_silhouette_mask
    for rationale). The resulting binary mask is feathered with a
    small Gaussian blur so the silhouette edge reads like a soft
    watermark rather than a hard die-cut.

    Position + scale: the figure is scaled so its height equals
    `target_height_frac * canvas_height`, then centred horizontally
    and anchored vertically by its TOP at `target_y_top`. The default
    0.62 is tuned so the warrior dominates the lower 2/3 of the
    canvas while the rose + wordmark sit cleanly above her.
    """
    if not source_path.exists():
        sys.exit(f"--character image not found: {source_path}")
    src = Image.open(source_path).convert("RGBA")
    src_w, src_h = src.size

    mask = _extract_silhouette_mask(src)  # (H, W) uint8 — figure outline
    arr = np.array(src)                   # (H, W, 4)

    # Per-pixel luminance of the source (Rec. 601 weights — the same
    # the eye uses), 0..1. We invert it so dark pixels (line work,
    # shadows, eyes, jewellery edges) stay opaque and pale pixels
    # (skin, white crop top) fade. This keeps the character's
    # internal detail readable instead of collapsing the whole
    # figure into a flat purple blob.
    rgb = arr[..., :3].astype(np.float32)
    luma = 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]
    inv = 1.0 - (luma / 255.0)            # 0=white, 1=black

    # Lift the floor a little so even bright skin still leaves a
    # ghost of itself — pure 0 alpha there would punch holes in the
    # otherwise-coherent silhouette. 0.30 floor gives a nice "tinted
    # tracing paper" feel inside the figure.
    inv = 0.30 + 0.70 * inv

    # Combine the figure mask (binary, defines where the character
    # ends) with the luminance modulation (continuous, defines how
    # opaque each interior pixel is). Multiply both, then scale by
    # the watermark intensity cap.
    fig_mask = mask.astype(np.float32) / 255.0
    alpha = (fig_mask * inv * max_alpha).astype(np.uint8)

    tint = np.zeros((src_h, src_w, 4), dtype=np.uint8)
    tint[..., 0] = SILHOUETTE_COLOR[0]
    tint[..., 1] = SILHOUETTE_COLOR[1]
    tint[..., 2] = SILHOUETTE_COLOR[2]
    tint[..., 3] = alpha
    tinted = Image.fromarray(tint, mode="RGBA")

    # Edge feather — a small blur makes the silhouette feel less
    # pasted-on, especially after we scale it up below.
    tinted = tinted.filter(ImageFilter.GaussianBlur(radius=1.0))

    # Scale to the requested fraction of the canvas height, then
    # cap width to 95% of canvas width if the source is wide.
    canvas_w, canvas_h = img.size
    target_h = int(canvas_h * target_height_frac)
    scale = target_h / src_h
    if src_w * scale > canvas_w * 0.95:
        scale = (canvas_w * 0.95) / src_w
    new_w = max(1, int(src_w * scale))
    new_h = max(1, int(src_h * scale))
    tinted = tinted.resize((new_w, new_h), Image.LANCZOS)

    paste_x = (canvas_w - new_w) // 2
    if vertically_center:
        # Override the requested top-y — centre the rendered figure
        # vertically in the canvas so it reads as a guardian behind
        # whatever overlays it (rose / wordmark).
        paste_y = (canvas_h - new_h) // 2
    else:
        paste_y = target_y_top
    # Caller is responsible for ensuring `img` is RGBA. main() does
    # this once after rendering the gradient.
    img.alpha_composite(tinted, (paste_x, paste_y))


def render_rose(draw: ImageDraw.ImageDraw, cx: float, cy: float, scale: float) -> float:
    """Render the 10-petal rose + cyan hexagon centred at (cx,cy).
    Returns the y-coordinate of the rose's bottom-most pixel so the
    caller can lay the wordmark beneath it."""
    # Large purple petals (length 30, width 6) — pathData
    #   M 54,54 L 51,38 L 54,24 L 57,38 Z
    # Local offsets from (54,54): (0,0) (-3,-16) (0,-30) (3,-16)
    big = ((0, 0), (-3, -16), (0, -30), (3, -16))
    for deg in (0, 72, 144, 216, 288):
        render_petal(draw, cx, cy, scale, deg, PURPLE, big)

    # Small lavender petals (length 18, width 4) — pathData
    #   M 54,54 L 52,44 L 54,36 L 56,44 Z
    # Local offsets from (54,54): (0,0) (-2,-10) (0,-18) (2,-10)
    small = ((0, 0), (-2, -10), (0, -18), (2, -10))
    for deg in (36, 108, 180, 252, 324):
        render_petal(draw, cx, cy, scale, deg, LAVENDER, small)

    # Cyan center hexagon — pathData
    #   M 54,49 L 58.33,51.5 L 58.33,56.5 L 54,59 L 49.67,56.5 L 49.67,51.5 Z
    # Local offsets from (54,54):
    hex_pts_local = (
        (0, -5),
        (4.33, -2.5),
        (4.33, 2.5),
        (0, 5),
        (-4.33, 2.5),
        (-4.33, -2.5),
    )
    hex_pts = [(cx + dx * scale, cy + dy * scale) for dx, dy in hex_pts_local]
    draw.polygon(hex_pts, fill=CYAN)

    # Bottom-most petal pixel sits ~30 source-units below the centre
    # (the largest petal length).
    return cy + 30 * scale


def render_wordmark(draw: ImageDraw.ImageDraw, canvas_w: int, rose_bottom_y: float) -> int:
    """Lay out MYTHARA + 1.0 + the seven-word backronym subtitle below
    the rose, all centred horizontally. Returns the y-coordinate of
    the bottom-most rendered text pixel so callers can position
    subsequent layers (e.g. the silhouette) without overlap."""
    if not FONT_BOLD.exists() or not FONT_REG.exists():
        sys.exit(f"missing JetBrains Mono fonts under {FONT_BOLD.parent}")

    # ── MYTHARA ──────────────────────────────────────────────────────
    # Bold, large, with explicit per-char letter-spacing for the wide
    # geometric look the watch face uses. Pillow doesn't expose
    # tracking, so we measure each glyph and lay them out by hand.
    mythara_font = ImageFont.truetype(str(FONT_BOLD), 96)
    tracking = 14  # extra px between glyphs
    text = "MYTHARA"
    glyph_widths = [mythara_font.getbbox(ch)[2] - mythara_font.getbbox(ch)[0] for ch in text]
    total_w = sum(glyph_widths) + tracking * (len(text) - 1)
    x = (canvas_w - total_w) // 2
    ascent, _ = mythara_font.getmetrics()
    mythara_y = rose_bottom_y + 70
    for i, ch in enumerate(text):
        # Bias x by -bbox[0] so each glyph's actual ink starts where
        # we placed the cursor, accounting for the glyph's left side
        # bearing.
        bbox = mythara_font.getbbox(ch)
        draw.text((x - bbox[0], mythara_y), ch, font=mythara_font, fill=LAVENDER)
        x += glyph_widths[i] + tracking

    # ── 1.0 ──────────────────────────────────────────────────────────
    version_font = ImageFont.truetype(str(FONT_REG), 40)
    version = "1.0"
    v_bbox = version_font.getbbox(version)
    v_w = v_bbox[2] - v_bbox[0]
    v_x = (canvas_w - v_w) // 2 - v_bbox[0]
    v_y = mythara_y + ascent + 24  # ascent ≈ baseline→top of MYTHARA
    draw.text((v_x, v_y), version, font=version_font, fill=CYAN)

    # ── Backronym tagline ───────────────────────────────────────────
    # Per-char rendering so we can highlight the seven M-Y-T-H-A-R-A
    # letters that anchor the acronym. Highlighted chars use the BOLD
    # JetBrains Mono variant in cyan (matches the "1.0" stamp and the
    # rose's hexagon nucleus); the surrounding lowercase + punctuation
    # render in muted lavender at the regular weight. Both variants
    # are the same point-size so the monospace baseline stays clean —
    # no mid-line shifts.
    subtitle_font = ImageFont.truetype(str(FONT_REG), 30)
    subtitle_bold = ImageFont.truetype(str(FONT_BOLD), 30)
    subtitle = "Mind-Yoked Tonal-Haptic Adaptive Resonant Assistant"
    # JetBrains Mono is monospaced, so any glyph's advance width
    # equals every glyph's advance width — measure once with "M".
    char_advance = subtitle_font.getbbox("M")[2] - subtitle_font.getbbox("M")[0]
    s_w = char_advance * len(subtitle)
    s_x = (canvas_w - s_w) // 2
    v_ascent, _ = version_font.getmetrics()
    s_y = v_y + v_ascent + 32
    cursor_x = s_x
    for i, ch in enumerate(subtitle):
        # An "acronym anchor" is a capital letter that sits at the
        # start of a word (after a space or hyphen, or at index 0).
        is_word_start = i == 0 or subtitle[i - 1] in (" ", "-")
        if is_word_start and ch.isupper():
            font, fill = subtitle_bold, CYAN
        else:
            font, fill = subtitle_font, SUBTITLE_COLOR
        draw.text((cursor_x, s_y), ch, font=font, fill=fill)
        cursor_x += char_advance

    # Subtitle baseline + descent ≈ wordmark block bottom.
    s_ascent, s_descent = subtitle_font.getmetrics()
    return int(s_y + s_ascent + s_descent)


def _wordmark_block_height() -> int:
    """Total pixel height of the MYTHARA + 1.0 + tagline block, useful
    for anchoring the wordmark from a known canvas-bottom y instead
    of from the rose-bottom. Mirrors render_wordmark's vertical
    layout maths exactly, without drawing anything.
    """
    mf = ImageFont.truetype(str(FONT_BOLD), 96)
    vf = ImageFont.truetype(str(FONT_REG), 40)
    sf = ImageFont.truetype(str(FONT_REG), 30)
    m_a, _m_d = mf.getmetrics()
    v_a, _v_d = vf.getmetrics()
    s_a, s_d = sf.getmetrics()
    # mythara_top → version_top: m_a + 24
    # version_top → subtitle_top: v_a + 32
    # subtitle_top → subtitle_bottom: s_a + s_d
    return m_a + 24 + v_a + 32 + s_a + s_d


def main() -> None:
    args = parse_args()
    w, h = args.width, args.height
    cx = w / 2

    # Two layouts:
    #   no character → centre-biased rose with the wordmark sitting
    #                  directly beneath it (the original "splash"
    #                  composition, lots of breathing room)
    #   character    → "amulet" composition: silhouette fills the
    #                  canvas as a guardian backdrop; the rose sits
    #                  at the visual centre over her chest like a
    #                  brooch, with the wordmark + tagline directly
    #                  below it. The cy offset compensates for the
    #                  wordmark block hanging beneath the rose so
    #                  the *combined* mark+text block is what reads
    #                  as centred, not the rose alone.
    has_silhouette = bool(args.character)
    if has_silhouette:
        scale = 6.5 * (w / 1280)
        # Pull the rose above true-centre by half the wordmark block
        # height so rose + wordmark together centre on canvas middle.
        cy = h / 2 - _wordmark_block_height() / 2
    else:
        # Original splash layout.
        scale = 8 * (w / 1280)
        cy = h / 2 - 200 * (h / 2856)

    # Z-order:
    #   1. gradient (RGB, base)
    #   2. node-graph mesh   (subtle backdrop)
    #   3. character silhouette (large guardian watermark)
    #   4. rose                  (above her headdress)
    #   5. wordmark + version + tagline (anchored to bottom)
    # Steps 2+ all alpha-composite, so promote to RGBA after the
    # gradient is laid down.
    img = Image.new("RGB", (w, h), BG_TOP)
    render_gradient(img)
    img = img.convert("RGBA")

    if not args.no_mesh:
        render_node_mesh(img, seed=args.mesh_seed)

    if has_silhouette:
        # Silhouette is a full-canvas backdrop, vertically centred
        # so the warrior occupies the middle band of the canvas. The
        # rose + wordmark then overlay her at canvas-middle (the cy
        # above already centres the rose+wordmark *block* on canvas-
        # middle), so the brand mark + text read as a brooch sitting
        # at her chest.
        margin = int(h * 0.04)
        silhouette_avail_h = h - 2 * margin
        silhouette_frac = silhouette_avail_h / h
        render_silhouette(
            img,
            Path(args.character).expanduser(),
            target_y_top=margin,           # ignored when vertically_center=True
            max_alpha=args.silhouette_alpha,
            target_height_frac=silhouette_frac,
            vertically_center=True,
        )

        # Rose + text rendered together, both anchored to the
        # canvas-centred rose position computed above. They move as
        # a single visual block, so wordmark always hangs directly
        # off the rose's bottom edge regardless of which layout
        # variant is in play.
        rose_bottom_y = cy + 30 * scale
        draw = ImageDraw.Draw(img)
        if not args.no_rose:
            render_rose(draw, cx, cy, scale)
        render_wordmark(draw, w, rose_bottom_y)
    else:
        # Original splash layout — wordmark hangs directly off the
        # rose's bottom edge, no silhouette.
        rose_bottom_y = cy + 30 * scale
        draw = ImageDraw.Draw(img)
        if not args.no_rose:
            render_rose(draw, cx, cy, scale)
        render_wordmark(draw, w, rose_bottom_y)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    # Flatten to RGB for the wallpaper applier — Android's
    # WallpaperManager doesn't need the alpha channel and a flat RGB
    # PNG is smaller / loads faster.
    img.convert("RGB").save(args.out, "PNG", optimize=True)
    print(f"wrote {args.out} ({w}x{h})")


if __name__ == "__main__":
    main()
