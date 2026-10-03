package com.gpl.rpg.AndorsTrail.resource.tiles;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.AsyncTask;
import android.widget.ImageView;
import android.widget.TextView;

import com.gpl.rpg.AndorsTrail.AndorsTrailApplication;
import com.gpl.rpg.AndorsTrail.AndorsTrailPreferences;
import com.gpl.rpg.AndorsTrail.R;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.ability.ActorConditionType;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.model.item.Inventory;
import com.gpl.rpg.AndorsTrail.model.item.ItemContainer;
import com.gpl.rpg.AndorsTrail.model.item.ItemContainer.ItemEntry;
import com.gpl.rpg.AndorsTrail.model.item.ItemType;
import com.gpl.rpg.AndorsTrail.model.map.LayeredTileMap;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.MonsterSpawnArea;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapTranslator;
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.ThemeHelper;

public final class TileManager {
	
	public static final int BEGIN_ID = 1;
	
	public static final int CHAR_HERO_0 = BEGIN_ID;
	public static final int CHAR_HERO_1 = CHAR_HERO_0+1;
	public static final int CHAR_HERO_2 = CHAR_HERO_1+1;
	public static final int CHAR_HERO_SHIP = CHAR_HERO_2+1;
	public static final int CHAR_HERO_SHEEP = CHAR_HERO_2+2;
	//Default hero
	public static final int CHAR_HERO = CHAR_HERO_0;
	//Max hero icon ID in this version.
	public static final int LAST_HERO = CHAR_HERO_2;
	
	public static final int iconID_selection_red = CHAR_HERO_SHEEP+1;
	public static final int iconID_selection_yellow = iconID_selection_red+1;
	public static final int iconID_attackselect = iconID_selection_red;
	public static final int iconID_moveselect = iconID_selection_yellow;
	public static final int iconID_groundbag = iconID_moveselect+1;
	public static final int iconID_boxopened = iconID_groundbag+1;
	public static final int iconID_boxclosed = iconID_boxopened+1;
	public static final int iconID_shop = iconID_groundbag;
	public static final int iconID_unassigned_quickslot = iconID_groundbag;
	public static final int iconID_selection_blue = iconID_boxclosed+1;
	public static final int iconID_selection_purple = iconID_selection_blue+1;
	public static final int iconID_selection_green = iconID_selection_purple+1;

	public static final int iconID_splatter_red_1a = iconID_selection_green+1;
	public static final int iconID_splatter_red_1b = iconID_splatter_red_1a+1;
	public static final int iconID_splatter_red_2a = iconID_splatter_red_1b+1;
	public static final int iconID_splatter_red_2b = iconID_splatter_red_2a+1;
	public static final int iconID_splatter_brown_1a = iconID_splatter_red_2b+1;
	public static final int iconID_splatter_brown_1b = iconID_splatter_brown_1a+1;
	public static final int iconID_splatter_brown_2a = iconID_splatter_brown_1b+1;
	public static final int iconID_splatter_brown_2b = iconID_splatter_brown_2a+1;
	public static final int iconID_splatter_white_1a = iconID_splatter_brown_2b+1;
	public static final int iconID_splatter_white_1b = iconID_splatter_white_1a+1;
	
	public static final int iconID_immunity_overlay = iconID_splatter_white_1b+1;
	
	public static final int tileID_placeholder_hero = iconID_immunity_overlay+1;
	public static final int tileID_placeholder_hat = tileID_placeholder_hero+1;
	public static final int tileID_placeholder_armor = tileID_placeholder_hat+1;
	public static final int tileID_placeholder_necklace = tileID_placeholder_armor+1;
	public static final int tileID_placeholder_weapon = tileID_placeholder_necklace+1;
	public static final int tileID_placeholder_shield = tileID_placeholder_weapon+1;
	public static final int tileID_placeholder_lring = tileID_placeholder_shield+1;
	public static final int tileID_placeholder_rring = tileID_placeholder_lring+1;
	public static final int tileID_placeholder_gloves = tileID_placeholder_rring+1;
	public static final int tileID_placeholder_boots = tileID_placeholder_gloves+1;
	


	public int tileSize;
	public float density;
	public float uiIconScale;

	public int viewTileSize;
	public float scale;


	public final TileCache tileCache = new TileCache();
	public TileCollection preloadedTiles;// = new TileCollection(116);
	public TileCollection adjacentMapTiles;
	private final HashSet<Integer> preloadedTileIDs = new HashSet<Integer>();

	// Equipment-driven player appearance. The base is the original warrior silhouette
	// with its baked-in sword and flask removed. Equipment is composed from cohesive
	// body-region layers, then recolored from the equipped item's actual in-game icon.
	private static final int HERO_EQUIPMENT_TILE_SIZE = 32;
	private static final int HERO_EQUIPMENT_COLUMNS = 8;

	private Bitmap heroEquipmentLayers;
	private Bitmap heroEquipmentMasks;
	private Bitmap heroEquipmentBase;
	private Bitmap heroTwoHandClearMask;
	private Bitmap playerAppearanceBitmap;
	private String playerAppearanceSignature;
	private final HashMap<String, Bitmap> directHeroSpriteCache = new HashMap<String, Bitmap>();
	private final HashMap<String, Bitmap> equipmentLayerCache = new HashMap<String, Bitmap>();
	private final HashMap<String, int[]> equipmentPaletteCache = new HashMap<String, int[]>();

	private Bitmap getHeroEquipmentLayers(Resources res) {
		if (heroEquipmentLayers == null) {
			heroEquipmentLayers = BitmapFactory.decodeResource(res, R.drawable.hero_equipment_layers);
		}
		return heroEquipmentLayers;
	}

	private Bitmap getHeroEquipmentMasks(Resources res) {
		if (heroEquipmentMasks == null) {
			heroEquipmentMasks = BitmapFactory.decodeResource(res, R.drawable.hero_equipment_masks);
		}
		return heroEquipmentMasks;
	}

	private Bitmap getHeroEquipmentBase(Resources res) {
		if (heroEquipmentBase == null) {
			heroEquipmentBase = BitmapFactory.decodeResource(res, R.drawable.hero_equipment_base);
		}
		return heroEquipmentBase;
	}

	private String directHeroToken(ItemType type) {
		if (type == null || type.id == null) return "";
		return type.id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
	}

	private Bitmap getDirectHeroSprite(Resources res, String resourceName) {
		if (directHeroSpriteCache.containsKey(resourceName)) {
			return directHeroSpriteCache.get(resourceName);
		}
		Bitmap bitmap = null;
		try {
			int resourceID = R.drawable.class.getField(resourceName).getInt(null);
			bitmap = BitmapFactory.decodeResource(res, resourceID);
		} catch (Exception ignored) {
			// Missing direct art is expected. Fall back to the generic equipment renderer.
		}
		directHeroSpriteCache.put(resourceName, bitmap);
		return bitmap;
	}

