# Turns the picked pictures into the app's drawables: each scene's master and five relights as 1080-wide WebP, each
# scene's packed effect mask, and the four fish with their white background cut away
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter
CANDIDATES = Path(__file__).parent / "backgrounds" / "candidates"
DRAWABLES = Path(__file__).parent.parent / "android" / "app" / "src" / "main" / "res" / "drawable-nodpi"
WIDTH = 1080
QUALITY = 82
MASTERS = {  # the midday picture picked for each scene (the relights were made from these)
    "lake": "lake-midday-google-2.png",
    "aquarium": "aquarium-midday-google-1.png",
    "ribbons": "ribbons-midday-google-1.png",
    "dew": "dew-midday-google-1.png",
    "bubbles": "bubbles-midday-google-1.png",
    "beach": "beach-midday-google-1.png",
}
RELIGHTS = ["night", "bluehour", "morning", "golden", "sunset"]
# Each scene's mask channels: red = water ripples, green = sway or flow, blue = glints or bobbing (None = empty)
PACKS = {
    "lake": ("lake-water-minus-plants", "lake-plants", "lake-water-minus-plants"),
    "aquarium": ("aquarium-water", None, "aquarium-water"),
    "ribbons": (None, "ribbons-ribbons", "ribbons-ribbons"),
    "dew": (None, "dew-drops", "dew-drops"),
    "bubbles": (None, None, "bubbles-orbs"),
    "beach": ("beach-water", "beach-plants", "beach-water"),
}
MASK_SIZE = (384, 688)
FISH = ["goldfish", "clownfish", "bluetang", "yellowtang"]  # version 1 of each was picked
FISH_WIDTH = 420
WHITE_LEVEL = 236  # a fish picture's pixel counts as background white when every channel is above this

# Shrink one picture to the app's width and save it as a WebP drawable
def save_scene(source, name):
    image = Image.open(source).convert("RGB")
    height = round(image.height * WIDTH / image.width)
    image.resize((WIDTH, height), Image.LANCZOS).save(DRAWABLES / f"{name}.webp", "WEBP", quality=QUALITY, method=6)

# Load one black-and-white mask, softened, at mask size (the lake's water has the grass taken out; None is empty)
def load_mask(name):
    # Pick how to make the channel
    if name is None:
        return Image.new("L", MASK_SIZE, 0)
    elif name == "lake-water-minus-plants":
        return ImageChops.subtract(load_mask("lake-water"), load_mask("lake-plants"))
    mask = Image.open(CANDIDATES / "masks" / f"{name}.png").convert("L").resize(MASK_SIZE, Image.LANCZOS)
    return mask.point(lambda value: 255 if value > 128 else 0).filter(ImageFilter.GaussianBlur(3))

# Cut the white background off a fish: flood-fill the white touching the edges, soften the edge, trim and shrink
def save_fish(name):
    image = Image.open(CANDIDATES / "cutouts" / f"{name}-1.png").convert("RGB").resize((1024, 1024), Image.LANCZOS)
    red, green, blue = image.split()
    darkest = ImageChops.darker(ImageChops.darker(red, green), blue)
    candidate = darkest.point(lambda value: 255 if value > WHITE_LEVEL else 0)
    seeds = [(x, y) for x in (0, 511, 1023) for y in (0, 511, 1023) if (x, y) != (511, 511)]

    # Fill the white background from every edge point that's still unfilled white
    for seed in seeds:
        # Skip points already filled or not white
        if candidate.getpixel(seed) == 255:
            ImageDraw.floodfill(candidate, seed, 128)
    background = candidate.point(lambda value: 255 if value == 128 else 0)
    alpha = ImageChops.invert(background).filter(ImageFilter.GaussianBlur(1.2))
    image.putalpha(alpha)
    image = image.crop(alpha.point(lambda value: 255 if value > 8 else 0).getbbox())
    height = round(image.height * FISH_WIDTH / image.width)
    image.resize((FISH_WIDTH, height), Image.LANCZOS).save(DRAWABLES / f"fish_{name}.png", optimize=True)

# Make every scene in every light, every mask and every fish, then report the total size
def main():
    DRAWABLES.mkdir(parents=True, exist_ok=True)

    # Each scene: its master as midday, the relit versions, and its packed mask
    for scene, master in MASTERS.items():
        save_scene(CANDIDATES / master, f"scene_{scene}_midday")

        # The five other lights
        for light in RELIGHTS:
            save_scene(CANDIDATES / "relit" / f"{scene}-{light}.png", f"scene_{scene}_{light}")
        Image.merge("RGB", [load_mask(name) for name in PACKS[scene]]).save(DRAWABLES / f"mask_{scene}.png",
                                                                          optimize=True)

    # Each fish
    for name in FISH:
        save_fish(name)
    made = sorted(DRAWABLES.glob("*.*"))
    total = sum(path.stat().st_size for path in made) / 1_000_000
    print(f"{len(made)} drawables, {total:.1f} MB, in {DRAWABLES}")

if __name__ == "__main__":
    main()
