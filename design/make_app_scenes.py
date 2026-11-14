# Turns the picked scene pictures (each master and its five relights) into the app's 1080-wide WebP drawables
from pathlib import Path
from PIL import Image
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

# Shrink one picture to the app's width and save it as a WebP drawable
def save_scene(source, name):
    image = Image.open(source).convert("RGB")
    height = round(image.height * WIDTH / image.width)
    image.resize((WIDTH, height), Image.LANCZOS).save(DRAWABLES / f"{name}.webp", "WEBP", quality=QUALITY, method=6)

# Make every scene in every light, then report the total size
def main():
    DRAWABLES.mkdir(parents=True, exist_ok=True)

    # Each scene: its master as midday, then the relit versions
    for scene, master in MASTERS.items():
        save_scene(CANDIDATES / master, f"scene_{scene}_midday")

        # The five other lights
        for light in RELIGHTS:
            save_scene(CANDIDATES / "relit" / f"{scene}-{light}.png", f"scene_{scene}_{light}")
    made = sorted(DRAWABLES.glob("scene_*.webp"))
    total = sum(path.stat().st_size for path in made) / 1_000_000
    print(f"{len(made)} scenes, {total:.1f} MB, in {DRAWABLES}")

if __name__ == "__main__":
    main()
