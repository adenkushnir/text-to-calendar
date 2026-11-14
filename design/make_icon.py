# Splits the picked app icon (a calendar in a bubble on the lakeside) into Android's adaptive-icon layers, a lake
# background and a bubble foreground, and makes the 512 x 512 Play Store icon
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter
ICONS = Path(__file__).parent / "backgrounds" / "candidates" / "icons"
DRAWABLES = Path(__file__).parent.parent / "android" / "app" / "src" / "main" / "res" / "drawable-nodpi"
PLAY_STORE = Path(__file__).parent / "icon" / "play-store.png"
LAYER_SIZE = 432  # a 108dp adaptive-icon layer at the sharpest screen density (4 pixels a dp)
BUBBLE_SHARE = 0.56  # the bubble's width as a share of the whole layer (launchers show only the middle two thirds)
QUALITY = 90

# Find the bubble: where the icon differs from its bubble-less background, as a centre and radius in pixels
def find_bubble(icon, background):
    difference = ImageChops.difference(icon, background).convert("L").resize((512, 512))
    blob = difference.point(lambda value: 255 if value > 28 else 0).filter(ImageFilter.MinFilter(9))
    left, top, right, bottom = blob.getbbox()
    scale = icon.width / 512
    radius = max(right - left, bottom - top) / 2 * scale
    return (left + right) / 2 * scale, (top + bottom) / 2 * scale, radius * 1.03

# Cut the bubble out on a soft-edged circle and centre it, shrunk to its share, on a clear layer
def bubble_layer(icon, center_x, center_y, radius):
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).ellipse((center_x - radius, center_y - radius, center_x + radius, center_y + radius), fill=255)
    bubble = icon.convert("RGBA")
    bubble.putalpha(mask.filter(ImageFilter.GaussianBlur(radius * 0.02)))
    box = (int(center_x - radius), int(center_y - radius), int(center_x + radius), int(center_y + radius))
    bubble = bubble.crop(box)
    target = round(icon.width * BUBBLE_SHARE)
    bubble = bubble.resize((target, target), Image.LANCZOS)
    layer = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    layer.paste(bubble, ((icon.width - target) // 2, (icon.height - target) // 2), bubble)
    return layer

# Make the two layers and the Play Store icon
def main():
    icon = Image.open(ICONS / "bubble-lake.png").convert("RGB")
    background = Image.open(ICONS / "bubble-lake-background.png").convert("RGB")
    center_x, center_y, radius = find_bubble(icon, background)
    print(f"bubble at {center_x:.0f},{center_y:.0f}, radius {radius:.0f} of {icon.width}")
    foreground = bubble_layer(icon, center_x, center_y, radius)
    size = (LAYER_SIZE, LAYER_SIZE)
    background.resize(size, Image.LANCZOS).save(DRAWABLES / "ic_launcher_background.webp", quality=QUALITY)
    foreground.resize(size, Image.LANCZOS).save(DRAWABLES / "ic_launcher_bubble.webp", quality=QUALITY)
    whole = background.convert("RGBA")
    whole.alpha_composite(foreground)
    PLAY_STORE.parent.mkdir(exist_ok=True)
    whole.convert("RGB").resize((512, 512), Image.LANCZOS).save(PLAY_STORE, optimize=True)
    print("wrote the two icon layers and", PLAY_STORE.name)

main()