	private Bitmap getDirectEquipmentSprite(Resources res, String slot, ItemType type) {
		if (type == null) return null;
		return getDirectHeroSprite(res, "hero_direct_" + slot + "_" + directHeroToken(type));
	}

	private Bitmap getDirectEquipmentMask(Resources res, String slot, ItemType type) {
		if (type == null) return null;
		return getDirectHeroSprite(res, "hero_direct_mask_" + slot + "_" + directHeroToken(type));
	}

	private Bitmap getDirectBodyHandCombo(Resources res, ItemType body, ItemType hands) {
		if (body == null || hands == null) return null;
		return getDirectHeroSprite(res, "hero_direct_combo_body_" + directHeroToken(body)
				+ "_hand_" + directHeroToken(hands));
	}

	private void drawBitmapPossiblyMirrored(Canvas canvas, Bitmap bitmap, Paint paint, boolean mirror) {
		if (bitmap == null) return;
		if (mirror) {
			canvas.save();
			canvas.scale(-1f, 1f, HERO_EQUIPMENT_TILE_SIZE / 2f, HERO_EQUIPMENT_TILE_SIZE / 2f);
			canvas.drawBitmap(bitmap, 0, 0, paint);
			canvas.restore();
		} else {
			canvas.drawBitmap(bitmap, 0, 0, paint);
		}
	}

	private void clearDirectMask(Canvas canvas, Bitmap mask, boolean mirror) {
		if (!hasVisiblePixels(mask)) return;
		Paint clearPaint = new Paint();
		clearPaint.setFilterBitmap(false);
		clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
		drawBitmapPossiblyMirrored(canvas, mask, clearPaint, mirror);
		clearPaint.setXfermode(null);
	}

	private boolean drawDirectEquipmentLayer(Canvas canvas, Resources res, Paint paint,
			String slot, ItemType type, boolean mirror, boolean replaceCoveredBase) {
		Bitmap layer = getDirectEquipmentSprite(res, slot, type);
		if (!hasVisiblePixels(layer)) return false;
		if (replaceCoveredBase) {
			Bitmap mask = getDirectEquipmentMask(res, slot, type);
			clearDirectMask(canvas, hasVisiblePixels(mask) ? mask : layer, mirror);
		}
		drawBitmapPossiblyMirrored(canvas, layer, paint, mirror);
		return true;
	}

	private Bitmap getHeroTwoHandClearMask(Resources res) {
		if (heroTwoHandClearMask == null) {
			heroTwoHandClearMask = BitmapFactory.decodeResource(res, R.drawable.hero_twohand_clear_mask);
		}
		return heroTwoHandClearMask;
	}

	private boolean hasVisiblePixels(Bitmap bitmap) {
		if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return false;
		int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
		bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
		for (int pixel : pixels) {
			if ((pixel >>> 24) != 0) return true;
		}
		return false;
	}

	private Bitmap getFallbackHeroBitmap(Resources res, Player player) {
		Bitmap fallback = preloadedTiles == null ? null : preloadedTiles.getBitmap(player.iconID);
		if (hasVisiblePixels(fallback)) return fallback;
		fallback = BitmapFactory.decodeResource(res, R.drawable.char_hero);
		return hasVisiblePixels(fallback) ? fallback : null;
	}

	private String getPlayerAppearanceSignature(Player player) {
		StringBuilder sb = new StringBuilder();
		sb.append(player.iconID);
		for (Inventory.WearSlot slot : Inventory.WearSlot.values()) {
			ItemType type = player.inventory.getItemTypeInWearSlot(slot);
			if (type != null) {
				sb.append('|').append(slot.ordinal()).append(':').append(type.id);
			}
		}
		return sb.toString();
	}

	private String categoryID(ItemType type) {
		return type == null || type.category == null ? null : type.category.id;
	}

	private String itemText(ItemType type, Player player) {
		if (type == null) return "";
		StringBuilder sb = new StringBuilder();
		String name = type.getName(player);
		if (name != null) sb.append(name).append(' ');
		String description = type.getDescription();
		if (description != null) sb.append(description);
		return sb.toString().toLowerCase();
	}

	private int getBodyLayer(ItemType type, Player player) {
		if (type == null) return -1;
		String id = type.id;
		// Pass 0: Crossglen/Fallhaven item-specific body silhouettes.
		if ("kids_shirt".equals(id)) return 46;
		if ("shirt1".equals(id)) return 47;
		if ("shirt2".equals(id)) return 48;
		if ("shirt_torn".equals(id)) return 49;
		if ("shirt_weathered".equals(id)) return 50;
		if ("shirt_patched_cloth".equals(id)) return 51;
		if ("shirt_dmgresist".equals(id)) return 52;

		String category = categoryID(type);
		String text = itemText(type, player);
		if ("bdy_clth".equals(category)) {
			if (text.contains("robe") || text.contains("gown") || text.contains("tunic")) return 43;
			return 1;
		}
		if ("bdy_lthr".equals(category)) {
			if (text.contains("hard leather") || text.contains("hardened leather")
					|| text.contains("reinforced leather")) return 44;
			return 2;
		}
		if ("bdy_hide".equals(category)) return 3;
		if ("bdy_lt".equals(category)) return 4;
		if ("bdy_hv".equals(category)) return 5;
		if ("chmail".equals(category)) return 6;
		if ("spmail".equals(category)) return 7;
		if ("plmail".equals(category)) return 8;
		if (category != null && category.startsWith("bdy_")) return 4;
		return -1;
	}

	private int getHeadLayer(ItemType type, Player player) {
		if (type == null) return -1;
		if ("hat1".equals(type.id)) return 53;
		if ("hat2".equals(type.id)) return 54;

		String category = categoryID(type);
		String text = itemText(type, player);
		if ("hd_cloth".equals(category)) {
			if (text.contains("hood") || text.contains("cowl")) return 10;
			return 9;
		}
		if ("hd_lthr".equals(category)) return 11;
		if ("hd_mtl_li".equals(category)) return 12;
		if ("hd_mtl_hv".equals(category)) return 13;
		if (category != null && category.startsWith("hd_")) return 11;
		return -1;
	}

	private int getHandLayer(ItemType type) {
		if (type == null) return -1;
		String id = type.id;
		if ("kids_gloves".equals(id) || "gloves_crude_cloth".equals(id)) return 55;
		if ("gloves_fancy".equals(id)) return 56;
		if ("gloves_fumbling".equals(id)) return 57;
		if ("used_gloves".equals(id)) return 58;
		if ("gloves_barbrawler".equals(id)) return 59;
		if ("gloves_attack1".equals(id)) return 60;
		if ("gloves_grip".equals(id)) return 61;
		if ("gloves_critical".equals(id)) return 62;

		String category = categoryID(type);
		if ("hnd_cloth".equals(category)) return 14;
		if ("hnd_lthr".equals(category)) return 15;
		if ("hnd_mtl_li".equals(category)) return 16;
		if ("hnd_mtl_hv".equals(category)) return 17;
		return -1;
	}

