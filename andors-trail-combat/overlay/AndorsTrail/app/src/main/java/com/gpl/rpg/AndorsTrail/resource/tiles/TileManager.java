package com.gpl.rpg.AndorsTrail.resource.tiles;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
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

	// Equipment-driven player appearance. The art is a native-style 32x32 layer
	// sheet derived from Andor's Trail's hero/NPC proportions and item icon language.
	private static final int HERO_EQUIPMENT_TILE_SIZE = 32;
	private static final int HERO_EQUIPMENT_COLUMNS = 8;

	private Bitmap heroEquipmentLayers;
	private Bitmap heroEquipmentBase;
	private Bitmap playerAppearanceBitmap;
	private String playerAppearanceSignature;

	private Bitmap getHeroEquipmentLayers(Resources res) {
		if (heroEquipmentLayers == null) {
			heroEquipmentLayers = BitmapFactory.decodeResource(res, R.drawable.hero_equipment_layers);
		}
		return heroEquipmentLayers;
	}

	private Bitmap getHeroEquipmentBase(Resources res) {
		if (heroEquipmentBase == null) {
			heroEquipmentBase = BitmapFactory.decodeResource(res, R.drawable.hero_equipment_base);
		}
		return heroEquipmentBase;
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

	private int getBodyLayer(String category) {
		if ("bdy_clth".equals(category)) return 1;
		if ("bdy_lthr".equals(category)) return 2;
		if ("bdy_hide".equals(category)) return 3;
		if ("bdy_lt".equals(category)) return 4;
		if ("bdy_hv".equals(category)) return 5;
		if ("chmail".equals(category)) return 6;
		if ("spmail".equals(category)) return 7;
		if ("plmail".equals(category)) return 8;
		if (category != null && category.startsWith("bdy_")) return 4;
		return -1;
	}

	private int getHeadLayer(String category) {
		if ("hd_cloth".equals(category)) return 9;
		if ("hd_lthr".equals(category)) return 10;
		if ("hd_mtl_li".equals(category)) return 11;
		if ("hd_mtl_hv".equals(category)) return 12;
		if (category != null && category.startsWith("hd_")) return 10;
		return -1;
	}

	private int getHandLayer(String category) {
		if ("hnd_lthr".equals(category) || "hnd_cloth".equals(category)) return 13;
		if ("hnd_mtl_li".equals(category) || "hnd_mtl_hv".equals(category)) return 14;
		return -1;
	}

	private int getFeetLayer(String category) {
		if ("feet_lthr".equals(category) || "feet_clth".equals(category)) return 15;
		if ("feet_mtl_li".equals(category) || "feet_mtl_hv".equals(category)) return 16;
		return -1;
	}

	private int getWeaponLayer(String category) {
		if ("dagger".equals(category)) return 17;
		if ("ssword".equals(category)) return 18;
		if ("rapier".equals(category)) return 19;
		if ("lsword".equals(category)) return 20;
		if ("2hsword".equals(category)) return 21;
		if ("bsword".equals(category)) return 22;
		if ("axe".equals(category)) return 23;
		if ("axe2h".equals(category)) return 24;
		if ("club".equals(category)) return 25;
		if ("staff".equals(category)) return 26;
		if ("mace".equals(category)) return 27;
		if ("scepter".equals(category)) return 28;
		if ("hammer".equals(category)) return 29;
		if ("hammer2h".equals(category)) return 30;
		if ("pole".equals(category)) return 31;
		return -1;
	}

	private int getShieldLayer(String category) {
		if ("buckler".equals(category)) return 32;
		if ("shld_wd_li".equals(category)) return 33;
		if ("shld_mtl_li".equals(category)) return 34;
		if ("shld_wd_hv".equals(category)) return 35;
		if ("shld_mtl_hv".equals(category)) return 36;
		if ("shld_twr".equals(category)) return 37;
		if (category != null && category.startsWith("shld_wd")) return 35;
		if (category != null && category.startsWith("shld_")) return 36;
		return -1;
	}

	private String categoryID(ItemType type) {
		return type == null || type.category == null ? null : type.category.id;
	}

	private void drawHeroEquipmentLayer(Canvas canvas, Resources res, Paint paint, int layerIndex, boolean mirror) {
		if (layerIndex < 0) return;
		Bitmap sheet = getHeroEquipmentLayers(res);
		if (sheet == null) return;
		int sx = (layerIndex % HERO_EQUIPMENT_COLUMNS) * HERO_EQUIPMENT_TILE_SIZE;
		int sy = (layerIndex / HERO_EQUIPMENT_COLUMNS) * HERO_EQUIPMENT_TILE_SIZE;
		Rect src = new Rect(sx, sy, sx + HERO_EQUIPMENT_TILE_SIZE, sy + HERO_EQUIPMENT_TILE_SIZE);
		Rect dst = new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE);
		if (mirror) {
			canvas.save();
			canvas.scale(-1f, 1f, HERO_EQUIPMENT_TILE_SIZE / 2f, HERO_EQUIPMENT_TILE_SIZE / 2f);
			canvas.drawBitmap(sheet, src, dst, paint);
			canvas.restore();
		} else {
			canvas.drawBitmap(sheet, src, dst, paint);
		}
	}

	private Bitmap buildPlayerAppearance(Resources res, Player player) {
		Bitmap result = Bitmap.createBitmap(HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(result);
		Paint paint = new Paint();
		paint.setFilterBitmap(false);

		if (player.iconID == CHAR_HERO_0) {
			Bitmap base = getHeroEquipmentBase(res);
			if (base != null) {
				canvas.drawBitmap(base, null,
						new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
			} else {
				// Last-resort fallback: the first sheet tile is the same weaponless,
				// flaskless warrior base.
				drawHeroEquipmentLayer(canvas, res, paint, 0, false);
			}
		} else {
			Bitmap selectedHero = preloadedTiles == null ? null : preloadedTiles.getBitmap(player.iconID);
			if (selectedHero != null) {
				canvas.drawBitmap(selectedHero, null,
						new Rect(0, 0, HERO_EQUIPMENT_TILE_SIZE, HERO_EQUIPMENT_TILE_SIZE), paint);
			} else {
				drawHeroEquipmentLayer(canvas, res, paint, 0, false);
			}
		}

		ItemType body = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.body);
		ItemType feet = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.feet);
		ItemType hands = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.hand);
		ItemType head = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.head);
		ItemType mainHand = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.weapon);
		ItemType offHand = player.inventory.getItemTypeInWearSlot(Inventory.WearSlot.shield);

		drawHeroEquipmentLayer(canvas, res, paint, getBodyLayer(categoryID(body)), false);
		drawHeroEquipmentLayer(canvas, res, paint, getFeetLayer(categoryID(feet)), false);
		drawHeroEquipmentLayer(canvas, res, paint, getHandLayer(categoryID(hands)), false);
		drawHeroEquipmentLayer(canvas, res, paint, getHeadLayer(categoryID(head)), false);

		int mainWeaponLayer = getWeaponLayer(categoryID(mainHand));
		if (mainHand != null && mainWeaponLayer < 0 && mainHand.isWeapon()) mainWeaponLayer = 20;
		drawHeroEquipmentLayer(canvas, res, paint, mainWeaponLayer, false);

		if (offHand != null && offHand.isWeapon()) {
			int offWeaponLayer = getWeaponLayer(categoryID(offHand));
			if (offWeaponLayer < 0) offWeaponLayer = 17;
			drawHeroEquipmentLayer(canvas, res, paint, offWeaponLayer, true);
		} else {
			int shieldLayer = getShieldLayer(categoryID(offHand));
			if (offHand != null && shieldLayer < 0 && offHand.isShield()) shieldLayer = 34;
			drawHeroEquipmentLayer(canvas, res, paint, shieldLayer, false);
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
