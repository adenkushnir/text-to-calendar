# Makes Datebook's Frutiger Aero backgrounds, their relit versions and effect masks with Google (or OpenAI), within a cap
import base64
import json
import os
import sys
from pathlib import Path
SPENDING_CAP = 15.00  # dollars, across every run
VERSIONS = 2  # versions of each scene, time and service
SERVICES = ("google",)  # add "openai" to also use GPT Image
OPENAI_MODEL = "gpt-image-2.5-flare"  # change to "gpt-image-2.5-sunburst" if Flare looks worse
OPENAI_SIZE = "1024x2224"  # tall phone shape; both sides are multiples of 16
OPENAI_QUALITY = "high"
OPENAI_TEXT_PRICE = 5.00 / 1_000_000  # dollars per prompt token
OPENAI_IMAGE_PRICE = 30.00 / 1_000_000  # dollars per image output token
GOOGLE_MODEL = "gemini-nano-banana-2.1"
GOOGLE_ASPECT = "9:16"
GOOGLE_SIZE = "2K"
GOOGLE_GUESS = 0.15  # dollars per image; Google doesn't report a price, so check AI Studio billing
OUT_FOLDER = Path(__file__).parent / "backgrounds" / "candidates"
LEDGER = OUT_FOLDER / "ledger.json"
STYLE = (
    "Photorealistic vertical phone wallpaper in the Frutiger Aero style of the late 2000s: bright, clean, optimistic, "
    "glossy, eco-futuristic, saturated sky blue, aqua and fresh leaf green, crisp detail, high dynamic range. "
    "Keep the middle of the picture calm and simple (open sky or water), because app cards will sit on top of it; "
    "put the interesting detail near the top and the bottom. No text, no logos, no watermark, no people, no animals, "
    "no fish, no soap bubbles, no user interface."
)
TIMES = {
    "morning": "Early morning: soft golden sunlight from a low sun on the left, a peach-coloured horizon, gentle long "
               "shadows, fresh dewy air.",
    "midday": "Bright midday: the sun high up, a deep blue sky, crisp white light.",
    "sunset": "Sunset: a pink, orange and violet sky, warm golden light on the clouds, the sun low on the right near "
              "the horizon, soft purple shadows.",
}
SCENES = {
    "lake": "A wide, perfectly calm lake fills the lower half and mirrors the sky like glass. On the far shore, small "
            "in the distance, a cluster of gleaming glass skyscrapers in blue and teal glass. A lush, bright green "
            "grassy bank fills the very bottom edge. Big puffy white cumulus clouds in the sky.",
    "aquarium": "A split-level view, half above and half below the water surface, with the waterline about 40 percent "
                "down from the top. Above: blue sky with puffy cumulus clouds and a tiny glass city on the far "
                "horizon. Below: crystal-clear turquoise water with sun rays slanting down and rippling light on a "
                "pale sandy bottom far below. The water is empty.",
    "ribbons": "An abstract 3D render: luminous, flowing ribbons of light in aqua, leaf green and white sweeping "
               "across a sky-blue gradient, a few glossy glass orbs, soft bokeh light dots and small sparkles. "
               "Smooth and airy.",
    "dew": "A macro photograph of one large, bright green leaf crossing the picture diagonally from the bottom left, "
           "covered in crystal-clear water droplets that refract the light. Behind it, a softly blurred sunlit garden "
           "and sky with large round bokeh, very shallow depth of field.",
    "bubbles": "An abstract 3D render of a bright, airy aqua and sky-blue space with soft beams of light from the "
               "top, gentle bokeh and a few glossy glass orbs in blue and green. Mostly open, glowing space.",
    "beach": "A tropical beach: clear turquoise shallow sea with gentle small waves, smooth white sand along the "
             "bottom, one palm tree leaning in from the right edge, a small green island on the horizon, puffy white "
             "clouds.",
}
CUTOUTS = {
    "goldfish": "A single realistic fancy goldfish with long flowing translucent fins, seen exactly from the side, "
                "facing right, whole body in frame, glossy orange and gold scales.",
    "clownfish": "A single realistic clownfish, seen exactly from the side, facing right, whole body in frame, bright "
                 "orange with white bands edged in black.",
    "bluetang": "A single realistic blue tang fish, seen exactly from the side, facing right, whole body in frame, "
                "royal blue with a black pattern and a yellow tail.",
    "yellowtang": "A single realistic yellow tang fish, seen exactly from the side, facing right, whole body in "
                  "frame, bright lemon yellow.",
}
MASTERS = {  # the midday image picked for each scene; every other light is redrawn from it
    "lake": "lake-midday-google-2.png",
    "aquarium": "aquarium-midday-google-1.png",
    "ribbons": "ribbons-midday-google-1.png",
    "dew": "dew-midday-google-1.png",
    "bubbles": "bubbles-midday-google-1.png",
    "beach": "beach-midday-google-1.png",
}
RELIGHTS = {
    "morning": "early morning: soft golden sunlight from a low sun on the left, a peach-coloured horizon, gentle long "
               "shadows, fresh dewy air",
    "golden": "late-afternoon golden hour: warm golden light from a lower sun on the right, a slightly warmer sky, longer "
              "shadows",
    "sunset": "sunset: a pink, orange and violet sky, the sun low on the right near the horizon, warm golden light on the "
              "clouds, soft purple shadows",
    "bluehour": "blue hour just after sunset: a deep blue and soft violet sky with a last pink glow at the horizon, calm "
                "and cool, any windows starting to glow",
    "night": "a clear night: a deep navy sky with a few stars and soft moonlight, any windows and lights glowing warmly "
             "and reflected, darker overall but still clear and beautiful",
}
RELIGHT_PROMPT = (
    "Edit this image. Keep the exact same composition, camera position, framing and every object unchanged: the same "
    "buildings, horizon, water, plants, leaves, ribbons, orbs and cloud shapes in the same places. Only change the "
    "lighting, the colours of the sky and the time of day to {light}. Photorealistic, the same glossy Frutiger Aero style."
)
MASKS = {  # the regions each scene's moving effects need, painted white on black
    "lake-water": ("lake", "the lake water, including the reflections in it"),
    "lake-plants": ("lake", "the grass"),
    "aquarium-water": ("aquarium", "everything below the water surface"),
    "beach-water": ("beach", "the sea water, including the waves"),
    "beach-plants": ("beach", "the palm tree's leaves"),
    "dew-drops": ("dew", "the water droplets on the leaf"),
    "ribbons-ribbons": ("ribbons", "the glowing ribbons of light"),
    "bubbles-orbs": ("bubbles", "the glass orbs and bubbles"),
}
MASK_PROMPT = (
    "Make a black-and-white mask of this exact image, at the same size and framing: paint {region} pure white and "
    "everything else pure black. Only pure black and pure white, nothing else, no other content."
)
ICONS = {  # app icon concepts: the main shape
    "check": "a glossy 3D white calendar page with a sky-blue top binding, and a shiny leaf-green gel orb with a white "
             "check mark overlapping its lower right corner",
    "bubble": "a small glossy 3D calendar page floating inside a large, clear soap bubble with a thin rainbow film and a "
              "bright window-shaped reflection",
    "tile": "a thick glass tile with rounded corners showing a calendar grid of small glossy aqua dots, one dot larger "
            "and bright leaf green",
    "orb": "a glossy sky-blue glass orb with a simple white calendar symbol inside and one small fresh green leaf "
           "growing from its top",
}
ICON_BACKGROUNDS = {  # app icon concepts: what's behind the shape
    "sky": "a bright sky-blue to aqua gradient with a soft white glow behind the shape",
    "lake": "a simplified, softly blurred lakeside scene: blue sky, white clouds, calm reflecting water, green grass",
    "white": "clean white glass with a gentle glossy sheen and a faint aqua tint at the bottom",
}
PICKED_ICON = "bubble-lake"  # the app icon picked in the workshop
ICON_PROMPT = (
    "An Android app icon in the glossy Frutiger Aero style of the late 2000s, a photorealistic 3D render: {shape}, "
    "on {background}. Square, full-bleed edge to edge, no rounded corners, no border, no frame; the main shape is "
    "centered and stays inside the middle 60 percent. Bright, clean, crisp, high contrast so it reads at small sizes. "
    "No text, no letters, no numbers."
)
CUTOUT_STYLE = "Isolated on a fully transparent background, nothing else in the picture, studio-quality photo detail."
CUTOUT_STYLE_WHITE = ("Isolated on a plain, flat, pure white background with no shadow, nothing else in the picture, "
                      "studio-quality photo detail.")  # Google has no transparency; a script removes the white

