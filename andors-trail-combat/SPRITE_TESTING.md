# Direct hero sprite testing

The UI playground supports direct 32x32 hero artwork before falling back to the older generated/recolored equipment sheets.

## Fixed base
Replace `res/drawable-nodpi/hero_equipment_base.png` to swap the default test character.

## Direct item artwork
Add a 32x32 transparent PNG using the real item ID:

- `hero_direct_body_<item_id>.png`
- `hero_direct_hand_<item_id>.png`
- `hero_direct_feet_<item_id>.png`
- `hero_direct_head_<item_id>.png`
- `hero_direct_weapon_<item_id>.png`
- `hero_direct_shield_<item_id>.png`

For body/hand/feet/head pieces that replace covered base pixels, add the matching mask:
`hero_direct_mask_<slot>_<item_id>.png`.

An optional exact body+hand composite can be supplied as:
`hero_direct_combo_body_<body_id>_hand_<hand_id>.png`.

TileManager resolves these names dynamically. Missing direct art falls back to the existing generic sprite system, so future test swaps usually require only replacing PNG assets.

The fast UI workflow uses sparse checkouts and assembleDebug only.
