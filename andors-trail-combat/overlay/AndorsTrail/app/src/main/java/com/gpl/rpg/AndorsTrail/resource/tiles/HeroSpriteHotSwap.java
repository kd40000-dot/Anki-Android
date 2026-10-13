package com.gpl.rpg.AndorsTrail.resource.tiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.model.item.ItemType;

/**
 * Runtime sprite override loader used by the sprite playground build.
 *
 * Shared-storage layout:
 *   /storage/emulated/0/AndorsTrail/Base/Base.png
 *   /storage/emulated/0/AndorsTrail/Headwear/<exact in-game item name>.png
 *   /storage/emulated/0/AndorsTrail/Armor/<exact in-game item name>.png
 *   /storage/emulated/0/AndorsTrail/Mainhand/<exact in-game item name>.png
 *   /storage/emulated/0/AndorsTrail/Offhand/<exact in-game item name>.png
 *   /storage/emulated/0/AndorsTrail/Gloves/<exact in-game item name>.png
 *   /storage/emulated/0/AndorsTrail/Footwear/<exact in-game item name>.png
 *
 * Files are polled by mtime/size and therefore update while the game is running.
 */
public final class HeroSpriteHotSwap {
	public static final String ROOT_NAME = "AndorsTrail";
	public static final String FOLDER_BASE = "Base";
	public static final String FOLDER_HEADWEAR = "Headwear";
	public static final String FOLDER_ARMOR = "Armor";
	public static final String FOLDER_MAINHAND = "Mainhand";
	public static final String FOLDER_OFFHAND = "Offhand";
	public static final String FOLDER_GLOVES = "Gloves";
	public static final String FOLDER_FOOTWEAR = "Footwear";

	private static final int REQUEST_STORAGE = 0x4171;
	private static final int LEGACY_SIZE = 32;
	public static final int TARGET_SIZE = 64;
	private static final int LARGE_SOURCE_SIZE = 1254;
	private static final long POLL_INTERVAL_MS = 250L;
	private static boolean permissionPromptLaunched = false;
	private static long lastSignaturePoll = 0L;
	private static String lastActiveSignature = "";
	private static final HashMap<String, CachedBitmap> bitmapCache = new HashMap<String, CachedBitmap>();

	private static final class CachedBitmap {
		long modified;
		long length;
		Bitmap bitmap;
		boolean keepCanvas;
		long fitModified;
		long fitLength;
	}

	private HeroSpriteHotSwap() { }

	public static File getRootDirectory() {
		return new File(Environment.getExternalStorageDirectory(), ROOT_NAME);
	}

	private static File getFolder(String folder) {
		return new File(getRootDirectory(), folder);
	}