# Read how much has been spent and which files exist, kept between runs
def load_ledger():
    # Start an empty ledger on the first run
    if not LEDGER.exists():
        return {"spent": 0.0, "costs": {}, "last": {"openai": 0.30, "google": GOOGLE_GUESS}}
    return json.loads(LEDGER.read_text())

# Save the ledger after every image, so a crash never loses the running total
def save_ledger(ledger):
    LEDGER.write_text(json.dumps(ledger, indent=2))

# Make one image with OpenAI and return its bytes and what it cost
def make_with_openai(prompt, transparent):
    from openai import OpenAI
    client = OpenAI()
    result = client.images.generate(
        model=OPENAI_MODEL,
        prompt=prompt,
        size="1024x1024" if transparent else OPENAI_SIZE,
        quality=OPENAI_QUALITY,
        background="transparent" if transparent else "opaque",
        output_format="png",
        n=1,
    )
    usage = getattr(result, "usage", None)
    input_tokens = getattr(usage, "input_tokens", 0) or 0
    output_tokens = getattr(usage, "output_tokens", 0) or 0
    cost = input_tokens * OPENAI_TEXT_PRICE + output_tokens * OPENAI_IMAGE_PRICE or 0.30  # $0.30 if usage is missing
    return base64.b64decode(result.data[0].b64_json), cost

