package com.gpl.rpg.AndorsTrail.savegames;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import android.widget.TextView;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.savegames.Savegames.FileHeader;
import com.gpl.rpg.AndorsTrail.util.L;

/**
 * Equipment-aware 64px preview cached beside the original save file.
 *
 * No changes to the save format, checksum, or anti-cheat data. The companion
 * travels with a manually exported save; old saves without one still load.
 */
public final class SavedHeroPortraits {
    private static final int MAGIC = 0x48505231; // HPR1
    private static final int MAX_PNG_BYTES = 1024 * 1024;
    private static final String SUFFIX = ".hero-portrait";
    private SavedHeroPortraits() {}

    private static File portraitFile(Context context, int slot) {
        File savedGame = slot == Savegames.SLOT_QUICKSAVE
                ? context.getFileStreamPath(
                    com.gpl.rpg.AndorsTrail.controller.Constants.FILENAME_SAVEGAME_QUICKSAVE)
                : Savegames.getSlotFile(slot, context);
        return new File(savedGame.getParentFile(), savedGame.getName() + SUFFIX);
    }

    /** Called only after the corresponding save file was successfully written. */
    public static void save(Context context, int slot, WorldContext world) {
        if (world == null || world.model == null || world.model.player == null) return;
        final Player player = world.model.player;
        try {
            Bitmap image = world.tileManager.getPlayerAppearanceBitmap(
                    context.getResources(), player);
            if (image == null) return;
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, png)) return;
            byte[] data = png.toByteArray();
            if (data.length <= 0 || data.length > MAX_PNG_BYTES) return;

            File target = portraitFile(context, slot);
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temp))) {
                out.writeInt(MAGIC);
                out.writeUTF(player.id);
                out.writeLong(player.savedVersion);
                out.writeInt(player.iconID);
                out.writeInt(data.length);
                out.write(data);
            }
            if (!temp.renameTo(target)) {
                // Some filesystems require removing an existing file first.
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace hero portrait");
                if (!temp.renameTo(target)) throw new IOException("Cannot install hero portrait");
            }
        } catch (Exception e) {
            // Never corrupt, abort, or roll back a valid gameplay save for UI art.
            L.warn("Could not update equipped save portrait: " + e);
        }
    }

    public static Bitmap load(Context context, int slot, FileHeader header) {
        if (header == null) return null;
        File target = portraitFile(context, slot);
        if (!target.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(target))) {
            if (in.readInt() != MAGIC) return null;
            if (!header.playerId.equals(in.readUTF())) return null;
            if (header.savedVersion != in.readLong()) return null;
            if (header.iconID != in.readInt()) return null;
            int len = in.readInt();
            if (len <= 0 || len > MAX_PNG_BYTES) return null;
            byte[] png = new byte[len];
            in.readFully(png);
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, len);
            if (bitmap == null || bitmap.getWidth() != 64 || bitmap.getHeight() != 64)
                return null;
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    /** Load the exact equipped appearance for the title-screen preview. */
    public static boolean apply(Context context, int slot, FileHeader header, ImageView view) {
        Bitmap bitmap = load(context, slot, header);
        if (bitmap == null) return false;
        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        drawable.setFilterBitmap(false);
        view.setImageDrawable(drawable);
        return true;
    }

    /** Show each manual slot's captured appearance at the normal icon size. */
    public static boolean apply(Context context, int slot, FileHeader header, TextView view) {
        Bitmap bitmap = load(context, slot, header);
        if (bitmap == null) return false;
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 32, 32, false);
        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), small);
        drawable.setFilterBitmap(false);
        view.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        return true;
    }
}