	public static boolean isStorageAccessible() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return Environment.isExternalStorageManager();
		}
		File root = getRootDirectory();
		return root.exists() && root.canRead();
	}

	public static void ensureStorageAccess(Activity activity) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			if (Environment.isExternalStorageManager()) {
				ensureDirectories();
				return;
			}
			if (permissionPromptLaunched) return;
			permissionPromptLaunched = true;
			Toast.makeText(activity,
					"Allow file access for Andor's Trail sprite hot-swap folders.",
					Toast.LENGTH_LONG).show();
			try {
				Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
				intent.setData(Uri.parse("package:" + activity.getPackageName()));
				activity.startActivity(intent);
			} catch (ActivityNotFoundException e) {
				try {
					activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
				} catch (ActivityNotFoundException ignored) { }
			}
			return;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
				&& activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
						!= PackageManager.PERMISSION_GRANTED) {
			if (!permissionPromptLaunched) {
				permissionPromptLaunched = true;
				activity.requestPermissions(new String[] {
						Manifest.permission.READ_EXTERNAL_STORAGE,
						Manifest.permission.WRITE_EXTERNAL_STORAGE
				}, REQUEST_STORAGE);
			}
			return;
		}
		ensureDirectories();
	}

	public static void ensureDirectories() {
		File root = getRootDirectory();
		if (!root.exists()) root.mkdirs();
		String[] folders = new String[] {
				FOLDER_BASE, FOLDER_HEADWEAR, FOLDER_ARMOR, FOLDER_MAINHAND,
				FOLDER_OFFHAND, FOLDER_GLOVES, FOLDER_FOOTWEAR
		};
		for (String name : folders) {
			File folder = new File(root, name);
			if (!folder.exists()) folder.mkdirs();
		}
		writeReadme(root);
		writeLargeSpriteGuide(root);
		writeAlignmentGuide(root);
	}

	private static void writeReadme(File root) {
		File readme = new File(root, "README.txt");
		if (readme.exists()) return;
		String text =
				"Andor's Trail sprite hot-swap folder\n\n" +
				"Use transparent 64x64 PNG files for full detail. Legacy 32x32 sprites are upscaled exactly 2x with nearest-neighbor.\n" +
				"1254x1254 PNG images are also supported and automatically fitted to their equipment slot.\n" +
				"Other dimensions are ignored. Every 32x32/64x64 sprite layer must use the same pose and alignment.\n\n" +
				"Base/Base.png\n" +
				"Headwear/<exact in-game item name>.png\n" +
				"Armor/<exact in-game item name>.png\n" +
				"Mainhand/<exact in-game item name>.png\n" +
				"Offhand/<exact in-game item name>.png\n" +
				"Gloves/<exact in-game item name>.png\n" +
				"Footwear/<exact in-game item name>.png\n\n" +
				"Example: Armor/Kid's shirt.png\n" +
				"If an equipment file is missing, that slot draws no sprite.\n" +
				"Layer order: Footwear, Armor, Gloves, Headwear, Offhand, Mainhand.\n";
		try {
			FileOutputStream out = new FileOutputStream(readme);
			out.write(text.getBytes("UTF-8"));
			out.close();
		} catch (IOException ignored) { }
	}

	private static void writeLargeSpriteGuide(File root) {
		File guide = new File(root, "1254_SPRITES.txt");
		if (guide.exists()) return;
		String text =
				"Large artwork support (Andor's Trail)\n\n" +
				"Keep your original transparent 1254x1254 PNG files; do not resize them.\n" +
				"Place them in the existing Base, Armor, Headwear, Mainhand, Offhand, Gloves, or Footwear folders.\n" +
				"Base/Base.png is the character's base. Equipment filenames must match in-game names.\n\n" +
				"1254x1254 images are safely sampled and automatically fitted to their corresponding equipment region.\n" +
				"The game renders a 64x64 map tile; the full 1254x1254 file is never held in the sprite cache.\n" +
				"32x32 and 64x64 PNGs still work and retain their exact original alignment.\n\n" +
				"For a 1254x1254 layer already positioned on the complete character canvas,\n" +
				"create an empty companion file named exactly '<name>.png.aligned' to disable automatic slot fitting.\n" +
				"Example: Armor/Kid's shirt.png.aligned\n" +
				"All equipment is composited over the same fixed 64x64 render canvas.\n";
		try (FileOutputStream out = new FileOutputStream(guide)) {
			out.write(text.getBytes("UTF-8"));
		} catch (IOException ignored) { }
	}


	private static void writeAlignmentGuide(File root) {
		File guide = new File(root, "1254_EXACT_ALIGNMENT.txt");
		if (guide.exists()) return;
		String text =
				"1254px equipment alignment (64px in-game canvas)\n\n" +
				"The six original equipment sprites have individually measured built-in placements.\n" +
				"Both gloves and both boots are positioned separately over the player's hands and feet.\n" +
				"Base/Base.png is drawn across the complete canvas, unchanged.\n" +
				"Do not create any companion files for the supplied test set.\n\n" +
				"To override any 1254px artwork placement without recompiling, put a\n" +
				"JSON file next to it: Armor/Kid's shirt.png.fit.json\n\n" +
				"Single component:\n" +
				"{\"sourceCrop\":[314,453,960,972],\"destination\":[16,29,48,47]}\n\n" +
				"Multiple components (gloves/boots):\n" +
				"{\"parts\":[{\"sourceCrop\":[368,816,577,1047],\"destination\":[19,46,29,57]},"
				+ "{\"sourceCrop\":[678,816,886,1047],\"destination\":[37,46,47,57]}]}\n\n" +
				"sourceCrop uses coordinates of the original 1254x1254 PNG.\n" +
				"destination uses the final 64x64 game character canvas.\n" +
				"Changes to .fit.json are detected while the game is running.\n" +
				"An empty PNG.aligned companion still disables automatic placement\n" +
				"when no .fit.json exists. Unknown equipment uses per-slot fallback.\n";
		try (FileOutputStream out = new FileOutputStream(guide)) {
			out.write(text.getBytes("UTF-8"));
		} catch (IOException ignored) { }
	}

	private static String safeFileName(String value) {
		if (value == null) return "";
		return value.replace('/', '_').replace('\\', '_').replace('\0', '_');
	}

	private static File resolveNamedFile(String folder, String itemName) {
		if (itemName == null || itemName.length() == 0) return null;
		String safe = safeFileName(itemName);
		File dir = getFolder(folder);
		File png = new File(dir, safe + ".png");
		if (png.isFile()) return png;
		File bare = new File(dir, safe);
		if (bare.isFile()) return bare;

		// Be forgiving about filename case while still matching the exact in-game name.
		File[] files = dir.listFiles();
		if (files != null) {
			String wantedPng = (safe + ".png").toLowerCase();
			String wantedBare = safe.toLowerCase();
			for (File candidate : files) {
				if (!candidate.isFile()) continue;
				String name = candidate.getName().toLowerCase();
				if (name.equals(wantedPng) || name.equals(wantedBare)) return candidate;
			}
		}
		return null;
	}

	private static File resolveItemFile(String folder, ItemType type, Player player) {
		if (type == null) return null;
		File byDisplayName = resolveNamedFile(folder, type.getName(player));
		if (byDisplayName != null) return byDisplayName;
		// Item IDs are also accepted as a fallback, useful if the game is running in another language.
		return resolveNamedFile(folder, type.id);
	}

	private static File resolveBaseFile() {
		File file = resolveNamedFile(FOLDER_BASE, "Base");
		if (file != null) return file;
		// If Base.png is not present, accept the first PNG in Base for faster iteration.
		File[] files = getFolder(FOLDER_BASE).listFiles();
		if (files != null) {
			for (File candidate : files) {
				if (candidate.isFile() && candidate.getName().toLowerCase().endsWith(".png")) {
					return candidate;
				}
			}
		}
		return null;
	}

	private static String stamp(File file) {
		if (file == null || !file.isFile()) return "-";
		File aligned = new File(file.getAbsolutePath() + ".aligned");
		File fit = new File(file.getAbsolutePath() + ".fit.json");
		return file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length()
				+ (aligned.isFile() ? ":aligned" : ":autofit")
				+ (fit.isFile() ? (":fit:" + fit.lastModified() + ":" + fit.length()) : ":no-fit");
	}

	private static String computeActiveSignature(Player player) {
		if (!isStorageAccessible()) return "off";
		StringBuilder sb = new StringBuilder();
		sb.append("B=").append(stamp(resolveBaseFile()));
		ItemType head = player.inventory.getItemTypeInWearSlot(com.gpl.rpg.AndorsTrail.model.item.Inventory.WearSlot.head);
		ItemType body = player.inventory.getItemTypeInWearSlot(com.gpl.rpg.AndorsTrail.model.item.Inventory.WearSlot.body);
		ItemType weapon = player.inventory.getItemTypeInWearSlot(com.gpl.rpg.AndorsTrail.model.item.Inventory.WearSlot.weapon);
		ItemType offhand = player.inventory.getItemTypeInWearSlot(com.gpl.rpg.AndorsTrail.model.item.Inventory.WearSlot.shield);
		ItemType gloves = player.inventory.getItemTypeInWearSlot(com.gpl.rpg.AndorsTrail.model.item.Inventory.WearSlot.hand);
		ItemType feet = player.inventory.getItemTypeInWearSlot(com.gpl.rpg.AndorsTrail.model.item.Inventory.WearSlot.feet);
		sb.append("|H=").append(stamp(resolveItemFile(FOLDER_HEADWEAR, head, player)));
		sb.append("|A=").append(stamp(resolveItemFile(FOLDER_ARMOR, body, player)));
		sb.append("|M=").append(stamp(resolveItemFile(FOLDER_MAINHAND, weapon, player)));
		sb.append("|O=").append(stamp(resolveItemFile(FOLDER_OFFHAND, offhand, player)));
		sb.append("|G=").append(stamp(resolveItemFile(FOLDER_GLOVES, gloves, player)));
		sb.append("|F=").append(stamp(resolveItemFile(FOLDER_FOOTWEAR, feet, player)));
		return sb.toString();
	}

	public static synchronized String getActiveSignature(Player player) {
		long now = SystemClock.uptimeMillis();
		if (now - lastSignaturePoll >= POLL_INTERVAL_MS || lastActiveSignature.length() == 0) {
			lastSignaturePoll = now;
			lastActiveSignature = computeActiveSignature(player);
		}
		return lastActiveSignature;
	}


	/** Original-pixel crop and exact 64-pixel destination for a layer or component. */
	private static final class LargeSpritePlacement {
		final Rect sourceCrop;
		final Rect destination;
		LargeSpritePlacement(Rect sourceCrop, Rect destination) {
			this.sourceCrop = sourceCrop;
			this.destination = destination;
		}
	}

	private static LargeSpritePlacement placement(int x0, int y0, int x1, int y1,
			int dx0, int dy0, int dx1, int dy1) {
		return new LargeSpritePlacement(new Rect(x0, y0, x1, y1),
				new Rect(dx0, dy0, dx1, dy1));
	}

	/**
	 * Exact visual-component bounds measured from the original 1254x1254 art,
	 * mapped to the body landmarks on the supplied Base sprite's 64x64 canvas.
	 * Unlike the former alpha-bounds table, these crops discard near-invisible
	 * fringe pixels and transparent margins. Gloves and boots use two parts.
	 */
	private static LargeSpritePlacement[] knownPlacements(String folder, File file) {
		if (file == null) return null;
		String name = file.getName();
		if (FOLDER_HEADWEAR.equals(folder) && name.equalsIgnoreCase("Fine green hat.png")) {
			return new LargeSpritePlacement[] {
				placement(342, 423, 1035, 831, 15, 3, 50, 24)
			};
		}
		if (FOLDER_ARMOR.equals(folder) && name.equalsIgnoreCase("Kid's shirt.png")) {
			return new LargeSpritePlacement[] {
				placement(314, 453, 960, 972, 16, 29, 48, 47)
			};
		}
		if (FOLDER_MAINHAND.equals(folder) && name.equalsIgnoreCase("Sharp steel rapier.png")) {
			return new LargeSpritePlacement[] {
				placement(313, 268, 830, 996, 1, 13, 29, 48)
			};
		}
		if (FOLDER_OFFHAND.equals(folder) && name.equalsIgnoreCase("Kid's shield.png")) {
			return new LargeSpritePlacement[] {
				placement(487, 485, 891, 999, 43, 30, 59, 51)
			};
		}
		if (FOLDER_GLOVES.equals(folder) && name.equalsIgnoreCase("Gloves of better grip.png")) {
			return new LargeSpritePlacement[] {
				placement(371, 679, 543, 883, 16, 36, 26, 45),
				placement(716, 679, 885, 883, 42, 36, 52, 45)
			};
		}
		if (FOLDER_FOOTWEAR.equals(folder) && name.equalsIgnoreCase("Crude leather boots.png")) {
			return new LargeSpritePlacement[] {
				placement(368, 816, 577, 1047, 19, 46, 29, 57),
				placement(678, 816, 886, 1047, 37, 46, 47, 57)
			};
		}
		return null;
	}

	private static Rect rectFromJson(JSONArray array, int max) throws Exception {
		if (array == null || array.length() != 4) throw new IllegalArgumentException("Rect must have 4 numbers");
		int x0 = array.getInt(0), y0 = array.getInt(1);
		int x1 = array.getInt(2), y1 = array.getInt(3);
		if (x0 < 0 || y0 < 0 || x1 > max || y1 > max || x1 <= x0 || y1 <= y0) {
			throw new IllegalArgumentException("Out-of-bounds or empty rect");
		}
		return new Rect(x0, y0, x1, y1);
	}

	private static LargeSpritePlacement parsePlacement(JSONObject o) throws Exception {
		return new LargeSpritePlacement(
				rectFromJson(o.getJSONArray("sourceCrop"), LARGE_SOURCE_SIZE),
				rectFromJson(o.getJSONArray("destination"), TARGET_SIZE));
	}

	/** Sidecar settings take precedence over built-in per-item coordinates. */
	private static LargeSpritePlacement[] readPlacements(File artFile) {
		File sidecar = new File(artFile.getAbsolutePath() + ".fit.json");
		if (!sidecar.isFile() || sidecar.length() > 16384L) return null;
		StringBuilder data = new StringBuilder();
		try (FileInputStream in = new FileInputStream(sidecar)) {
			byte[] bytes = new byte[4096];
			int n;
			while ((n = in.read(bytes)) != -1) {
				if (data.length() + n > 16384) return null;
				data.append(new String(bytes, 0, n, "UTF-8"));
			}
			JSONObject document = new JSONObject(data.toString());
			JSONArray parts = document.optJSONArray("parts");
			if (parts != null) {
				if (parts.length() < 1 || parts.length() > 8) return null;
				LargeSpritePlacement[] placements = new LargeSpritePlacement[parts.length()];
				for (int i = 0; i < placements.length; i++)
					placements[i] = parsePlacement(parts.getJSONObject(i));
				return placements;
			}
			return new LargeSpritePlacement[] {parsePlacement(document)};
		} catch (Exception ignored) {
			return null;
		}
	}

	/** Source coordinates are in the 1254px PNG; decoded bitmap is sampled 8x. */
	private static Rect sampledRect(Rect original, Bitmap decoded) {
		float xScale = decoded.getWidth() / (float) LARGE_SOURCE_SIZE;
		float yScale = decoded.getHeight() / (float) LARGE_SOURCE_SIZE;
		int x0 = Math.max(0, Math.min(decoded.getWidth() - 1, Math.round(original.left * xScale)));
		int y0 = Math.max(0, Math.min(decoded.getHeight() - 1, Math.round(original.top * yScale)));
		int x1 = Math.max(x0 + 1, Math.min(decoded.getWidth(), Math.round(original.right * xScale)));
		int y1 = Math.max(y0 + 1, Math.min(decoded.getHeight(), Math.round(original.bottom * yScale)));
		return new Rect(x0, y0, x1, y1);
	}

	/**
	 * Large PNGs are input artwork; the map remains a fixed 64x64 character tile.
	 * Already-aligned/base files use a full-canvas fit. All other known original
	 * equipment artwork uses exact measured components, with optional overrides.
	 */
	private static Bitmap fitLargeSprite(Bitmap source, String folder, File file, boolean keepCanvas) {
		Bitmap target = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(target);
		Paint paint = new Paint();
		paint.setFilterBitmap(true);
		paint.setDither(true);
		LargeSpritePlacement[] components = FOLDER_BASE.equals(folder) ? null : readPlacements(file);
		if (components == null && !FOLDER_BASE.equals(folder) && !keepCanvas)
			components = knownPlacements(folder, file);
		if (components != null) {
			for (LargeSpritePlacement component : components)
				canvas.drawBitmap(source, sampledRect(component.sourceCrop, source),
						component.destination, paint);
		} else {
			Rect src = new Rect(0, 0, source.getWidth(), source.getHeight());
			Rect dst = new Rect(0, 0, TARGET_SIZE, TARGET_SIZE);
			if (!FOLDER_BASE.equals(folder) && !keepCanvas) {
				Rect content = visibleContentBounds(source);
				if (content != null) src = content;
				dst = slotBounds(folder);
			}
			canvas.drawBitmap(source, src, dst, paint);
		}
		return target;
	}

	/** Locate visible pixels of an isolated item, ignoring near-transparent fringe. */
	private static Rect visibleContentBounds(Bitmap source) {
		int width = source.getWidth(), height = source.getHeight();
		int[] pixels = new int[width * height];
		source.getPixels(pixels, 0, width, 0, 0, width, height);
		int minX = width, minY = height, maxX = -1, maxY = -1;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if ((pixels[y * width + x] >>> 24) < 12) continue;
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				if (y < minY) minY = y;
				if (y > maxY) maxY = y;
			}
		}
		return maxX < 0 ? null : new Rect(minX, minY, maxX + 1, maxY + 1);
	}

	/** Default placement of large, standalone equipment art on the original hero pose. */
	private static Rect slotBounds(String folder) {
		if (FOLDER_HEADWEAR.equals(folder)) return new Rect(15, 3, 50, 23);
		if (FOLDER_ARMOR.equals(folder)) return new Rect(12, 27, 52, 49);
		if (FOLDER_GLOVES.equals(folder)) return new Rect(4, 32, 60, 47);
		if (FOLDER_FOOTWEAR.equals(folder)) return new Rect(11, 43, 53, 63);
		if (FOLDER_OFFHAND.equals(folder)) return new Rect(38, 29, 63, 54);
		if (FOLDER_MAINHAND.equals(folder)) return new Rect(0, 10, 28, 47);
		return new Rect(0, 0, TARGET_SIZE, TARGET_SIZE);
	}

	private static synchronized Bitmap loadFile(File file, String folder) {
		if (file == null || !file.isFile()) return null;
		String path = file.getAbsolutePath();
		long modified = file.lastModified();
		long length = file.length();
		boolean keepCanvas = new File(path + ".aligned").isFile();
		File fitFile = new File(path + ".fit.json");
		long fitModified = fitFile.isFile() ? fitFile.lastModified() : -1L;
		long fitLength = fitFile.isFile() ? fitFile.length() : -1L;
		CachedBitmap cached = bitmapCache.get(path);
		if (cached != null && cached.modified == modified && cached.length == length
				&& cached.keepCanvas == keepCanvas
				&& cached.fitModified == fitModified && cached.fitLength == fitLength) {
			return cached.bitmap;
		}
		Bitmap exact = null;
		Bitmap decoded = null;
		try {
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			bounds.inScaled = false;
			BitmapFactory.decodeFile(path, bounds);
			int width = bounds.outWidth, height = bounds.outHeight;
			boolean validSquare = width == height
					&& (width == LEGACY_SIZE || width == TARGET_SIZE || width == LARGE_SOURCE_SIZE);
			if (validSquare) {
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inScaled = false;
				options.inPreferredConfig = Bitmap.Config.ARGB_8888;
				if (width == LARGE_SOURCE_SIZE) {
					// Decode around 157x157 rather than allocating a full 1254x1254 bitmap.
					options.inSampleSize = 8;
				}
				decoded = BitmapFactory.decodeFile(path, options);
				if (decoded != null) {
					if (width == TARGET_SIZE) exact = decoded;
					else if (width == LEGACY_SIZE)
						exact = Bitmap.createScaledBitmap(decoded, TARGET_SIZE, TARGET_SIZE, false);
					else
						exact = fitLargeSprite(decoded, folder, file, keepCanvas);
				}
			}
		} catch (OutOfMemoryError e) {
			// Corrupt/oversized user sprites must never crash the game.
			exact = null;
		} catch (RuntimeException e) {
			exact = null;
		} finally {
			if (decoded != null && decoded != exact && !decoded.isRecycled()) decoded.recycle();
		}
		CachedBitmap next = new CachedBitmap();
		next.modified = modified;
		next.length = length;
		next.bitmap = exact;
		next.keepCanvas = keepCanvas;
		next.fitModified = fitModified;
		next.fitLength = fitLength;
		bitmapCache.put(path, next);
		return exact;
	}

	public static Bitmap loadBaseSprite() {
		if (!isStorageAccessible()) return null;
		return loadFile(resolveBaseFile(), FOLDER_BASE);
	}

	public static Bitmap loadItemSprite(String folder, ItemType type, Player player) {
		if (!isStorageAccessible() || type == null) return null;
		return loadFile(resolveItemFile(folder, type, player), folder);
	}
}