# Make one image with Google's Nano Banana at an aspect ratio (editing a source image if given); return bytes and a guess
def make_with_google(prompt, aspect, source=None):
    from google import genai
    from google.genai import types
    client = genai.Client()
    config = types.GenerateContentConfig(
        response_modalities=["IMAGE"],
        image_config=types.ImageConfig(aspect_ratio=aspect, image_size=GOOGLE_SIZE),
    )
    contents = [types.Part.from_bytes(data=source.read_bytes(), mime_type="image/png"), prompt] if source else prompt
    response = client.models.generate_content(model=GOOGLE_MODEL, contents=contents, config=config)

    # Find the picture among the parts of the reply
    for part in response.candidates[0].content.parts:
        # The picture is the part that carries data
        if part.inline_data and part.inline_data.data:
            return part.inline_data.data, GOOGLE_GUESS
    raise RuntimeError("Google replied without an image: " + (response.text or "no text"))

# Print the Google models this key can use that make images
def list_google_models():
    from google import genai
    client = genai.Client()

    # Only the image models
    for model in client.models.list():
        # Image models have "image" or "banana" in their names
        if "image" in model.name or "banana" in model.name:
            print(model.name)

# Make one image unless it already exists or the cap would be passed; return False once the cap is reached
def make_one(ledger, service, name, prompt, transparent=False, source=None):
    path = OUT_FOLDER / f"{name}.png"

    # Skip images made in an earlier run
    if path.exists():
        return True
    estimate = ledger["last"][service]

    # Stop before an image that could push spending past the cap
    if ledger["spent"] + estimate > SPENDING_CAP:
        print(f"Stopping: ${ledger['spent']:.2f} spent, and the next image (about ${estimate:.2f}) could pass the "
              f"${SPENDING_CAP:.2f} cap.")
        return False
    print(f"Making {name} with {service}...", flush=True)

    try:
        # Ask the right service
        if service == "openai":
            data, cost = make_with_openai(prompt, transparent)
        else:
            data, cost = make_with_google(prompt, "1:1" if transparent else GOOGLE_ASPECT, source)
    except Exception as error:
        print(f"  Failed: {error}")
        return True
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    ledger["spent"] += cost
    ledger["costs"][name] = round(cost, 4)
    ledger["last"][service] = max(cost, 0.01)
    save_ledger(ledger)
    print(f"  Saved {path.name}: about ${cost:.3f} (total ${ledger['spent']:.2f} of ${SPENDING_CAP:.2f})")
    return True

# The full prompt for one scene at one time of day
def scene_prompt(scene, time_name):
    return f"{SCENES[scene]} {TIMES[time_name]} {STYLE}"