	private int getFeetLayer(ItemType type) {
		if (type == null) return -1;
		String id = type.id;
		if ("boots_sewn".equals(id)) return 63;
		if ("boots_crude_leather".equals(id)) return 64;
		if ("boots1".equals(id)) return 65;
		if ("boots2".equals(id)) return 66;
		if ("boots3".equals(id)) return 67;

		String category = categoryID(type);
		if ("feet_clth".equals(category)) return 18;
		if ("feet_lthr".equals(category)) return 19;
		if ("feet_mtl_li".equals(category)) return 20;
		if ("feet_mtl_hv".equals(category)) return 21;
		return -1;
	}

	private int getWeaponLayer(ItemType type) {
		if (type == null) return -1;
		String id = type.id;
		// Pass 0: Crossglen/Fallhaven weapons use item-specific 32x32 art.
		if ("dagger0".equals(id)) return 73;
		if ("dagger1".equals(id)) return 74;
		if ("dagger_sharp_steel".equals(id)) return 75;
		if ("club1".equals(id)) return 76;
		if ("club3".equals(id)) return 77;
		if ("ironsword0".equals(id)) return 78;
		if ("rusted_iron_sword".equals(id)) return 79;
		if ("ironsword1".equals(id)) return 80;
		if ("ironsword2".equals(id)) return 81;
		if ("longsword_hard_iron".equals(id)) return 82;
		if ("shortsword1".equals(id)) return 83;
		if ("broadsword1".equals(id)) return 84;
		if ("axe2".equals(id)) return 85;
		if ("axe_black1".equals(id)) return 86;
		if ("hammer0".equals(id)) return 87;
		if ("qtrstaff".equals(id)) return 88;
		if ("clmr_rst".equals(id)) return 89;
		if ("clmr_irn1".equals(id)) return 90;
		if ("spear_rusty".equals(id)) return 91;
		if ("spear_iron".equals(id)) return 92;

		String category = categoryID(type);
		if ("dagger".equals(category)) return 22;
		if ("ssword".equals(category)) return 23;
		if ("rapier".equals(category)) return 24;
		if ("lsword".equals(category)) return 25;
		if ("2hsword".equals(category)) return 26;
		if ("bsword".equals(category)) return 27;
		if ("axe".equals(category)) return 28;
		if ("axe2h".equals(category)) return 29;
		if ("club".equals(category)) return 30;
		if ("staff".equals(category)) return 31;
		if ("mace".equals(category)) return 32;
		if ("scepter".equals(category)) return 33;
		if ("hammer".equals(category)) return 34;
		if ("hammer2h".equals(category)) return 35;
		if ("pole".equals(category)) return 36;
		return -1;
	}

	private int getShieldLayer(ItemType type) {
		if (type == null) return -1;
		String id = type.id;
		if ("broken_buckler".equals(id)) return 68;
		if ("shield_crude_wooden".equals(id)) return 69;
		if ("shield_cracked_wooden".equals(id)) return 70;
		if ("shield_wooden_buckler".equals(id)) return 71;
		if ("shield3".equals(id)) return 72;

		String category = categoryID(type);
		if ("buckler".equals(category)) return 37;
		if ("shld_wd_li".equals(category)) return 38;
		if ("shld_mtl_li".equals(category)) return 39;
		if ("shld_wd_hv".equals(category)) return 40;
		if ("shld_mtl_hv".equals(category)) return 41;
		if ("shld_twr".equals(category)) return 42;
		if (category != null && category.startsWith("shld_wd")) return 40;
		if (category != null && category.startsWith("shld_")) return 41;
		return -1;
	}

	private boolean isAppearanceTwoHanded(ItemType type) {
		if (type == null) return false;
		if (type.isTwohandWeapon()) return true;
		String id = type.id;
		return "qtrstaff".equals(id)
				|| "clmr_rst".equals(id)
				|| "clmr_irn1".equals(id)
				|| "spear_rusty".equals(id)
				|| "spear_iron".equals(id);
	}

	private int clampColor(int value) {
		return Math.max(0, Math.min(255, value));
	}

	private int shadeColor(int color, float factor) {
		return Color.rgb(
				clampColor((int) (Color.red(color) * factor)),
				clampColor((int) (Color.green(color) * factor)),
				clampColor((int) (Color.blue(color) * factor)));
	}

	private int colorDistanceSquared(int a, int b) {
		int dr = Color.red(a) - Color.red(b);
		int dg = Color.green(a) - Color.green(b);
		int db = Color.blue(a) - Color.blue(b);
		return dr * dr + dg * dg + db * db;
	}

	private int defaultPrimaryColor(ItemType type) {
		String category = categoryID(type);
		if (category == null) return Color.rgb(145, 145, 150);
		if (category.contains("lthr") || "bdy_hide".equals(category)) return Color.rgb(125, 78, 52);
		if (category.startsWith("shld_wd")) return Color.rgb(126, 84, 49);
		if ("club".equals(category) || "staff".equals(category)) return Color.rgb(126, 84, 49);
		if ("scepter".equals(category)) return Color.rgb(175, 135, 62);
		if (category.contains("mtl") || "chmail".equals(category) || "spmail".equals(category)
				|| "plmail".equals(category) || "bdy_hv".equals(category)
				|| type.isWeapon() || type.isShield()) return Color.rgb(145, 150, 158);
		return Color.rgb(145, 145, 150);
	}

	private int defaultSecondaryColor(ItemType type) {
		String category = categoryID(type);
		if (category != null && (category.contains("lthr") || "bdy_hide".equals(category)
				|| category.startsWith("shld_wd") || "club".equals(category) || "staff".equals(category))) {
			return Color.rgb(78, 50, 36);
		}
		if ("scepter".equals(category)) return Color.rgb(102, 73, 42);
		return Color.rgb(78, 66, 58);
	}

