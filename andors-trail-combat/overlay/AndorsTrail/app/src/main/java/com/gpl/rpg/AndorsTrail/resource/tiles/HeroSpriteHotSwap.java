package com.gpl.rpg.AndorsTrail.resource.tiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

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
	private static final int TARGET_SIZE = 32;
	private static final long POLL_INTERVAL_MS = 250L;
	private static boolean permissionPromptLaunched = false;
	private static long lastSignaturePoll = 0L;
	private static String lastActiveSignature = "";
	private static final HashMap<String, CachedBitmap> bitmapCache = new HashMap<String, CachedBitmap>();

	private static final class CachedBitmap {
		long modified;
		long length;
		Bitmap bitmap;
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
	}

	private static void writeReadme(File root) {
		File readme = new File(root, "README.txt");
		if (readme.exists()) return;
		String text =
				"Andor's Trail sprite hot-swap folder\n\n" +
				"Use transparent 32x32 PNG files. Sprites are loaded pixel-for-pixel and are never resized.\n" +
				"Files with any other dimensions are ignored so the game cannot distort your art.\n\n" +
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
		return file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
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

	private static synchronized Bitmap loadFile(File file) {
		if (file == null || !file.isFile()) return null;
		String path = file.getAbsolutePath();
		long modified = file.lastModified();
		long length = file.length();
		CachedBitmap cached = bitmapCache.get(path);
		if (cached != null && cached.modified == modified && cached.length == length) {
			return cached.bitmap;
		}
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inScaled = false;
		Bitmap decoded = BitmapFactory.decodeFile(path, options);
		Bitmap exact = decoded != null && decoded.getWidth() == TARGET_SIZE && decoded.getHeight() == TARGET_SIZE
				? decoded : null;
		CachedBitmap next = new CachedBitmap();
		next.modified = modified;
		next.length = length;
		next.bitmap = exact;
		bitmapCache.put(path, next);
		return exact;
	}

	public static Bitmap loadBaseSprite() {
		if (!isStorageAccessible()) return null;
		return loadFile(resolveBaseFile());
	}

	public static Bitmap loadItemSprite(String folder, ItemType type, Player player) {
		if (!isStorageAccessible() || type == null) return null;
		return loadFile(resolveItemFile(folder, type, player));
	}
}