# Make one midday lake image with each service in use, to check the key, the model and the cost
def run_test(ledger):
    prompt = scene_prompt("lake", "midday")

    # One image per service
    for service in SERVICES:
        make_one(ledger, service, f"lake-midday-{service}-1", prompt)

# Make every scene, time and version with each service in use (midday first), then the fish cutouts
def run_all(ledger):
    # Midday first, so the most-seen light is done if the cap is reached early
    for time_name in ("midday", "morning", "sunset"):
        # Every scene at this time of day
        for scene in SCENES:
            # Each version with each service
            for version in range(1, VERSIONS + 1):
                # Each service in use, one after the other
                for service in SERVICES:
                    # Stop everything once the cap is reached
                    if not make_one(ledger, service, f"{scene}-{time_name}-{service}-{version}",
                                    scene_prompt(scene, time_name)):
                        return

    service = "openai" if "openai" in SERVICES else "google"
    style = CUTOUT_STYLE if service == "openai" else CUTOUT_STYLE_WHITE

    # Fish cutouts: transparent from OpenAI, or on white from Google
    for cutout, description in CUTOUTS.items():
        # Each version
        for version in range(1, VERSIONS + 1):
            # Stop once the cap is reached
            if not make_one(ledger, service, f"cutouts/{cutout}-{version}", f"{description} {style}",
                            transparent=True):
                return

# Redraw each scene's master image in every other light, keeping the picture itself the same
def run_relight(ledger):
    # Each scene's master
    for scene, master in MASTERS.items():
        # Every other light
        for light, description in RELIGHTS.items():
            # Stop once the cap is reached
            if not make_one(ledger, "google", f"relit/{scene}-{light}", RELIGHT_PROMPT.format(light=description),
                            source=OUT_FOLDER / master):
                return

# Make the black-and-white masks that keep each moving effect in its own part of the picture
def run_masks(ledger):
    # Each mask comes from its scene's master
    for name, (scene, region) in MASKS.items():
        # Stop once the cap is reached
        if not make_one(ledger, "google", f"masks/{name}", MASK_PROMPT.format(region=region),
                        source=OUT_FOLDER / MASTERS[scene]):
            return

# Render every app icon concept on every background, square
def run_icons(ledger):
    # Each concept
    for concept, shape in ICONS.items():
        # On each background
        for background, behind in ICON_BACKGROUNDS.items():
            # Stop once the cap is reached
            if not make_one(ledger, "google", f"icons/{concept}-{background}",
                            ICON_PROMPT.format(shape=shape, background=behind), transparent=True):
                return

# Make the picked icon's background layer: the same picture with the bubble and calendar removed
def run_icon_layers(ledger):
    prompt = ("Edit this image: remove the soap bubble and the calendar completely and fill in the scene behind them "
              "naturally, so only the softly blurred lakeside remains (sky, clouds, calm water, grass). Keep everything "
              "else exactly the same, at the same size and framing.")
    make_one(ledger, "google", f"icons/{PICKED_ICON}-background", prompt, transparent=True,
             source=OUT_FOLDER / "icons" / f"{PICKED_ICON}.png")

# Check the keys are set, then run the test or everything
def main():
    needed = {"openai": "OPENAI_API_KEY", "google": "GEMINI_API_KEY"}
    missing = [needed[service] for service in SERVICES if not os.environ.get(needed[service])]

    # The key for each service in use must be in the environment
    if missing:
        print("Set these first (see the instructions): " + ", ".join(missing))
        sys.exit(1)
    OUT_FOLDER.mkdir(parents=True, exist_ok=True)
    ledger = load_ledger()
    mode = sys.argv[1] if len(sys.argv) > 1 else "test"

    # Pick the run
    if mode == "models":
        list_google_models()
        return
    elif mode == "all":
        run_all(ledger)
    elif mode == "icons":
        run_icons(ledger)
    elif mode == "iconlayers":
        run_icon_layers(ledger)
    elif mode == "relight":
        run_relight(ledger)
        run_masks(ledger)
    else:
        run_test(ledger)
    print(f"Done. Spent about ${ledger['spent']:.2f} of ${SPENDING_CAP:.2f}. Images are in {OUT_FOLDER}")

if __name__ == "__main__":
    main()