	private int[] getEquipmentPalette(ItemType type, Resources res) {
		if (type == null) return new int[] { Color.rgb(145, 145, 150), Color.rgb(78, 66, 58) };
		int[] cached = equipmentPaletteCache.get(type.id);
		if (cached != null) return cached;

		int primary = defaultPrimaryColor(type);
		int secondary = defaultSecondaryColor(type);
		try {
			Bitmap icon = tileCache.loadSingleTile(type.iconID, res);
			if (icon != null) {
				HashMap<Integer, Integer> counts = new HashMap<Integer, Integer>();
				for (int y = 0; y < icon.getHeight(); ++y) {
					for (int x = 0; x < icon.getWidth(); ++x) {
						int pixel = icon.getPixel(x, y);
						if (Color.alpha(pixel) < 96) continue;
						int r = Color.red(pixel);
						int g = Color.green(pixel);
						int b = Color.blue(pixel);
						int max = Math.max(r, Math.max(g, b));
						int min = Math.min(r, Math.min(g, b));
						int luma = (r * 3 + g * 5 + b * 2) / 10;
						if (luma < 38) continue; // ignore black outlines
						int qr = (r >> 4) << 4;
						int qg = (g >> 4) << 4;
						int qb = (b >> 4) << 4;
						int key = Color.rgb(qr, qg, qb);
						int weight = 1;
						if (max - min > 45) weight++; // preserve strongly colored cloth/leather
						Integer old = counts.get(key);
						counts.put(key, old == null ? weight : old + weight);
					}
				}

				int bestCount = -1;
				for (Integer color : counts.keySet()) {
					int count = counts.get(color);
					if (count > bestCount) {
						bestCount = count;
						primary = color;
					}
				}

				int secondCount = -1;
				for (Integer color : counts.keySet()) {
					if (colorDistanceSquared(primary, color) < 3600) continue;
					int count = counts.get(color);
					if (count > secondCount) {
						secondCount = count;
						secondary = color;
					}
				}
			}
		} catch (RuntimeException ignored) {
			// Keep category-based fallbacks if an icon is unavailable during a transient load.
		}
		int[] result = new int[] { primary, secondary };
		equipmentPaletteCache.put(type.id, result);
		return result;
	}

	private Bitmap getRenderedEquipmentLayer(Resources res, int layerIndex, ItemType type, boolean mirror) {
		if (layerIndex < 0 || type == null) return null;
		String key = layerIndex + ":" + type.id + ":" + (mirror ? "m" : "n");
		Bitmap cached = equipmentLayerCache.get(key);
		if (cached != null) return cached;

		Bitmap sourceSheet = getHeroEquipmentLayers(res);
		if (sourceSheet == null) return null;
		int sx = (layerIndex % HERO_EQUIPMENT_COLUMNS) * HERO_EQUIPMENT_TILE_SIZE;
		int sy = (layerIndex / HERO_EQUIPMENT_COLUMNS) * HERO_EQUIPMENT_TILE_SIZE;
		if (sx + HERO_EQUIPMENT_TILE_SIZE > sourceSheet.getWidth()
				|| sy + HERO_EQUIPMENT_TILE_SIZE > sourceSheet.getHeight()) return null;

		int[] palette = getEquipmentPalette(type, res);
		Bitmap result = Bitmap.createBitmap(HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE, Bitmap.Config.ARGB_8888);
		for (int y = 0; y < HERO_EQUIPMENT_TILE_SIZE; ++y) {
			for (int x = 0; x < HERO_EQUIPMENT_TILE_SIZE; ++x) {
				int marker = sourceSheet.getPixel(sx + x, sy + y);
				int alpha = Color.alpha(marker);
				if (alpha == 0) continue;
				int r = Color.red(marker);
				int g = Color.green(marker);
				int b = Color.blue(marker);
				boolean skinMarker = g > r + 60 && g > b + 60;
				boolean secondaryMarker = !skinMarker && r > 120 && b > 120 && g < 180
						&& r > g + 45 && b > g + 45;
				int baseColor = skinMarker ? Color.rgb(215, 131, 92)
						: (secondaryMarker ? palette[1] : palette[0]);
				float factor;
				if (skinMarker) {
					if (g < 90) factor = 0.62f;
					else if (g > 205) factor = 1.18f;
					else factor = 1.0f;
				} else if (secondaryMarker) {
					if (r < 180) factor = 0.58f;
					else if (g > 80) factor = 1.28f;
					else factor = 0.95f;
				} else {
					int luma = (r + g + b) / 3;
					if (luma < 50) factor = 0.24f;
					else if (luma < 110) factor = 0.56f;
					else if (luma < 190) factor = 0.92f;
					else factor = 1.28f;
				}
				int shaded = shadeColor(baseColor, factor);
				int dx = mirror ? HERO_EQUIPMENT_TILE_SIZE - 1 - x : x;
				result.setPixel(dx, y, Color.argb(alpha, Color.red(shaded), Color.green(shaded), Color.blue(shaded)));
			}
		}
		equipmentLayerCache.put(key, result);
		return result;
	}

	private void drawHeroEquipmentMask(Canvas canvas, Resources res, int layerIndex, boolean mirror) {
		if (layerIndex < 0) return;
		Bitmap maskSheet = getHeroEquipmentMasks(res);
		if (maskSheet == null) return;
		int sx = (layerIndex % HERO_EQUIPMENT_COLUMNS) * HERO_EQUIPMENT_TILE_SIZE;
		int sy = (layerIndex / HERO_EQUIPMENT_COLUMNS) * HERO_EQUIPMENT_TILE_SIZE;
		if (sx + HERO_EQUIPMENT_TILE_SIZE > maskSheet.getWidth()
				|| sy + HERO_EQUIPMENT_TILE_SIZE > maskSheet.getHeight()) return;
		Rect src = new Rect(sx, sy, sx + HERO_EQUIPMENT_TILE_SIZE, sy + HERO_EQUIPMENT_TILE_SIZE);
		Rect dst = new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE);
		Paint clearPaint = new Paint();
		clearPaint.setFilterBitmap(false);
		clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
		if (mirror) {
			canvas.save();
			canvas.scale(-1f, 1f, HERO_EQUIPMENT_TILE_SIZE / 2f, HERO_EQUIPMENT_TILE_SIZE / 2f);
			canvas.drawBitmap(maskSheet, src, dst, clearPaint);
			canvas.restore();
		} else {
			canvas.drawBitmap(maskSheet, src, dst, clearPaint);
		}
		clearPaint.setXfermode(null);
	}

	private void drawHeroEquipmentLayer(Canvas canvas, Resources res, Paint paint,
			int layerIndex, ItemType type, boolean mirror, boolean replaceCoveredBase) {
		if (layerIndex < 0 || type == null) return;
		if (replaceCoveredBase) drawHeroEquipmentMask(canvas, res, layerIndex, mirror);
		Bitmap layer = getRenderedEquipmentLayer(res, layerIndex, type, mirror);
		if (layer != null) canvas.drawBitmap(layer, 0, 0, paint);
	}

	private void clearTwoHandSideHands(Canvas canvas, Resources res) {
		Bitmap mask = getHeroTwoHandClearMask(res);
		if (mask == null) return;
		Paint clearPaint = new Paint();
		clearPaint.setFilterBitmap(false);
		clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
		canvas.drawBitmap(mask, 0, 0, clearPaint);
		clearPaint.setXfermode(null);
	}

	private Bitmap buildPlayerAppearance(Resources res, Player player) {
		Bitmap result = Bitmap.createBitmap(HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(result);
		Paint paint = new Paint();
		paint.setFilterBitmap(false);

		ItemType body = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.body);
		ItemType feet = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.feet);
		ItemType hands = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.hand);
		ItemType head = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.head);
		ItemType mainHand = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.weapon);
		ItemType offHand = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.shield);

		boolean directBodyHandled = false;
		boolean directHandsHandled = false;
		if (player.iconID <= LAST_HERO) {
			Bitmap combo = getDirectBodyHandCombo(res, body, hands);
			Bitmap base = hasVisiblePixels(combo) ? combo : getHeroEquipmentBase(res);
			if (hasVisiblePixels(combo)) {
				directBodyHandled = true;
				directHandsHandled = true;
			}
			if (hasVisiblePixels(base)) {
				canvas.drawBitmap(base, null,
						new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
			} else {
				Bitmap fallback = getFallbackHeroBitmap(res, player);
				if (fallback != null) {
					canvas.drawBitmap(fallback, null,
							new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
				}
			}
		} else {
			Bitmap selectedHero = preloadedTiles == null ? null : preloadedTiles.getBitmap(player.iconID);
			if (hasVisiblePixels(selectedHero)) {
				canvas.drawBitmap(selectedHero, null,
						new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
			} else {
				Bitmap fallback = getFallbackHeroBitmap(res, player);
				if (fallback != null) {
					canvas.drawBitmap(fallback, null,
							new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
				}
			}
		}

		// Direct 32x32 assets are preferred for rapid sprite iteration. Resource names are
		// derived from the real item ID, so adding or replacing approved artwork usually
		// requires only PNG changes, not another TileManager edit. Missing direct art
		// transparently falls back to the existing category/item renderer.
		if (!directBodyHandled) {
			directBodyHandled = drawDirectEquipmentLayer(canvas, res, paint, "body", body, false, true);
			if (!directBodyHandled) {
				drawHeroEquipmentLayer(canvas, res, paint, getBodyLayer(body, player), body, false, true);
			}
		}
		if (!drawDirectEquipmentLayer(canvas, res, paint, "feet", feet, false, true)) {
			drawHeroEquipmentLayer(canvas, res, paint, getFeetLayer(feet), feet, false, true);
		}
		if (!directHandsHandled) {
			directHandsHandled = drawDirectEquipmentLayer(canvas, res, paint, "hand", hands, false, true);
			if (!directHandsHandled) {
				drawHeroEquipmentLayer(canvas, res, paint, getHandLayer(hands), hands, false, true);
			}
		}
		if (!drawDirectEquipmentLayer(canvas, res, paint, "head", head, false, true)) {
			drawHeroEquipmentLayer(canvas, res, paint, getHeadLayer(head, player), head, false, true);
		}

		final boolean twoHandedMain = isAppearanceTwoHanded(mainHand);

		// A normal weapon slot is the character's right hand, which is viewer-left
		// on this front-facing sprite. Mirroring that same weapon art puts an
		// off-hand weapon in the character's left hand (viewer-right). Shields
		// are authored directly on viewer-right because they belong to the shield slot.
		if (!twoHandedMain) {
			if (offHand != null && offHand.isWeapon()) {
				int offWeaponLayer = getWeaponLayer(offHand);
				if (offWeaponLayer < 0) offWeaponLayer = 22;
				if (!drawDirectEquipmentLayer(canvas, res, paint, "weapon", offHand, true, false)) {
					drawHeroEquipmentLayer(canvas, res, paint, offWeaponLayer, offHand, true, false);
				}
			} else if (offHand != null) {
				int shieldLayer = getShieldLayer(offHand);
				if (shieldLayer < 0 && offHand.isShield()) shieldLayer = 39;
				if (!drawDirectEquipmentLayer(canvas, res, paint, "shield", offHand, false, false)) {
					drawHeroEquipmentLayer(canvas, res, paint, shieldLayer, offHand, false, false);
				}
			}
		}

		if (mainHand != null) {
			int mainWeaponLayer = getWeaponLayer(mainHand);
			if (mainWeaponLayer < 0 && mainHand.isWeapon()) mainWeaponLayer = 25;
			if (twoHandedMain) {
				// Two-handed categories use a centered diagonal pose. Remove the
				// relaxed side-hands first so the sprite never appears to have
				// extra arms, then draw both gripping hands on the weapon.
				clearTwoHandSideHands(canvas, res);
				if (!drawDirectEquipmentLayer(canvas, res, paint, "weapon", mainHand, false, false)) {
					drawHeroEquipmentLayer(canvas, res, paint, mainWeaponLayer, mainHand, false, false);
				}
				if (hands != null) {
					// Repaint the two grip points using the equipped glove palette.
					drawHeroEquipmentLayer(canvas, res, paint, 45, hands, false, false);
				}
			} else {
				if (!drawDirectEquipmentLayer(canvas, res, paint, "weapon", mainHand, false, false)) {
					drawHeroEquipmentLayer(canvas, res, paint, mainWeaponLayer, mainHand, false, false);
				}
			}
		}

		// A corrupt or unsupported equipment resource must never make the player invisible.
		if (!hasVisiblePixels(result)) {
			Bitmap fallback = getFallbackHeroBitmap(res, player);
			if (fallback != null) {
				canvas.drawBitmap(fallback, null,
						new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
			}
		}
		return result;
	}

	public Bitmap getPlayerAppearanceBitmap(Resources res, Player player) {
		String signature = getPlayerAppearanceSignature(player);
		if (playerAppearanceBitmap == null || !signature.equals(playerAppearanceSignature)) {
			playerAppearanceBitmap = buildPlayerAppearance(res, player);
			playerAppearanceSignature = signature;
		}
		return playerAppearanceBitmap;
	}

	public Drawable getPlayerAppearanceDrawable(Resources res, Player player, boolean large) {
		Bitmap bitmap = getPlayerAppearanceBitmap(res, player);
		if (large) {
			bitmap = Bitmap.createScaledBitmap(bitmap, HERO_EQUIPMENT_TILE_SIZE * 2, HERO_EQUIPMENT_TILE_SIZE * 2, false);
		}
		return new BitmapDrawable(res, bitmap);
	}

	public void setPlayerAppearanceImageView(Resources res, ImageView imageView, Player player, boolean large) {
		setImageViewTile(imageView, getPlayerAppearanceDrawable(res, player, large));
	}

	public void refreshPlayerAppearance(Resources res, WorldContext world) {
		playerAppearanceSignature = null;
		playerAppearanceBitmap = null;
		if (world != null && world.model != null && world.model.currentMaps != null
				&& world.model.currentMaps.tiles != null) {
			world.model.currentMaps.tiles.setBitmap(
					tileID_placeholder_hero,
					getPlayerAppearanceBitmap(res, world.model.player));
		}
	}

	public TileCollection loadTilesFor(Collection<Integer> tileIDs, Resources r) {
		return tileCache.loadTilesFor(tileIDs, r);
	}

	public TileCollection loadTilesFor(ItemContainer container, Resources r) {
		return tileCache.loadTilesFor(getTileIDsFor(container), r);
	}

	public HashSet<Integer> getTileIDsFor(ItemContainer container) {
		HashSet<Integer> iconIDs = new HashSet<Integer>();
		for(ItemEntry i : container.items) {
			iconIDs.add(i.itemType.iconID);
		}
		return iconIDs;
	}

	public TileCollection loadTilesFor(Inventory inventory, Resources r) {
		HashSet<Integer> iconIDs = getTileIDsFor(inventory);
		for (Inventory.WearSlot slot : Inventory.WearSlot.values()) {
			ItemType t = inventory.getItemTypeInWearSlot(slot);
			if (t != null) iconIDs.add(t.iconID);
		}
		return tileCache.loadTilesFor(iconIDs, r);
	}

	public TileCollection loadTilesFor(PredefinedMap map, LayeredTileMap tileMap, WorldContext world, Resources r) {
		HashSet<Integer> iconIDs = getTileIDsFor(map, tileMap, world);
		TileCollection result = tileCache.loadTilesFor(iconIDs, r);
		for(int i : preloadedTileIDs) {
			result.setBitmap(i, preloadedTiles.getBitmap(i));
		}
		//TODO patch placeholders on the fly here.
		updatePlaceholdersTiles(result, world, r);
		return result;
	}

	private void updatePlaceholdersTiles(TileCollection result, WorldContext world, Resources res) {
		result.setBitmap(tileID_placeholder_hero, getPlayerAppearanceBitmap(res, world.model.player));
	}

	public HashSet<Integer> getTileIDsFor(PredefinedMap map, LayeredTileMap tileMap, WorldContext world) {
		HashSet<Integer> iconIDs = new HashSet<Integer>();
		for (MonsterSpawnArea a : map.spawnAreas) {
			for (String monsterTypeID : a.monsterTypeIDs) {
				iconIDs.add(world.monsterTypes.getMonsterType(monsterTypeID).iconID);
			}
			// Add icons for monsters that are already spawned, but that do not belong to the group of
			// monsters that usually spawn here. This could happen if we change the contents of spawn-
			// areas in a later release,
			for (Monster m : a.monsters) {
				iconIDs.add(m.iconID);
			}
		}
		iconIDs.addAll(tileMap.usedTileIDs);
		return iconIDs;
	}

	public void setDensity(Resources r) {
		density = r.getDisplayMetrics().density;
		uiIconScale = 100 * density;
//		tileSize = (int) (32 * density);
		if (density < 1) tileSize = (int) (32 * density);
		else tileSize = 32;
	}

	public void updatePreferences(AndorsTrailPreferences prefs) {
		float densityScaler = 1;
		if (density > 1) densityScaler = density;
		scale = prefs.scalingFactor * densityScaler;
		viewTileSize = (int) (tileSize * prefs.scalingFactor * densityScaler);
	}



	public void setImageViewTile(Resources res, TextView textView, Monster monster, TileCollection tiles) { setImageViewTileForMonster(res, textView, monster.iconID, tiles); }
	public void setImageViewTile(Resources res, TextView textView, Player player) { setImageViewTile(res, textView, getPlayerAppearanceBitmap(res, player)); }
	public void setImageViewTileForMonster(Resources res, TextView textView, int iconID, TileCollection tiles) { setImageViewTile(res, textView, tiles.getBitmap(iconID)); }
	public void setImageViewTileForPlayer(Resources res, TextView textView, int iconID) { setImageViewTile(res, textView, preloadedTiles.getBitmap(iconID)); }
	public void setImageViewTile(Resources res, TextView textView, ActorConditionType conditionType) { setImageViewTile(res, textView, preloadedTiles.getBitmap(conditionType.iconID)); }
	public void setImageViewTile(Resources res, TextView textView, ActorConditionType conditionType, boolean immunityOverlay) { setImageViewTile(res, textView, preloadedTiles.getBitmap(conditionType.iconID), immunityOverlay); }
	public void setImageViewTileForUIIcon(Resources res, TextView textView, int iconID) { setImageViewTile(res, textView, preloadedTiles.getBitmap(iconID)); }
	private void setImageViewTile(Resources res, TextView textView, Bitmap b) { 
		if (density > 1) {
			setImageViewTile(textView, new BitmapDrawable(res, Bitmap.createScaledBitmap(b, (int)(tileSize*density), (int)(tileSize*density), true)));
		} else {
			setImageViewTile(textView, new BitmapDrawable(res, b)); 
		}
	}
	public void setImageViewTile(Resources res, TextView textView, Bitmap b, boolean immunityOverlay) {
		if (!immunityOverlay) setImageViewTile(res, textView, b);
		else {
			Drawable[] layers = new Drawable[2];
			if (density > 1) {
				layers[0] = new BitmapDrawable(res, Bitmap.createScaledBitmap(b, (int)(tileSize*density), (int)(tileSize*density), true));
				layers[1] = new BitmapDrawable(res, preloadedTiles.getBitmap(iconID_immunity_overlay));
			} else {
				layers[0] = new BitmapDrawable(res, b);
				layers[1] = new BitmapDrawable(res, preloadedTiles.getBitmap(iconID_immunity_overlay));
			}
			LayerDrawable layered = new LayerDrawable(layers);
			setImageViewTile(textView, layered);
		}
	}
	private void setImageViewTile(TextView textView, Drawable d) {
		/*if (density > 1) {
			ScaleDrawable sd = new ScaleDrawable(d, 0, uiIconScale, uiIconScale);
			sd.setLevel(8000);
			d.setBounds(0, 0, (int)(tileSize * density), (int)(tileSize * density));
			textView.setCompoundDrawables(sd, null, null, null);
		}
		else */textView.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
	}
	
	public void setImageViewTileForSingleItemType(Resources res, TextView textView, ItemType itemType) {
		final Bitmap icon = tileCache.loadSingleTile(itemType.iconID, res);
		setImageViewTile(res, textView, itemType, icon);
	}
	public void setImageViewTile(Resources res, TextView textView, ItemType itemType, TileCollection itemTileCollection) {
		final Bitmap icon = itemTileCollection.getBitmap(itemType.iconID);
		setImageViewTile(res, textView, itemType, icon);
	}
	private void setImageViewTile(Resources res, TextView textView, ItemType itemType, Bitmap icon) {
		final int overlayIconID = itemType.getOverlayTileID();
		if (overlayIconID != -1) {
			
			if (density > 1) {
			
			setImageViewTile(textView,
				new LayerDrawable(new Drawable[] {
					new BitmapDrawable(res, Bitmap.createScaledBitmap(preloadedTiles.getBitmap(overlayIconID), (int)(tileSize*density), (int)(tileSize*density), true))
					,new BitmapDrawable(res, Bitmap.createScaledBitmap(icon, (int)(tileSize*density), (int)(tileSize*density), true))
				})
			);
			} else {
				setImageViewTile(textView,
						new LayerDrawable(new Drawable[] {
							new BitmapDrawable(res, preloadedTiles.getBitmap(overlayIconID))
							,new BitmapDrawable(res, icon)
						})
					);	
			}
		} else {
			setImageViewTile(res, textView, icon);
		}
	}

	public void setImageViewTile(Resources res, ImageView imageView, Monster monster, TileCollection tiles) { setImageViewTileForMonster(res, imageView, monster.iconID, tiles); }
	public void setImageViewTile(Resources res, ImageView imageView, Player player) { setImageViewTile(res, imageView, getPlayerAppearanceBitmap(res, player)); }
	public void setImageViewTileForMonster(Resources res, ImageView imageView, int iconID, TileCollection tiles) {  setImageViewTile(res, imageView, tiles.getBitmap(iconID)); }
	public void setImageViewTileForPlayer(Resources res, ImageView imageView, int iconID) {  setImageViewTile(res, imageView, preloadedTiles.getBitmap(iconID)); }
//	public void setImageViewTile(Resources res, ImageView imageView, ActorConditionType conditionType) {  setImageViewTile(res, imageView, preloadedTiles.getBitmap(conditionType.iconID)); }
	public void setImageViewTile(Context ctx, ImageView imageView, ActorConditionType conditionType, boolean immunityOverlay) {  setImageViewTile(ctx, imageView, preloadedTiles.getBitmap(conditionType.iconID), immunityOverlay); }
	public void setImageViewTile(Context ctx, ImageView imageView, ActorConditionType conditionType, boolean immunityOverlay, String exponent, String index) {  setImageViewTile(ctx, imageView, preloadedTiles.getBitmap(conditionType.iconID), immunityOverlay, exponent, index); }
	public void setImageViewTileForUIIcon(Resources res, ImageView imageView, int iconID) { setImageViewTile(res, imageView, preloadedTiles.getBitmap(iconID)); }
	public void setImageViewTile(Resources res, ImageView imageView, Bitmap b) {
		if (density > 1) {
			setImageViewTile(imageView, new BitmapDrawable(res, Bitmap.createScaledBitmap(b, (int)(tileSize*density), (int)(tileSize*density), true)));
		} else {
			setImageViewTile(imageView, new BitmapDrawable(res, b)); 
		}
	}
	public void setImageViewTile(Context ctx, ImageView imageView, Bitmap b, boolean immunityOverlay) {
		setImageViewTile(ctx, imageView, b, immunityOverlay, null, null);
	}
	public void setImageViewTile(Context ctx, ImageView imageView, Bitmap b, boolean immunityOverlay, String exponent, String index) {
		if (!immunityOverlay && exponent == null && index == null) setImageViewTile(ctx.getResources(), imageView, b);
		else {
			Drawable[] layers = new Drawable[1+
			                                 (immunityOverlay ? 1 : 0)+
			                                 (exponent != null ? 1 : 0)+
			                                 (index != null ? 1 : 0)];
			int tileWidth;
			if (density > 1) {
				tileWidth = (int)(tileSize*density);
				layers[0] = new BitmapDrawable(ctx.getResources(), Bitmap.createScaledBitmap(b, tileWidth, tileWidth, true));
			} else {
				tileWidth = tileSize;
				layers[0] = new BitmapDrawable(ctx.getResources(), b);
			}
			int nextIndex = 1;
			if (immunityOverlay) {
				layers[nextIndex] = new BitmapDrawable(ctx.getResources(), preloadedTiles.getBitmap(iconID_immunity_overlay));
				nextIndex++;
			}
			if (exponent != null) {
				layers[nextIndex] = new TextDrawable(ctx, tileWidth, tileWidth, exponent, TextDrawable.Align.TOP_RIGHT);
				nextIndex++;
			}
			if (index != null) {
				layers[nextIndex] = new TextDrawable(ctx, tileWidth, tileWidth, index, TextDrawable.Align.BOTTOM_RIGHT);
				nextIndex++;
			}
			LayerDrawable layered = new LayerDrawable(layers);
			setImageViewTile(imageView, layered);
		}
	}
	
	public void setImageViewTile(ImageView imageView, Drawable d) {
		imageView.setImageDrawable(d);
	}
	
	public void setImageViewTile(Resources res, ImageView imageView, ItemType itemType, TileCollection itemTileCollection) {
		final Bitmap icon = itemTileCollection.getBitmap(itemType.iconID);
		setImageViewTile(res, imageView, itemType, icon);
	}
	public void setImageViewTileWithOverlay(Resources res, ImageView imageView, int overlayIconID, Bitmap icon, boolean overlayAbove) {
		if (overlayIconID != -1) {
			Drawable overlayDrawable, iconDrawable;
			if (density > 1) {
				overlayDrawable = new BitmapDrawable(res, Bitmap.createScaledBitmap(preloadedTiles.getBitmap(overlayIconID), (int)(tileSize*density), (int)(tileSize*density), true));
				iconDrawable = new BitmapDrawable(res, Bitmap.createScaledBitmap(icon, (int)(tileSize*density), (int)(tileSize*density), true));
			} else {
				overlayDrawable = new BitmapDrawable(res, preloadedTiles.getBitmap(overlayIconID));
				iconDrawable = new BitmapDrawable(res, icon);
			}
			
			if (overlayAbove) {
				LayerDrawable layered = new LayerDrawable(new Drawable[] {
						iconDrawable
						,overlayDrawable
				});
				setImageViewTile(imageView, layered);
			} else {
				LayerDrawable layered = new LayerDrawable(new Drawable[] {
						overlayDrawable
						,iconDrawable
				});
				setImageViewTile(imageView, layered);
			}
		} else {
			setImageViewTile(res, imageView, icon);
		}
	}
	private void setImageViewTile(Resources res, ImageView imageView, ItemType itemType, Bitmap icon) {
		final int overlayIconID = itemType.getOverlayTileID();
		setImageViewTileWithOverlay(res, imageView, overlayIconID, icon, false);
	}
	


	public Drawable getDrawableForItem(Resources res, int iconID, TileCollection itemTileCollection) {
		final Bitmap icon = itemTileCollection.getBitmap(iconID);
		if (density > 1) {
			return new BitmapDrawable(res, Bitmap.createScaledBitmap(icon, (int)(tileSize*density), (int)(tileSize*density), true));
		} else {
			return new BitmapDrawable(res, icon);
			}
	}

	public void loadPreloadedTiles(Resources r) {
		int maxTileID = tileCache.getMaxTileID();
//		if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
//			if (maxTileID > preloadedTiles.maxTileID) {
//				L.log("ERROR: TileManager.preloadedTiles needs to be initialized with at least " + maxTileID + " slots. Application will crash now.");
//				throw new IndexOutOfBoundsException("ERROR: TileManager.preloadedTiles needs to be initialized with at least " + maxTileID + " slots. Application will crash now.");
//			}
//		}
		preloadedTiles = new TileCollection(maxTileID);
		for(int i = TileManager.BEGIN_ID; i <= maxTileID; ++i) {
			preloadedTileIDs.add(i);
		}
		tileCache.loadTilesFor(preloadedTileIDs, r, preloadedTiles);
	}

	private final HashMap<String, HashSet<Integer>> tileIDsPerMap = new HashMap<String, HashSet<Integer>>();
	private void addTileIDsFor(HashSet<Integer> dest, String mapName, final Resources res, final WorldContext world) {
		HashSet<Integer> cachedTileIDs = tileIDsPerMap.get(mapName);
		if (cachedTileIDs == null) {
			PredefinedMap adjacentMap = world.maps.findPredefinedMap(mapName);
			if (adjacentMap == null) return;
			LayeredTileMap adjacentMapTiles = TMXMapTranslator.readLayeredTileMap(res, tileCache, adjacentMap);
			cachedTileIDs = getTileIDsFor(adjacentMap, adjacentMapTiles, world);
			tileIDsPerMap.put(mapName, cachedTileIDs);
		}

		if(AndorsTrailApplication.DEVELOPMENT_DEBUGMESSAGES){
			L.log("TileIDsFor " + mapName + "\n" + cachedTileIDs);
		}
		dest.addAll(cachedTileIDs);
	}
	public void cacheAdjacentMaps(final Resources res, final WorldContext world, final PredefinedMap nextMap) {
		(new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... arg0) {
				adjacentMapTiles = null;

				HashSet<String> adjacentMapNames = new HashSet<String>();
				for (MapObject o : nextMap.eventObjects) {
					if (o.type != MapObject.MapObjectType.newmap) continue;
					if (o.map == null) continue;
					adjacentMapNames.add(o.map);
				}

				HashSet<Integer> tileIDs = new HashSet<Integer>();
				for (String mapName : adjacentMapNames) {
					if(AndorsTrailApplication.DEVELOPMENT_DEBUGMESSAGES){
						L.log("addTileIDsFor " + mapName);
					}
					addTileIDsFor(tileIDs, mapName, res, world);
				}

				long freeMemRequired = tileSize * tileSize * tileIDs.size() * 4 /*RGBA_8888*/ * 2 /*Require twice the needed size, to leave room for others*/;
				Runtime r = Runtime.getRuntime();
				
				if (r.maxMemory() - r.totalMemory() > freeMemRequired) {
					adjacentMapTiles = tileCache.loadTilesFor(tileIDs, res);
				}
				return null;
			}
		}).execute();
	}
	
	private static class TextDrawable extends Drawable {

		private String text;
		private int size = 15;
		private Align align = Align.CENTER;
		private Paint mFillPaint;
		private Paint mStrokePaint;
		private Rect textBounds;
		private int cHeight;
		private int cWidth;
		
		public enum Align {
			TOP,
			TOP_LEFT,
			TOP_RIGHT,
			CENTER,
			LEFT,
			RIGHT,
			BOTTOM,
			BOTTOM_LEFT,
			BOTTOM_RIGHT
		}
		
		public TextDrawable(Context ctx, int cWidth, int cHeight, String text, Align align, int size) {
			this.text= text;
			this.align = align;
			this.size = size;
			this.cWidth = cWidth;
			this.cHeight = cHeight;
			init(ctx);
		}

		public TextDrawable(Context ctx, int cWidth, int cHeight,  String text, Align align) {
			this.text= text;
			this.align = align;
			this.cWidth = cWidth;
			this.cHeight = cHeight;
			init(ctx);
		}
		
		public TextDrawable(Context ctx, int cWidth, int cHeight,  String text) {
			this.text= text;
			this.cWidth = cWidth;
			this.cHeight = cHeight;
			init(ctx);
		}
		
		public void init(Context ctx) {
			mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

			mFillPaint.setColor(ThemeHelper.getThemeColor(ctx, R.attr.ui_theme_dialogue_light_color));
//			mFillPaint.setShadowLayer(5f * res.getDisplayMetrics().scaledDensity, 1, 1, res.getColor(android.R.color.black));
			mFillPaint.setStyle(Paint.Style.FILL);
			mFillPaint.setTextSize(size * ctx.getResources().getDisplayMetrics().scaledDensity);
			textBounds = new Rect();
			mFillPaint.getTextBounds(text, 0, text.length(), textBounds);
			mStrokePaint=new Paint(mFillPaint);
//			mStrokePaint.setStyle(Paint.Style.FILL);
//			mStrokePaint.setStrokeWidth(1f * res.getDisplayMetrics().scaledDensity);
			mStrokePaint.setColor(ThemeHelper.getThemeColor(ctx, R.attr.ui_theme_buttonbar_bg_color));
		}
		
		
		
		@Override
		public void draw(Canvas canvas) {
			float x,y;
			switch (align) {
			case BOTTOM:
			case BOTTOM_LEFT:
			case BOTTOM_RIGHT:
				y = cHeight - textBounds.bottom;
				break;
			case CENTER:
			case LEFT:
			case RIGHT:
				y = (cHeight - textBounds.height()) / 2;
				break;
			case TOP:
			case TOP_LEFT:
			case TOP_RIGHT:
			default:
				y = 0 - textBounds.top;
				break;
			}
			
			switch (align) {
			case BOTTOM:
			case CENTER:
			case TOP:
				x = (cWidth - textBounds.width()) / 2;
				break;
			case BOTTOM_LEFT:
			case LEFT:
			case TOP_LEFT:
			default:
				x = 0 - textBounds.left;
				break;
			case BOTTOM_RIGHT:
			case RIGHT:
			case TOP_RIGHT:
				x = cWidth - textBounds.right;
				break;
			
			}
			canvas.drawRect(x, y - textBounds.height(), x + textBounds.width(), y, mStrokePaint);
			canvas.drawText(text, x, y, mFillPaint);
//			canvas.drawText(text, x, y, mStrokePaint);
		}

		@Override
		public void setAlpha(int alpha) {
			mFillPaint.setAlpha(alpha);
//			mStrokePaint.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(ColorFilter cf) {
			mFillPaint.setColorFilter(cf);
//			mStrokePaint.setColorFilter(cf);
		}

		@Override
		public int getOpacity() {
			return mFillPaint.getAlpha();
		}
		
		@Override
		public int getIntrinsicWidth() {
			return cWidth;
		}
		
		@Override
		public int getIntrinsicHeight() {
			return cHeight;
		}
		
		@Override
		public boolean getPadding(Rect padding) {
			padding.bottom = 0;
			padding.top = 0;
			padding.left = 0;
			padding.right = 0;
			return false;
		}
		
	}
}
