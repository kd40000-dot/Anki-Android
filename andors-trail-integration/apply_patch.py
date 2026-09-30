#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1]).resolve()
integration = Path(__file__).resolve().parent
project = root / "AndorsTrail"

manifest = project / "app/src/main/AndroidManifest.xml"
combat_xml = project / "res/layout/combatview.xml"
combat_java = project / "app/src/main/java/com/gpl/rpg/AndorsTrail/view/CombatView.java"
controller_dst = project / "app/src/main/java/com/gpl/rpg/AndorsTrail/view/AnkiCombatReviewController.java"

# Add the Java integration controller.
shutil.copy2(integration / "AnkiCombatReviewController.java", controller_dst)

# Permission + package visibility for the custom AnkiDroid Retry build.
text = manifest.read_text()
permission_anchor = '\t<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />\n'
permission_insert = permission_anchor + '\t<uses-permission android:name="com.ichi2.anki.retry.permission.READ_WRITE_DATABASE" />\n'
if "com.ichi2.anki.retry.permission.READ_WRITE_DATABASE" not in text:
    if permission_anchor not in text:
        raise SystemExit("Manifest permission anchor not found")
    text = text.replace(permission_anchor, permission_insert, 1)

queries_anchor = '\t<queries>\n'
if '<package android:name="com.ichi2.anki.retry"' not in text:
    if queries_anchor not in text:
        raise SystemExit("Manifest queries anchor not found")
    text = text.replace(
        queries_anchor,
        queries_anchor + '\t\t<package android:name="com.ichi2.anki.retry" />\n',
        1,
    )
manifest.write_text(text)

# Add an Anki review panel directly to the existing combat HUD.
text = combat_xml.read_text()
panel = r'''
        <LinearLayout
            android:id="@+id/combatview_anki_panel"
            style="@style/AndorsTrail_Style_StdFrame"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_below="@id/combatview_monsterbar"
            android:orientation="vertical"
            android:padding="5dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/combatview_anki_question"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textColor="#FFFFFFFF"
                android:textSize="@dimen/actionbar_text" />

            <EditText
                android:id="@+id/combatview_anki_input"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="Type answer"
                android:imeOptions="actionDone"
                android:inputType="textNoSuggestions"
                android:maxLines="2"
                android:textColor="#FFFFFFFF"
                android:textColorHint="#FFAAAAAA" />

            <Button
                android:id="@+id/combatview_anki_showanswer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Show Answer" />

            <TextView
                android:id="@+id/combatview_anki_answer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:paddingTop="4dp"
                android:textColor="#FFFFFFFF"
                android:visibility="gone" />

            <TextView
                android:id="@+id/combatview_anki_marker"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center"
                android:padding="3dp"
                android:textStyle="bold"
                android:visibility="gone" />

            <LinearLayout
                android:id="@+id/combatview_anki_ratingrow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:visibility="gone">

                <Button
                    android:id="@+id/combatview_anki_again"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Again"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/combatview_anki_hard"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Hard"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/combatview_anki_good"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Good"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/combatview_anki_easy"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Easy"
                    android:textSize="11sp" />
            </LinearLayout>
        </LinearLayout>
'''
xml_anchor = '''        </RelativeLayout>
    </RelativeLayout>

    <RelativeLayout
        android:id="@+id/combatview_activeconditions_cliparea"'''
if 'android:id="@+id/combatview_anki_panel"' not in text:
    if xml_anchor not in text:
        raise SystemExit("combatview.xml insertion anchor not found")
    text = text.replace(
        xml_anchor,
        '        </RelativeLayout>\n' + panel + '    </RelativeLayout>\n\n    <RelativeLayout\n        android:id="@+id/combatview_activeconditions_cliparea"',
        1,
    )
combat_xml.write_text(text)

# Wire the combat Attack button and turn callbacks to the Anki review controller.
text = combat_java.read_text()

field_anchor = '\tprivate final TextView monsterActionText;\n'
if 'private final AnkiCombatReviewController ankiCombatReview;' not in text:
    if field_anchor not in text:
        raise SystemExit("CombatView field anchor not found")
    text = text.replace(
        field_anchor,
        field_anchor + '\tprivate final AnkiCombatReviewController ankiCombatReview;\n',
        1,
    )

init_anchor = '\t\tfinal CombatController c = controllers.combatController;\n'
if 'new AnkiCombatReviewController' not in text:
    if init_anchor not in text:
        raise SystemExit("CombatView init anchor not found")
    text = text.replace(
        init_anchor,
        init_anchor + '\t\tankiCombatReview = new AnkiCombatReviewController((MainActivity) context, c, world);\n',
        1,
    )

old_attack = '''\t\tattackMoveButton.setOnClickListener((View v) -> {
\t\t\tc.executeMoveAttack(0, 0);
\t\t\tMainView mv = ((MainActivity) getContext()).getMainView();
\t\t\tmv.post(mv::requestFocus);
\t\t});'''
new_attack = '''\t\tattackMoveButton.setOnClickListener((View v) -> {
\t\t\tif (world.model.uiSelections.selectedMonster != null && ankiCombatReview.interceptAttack()) {
\t\t\t\treturn;
\t\t\t}
\t\t\tc.executeMoveAttack(0, 0);
\t\t\tMainView mv = ((MainActivity) getContext()).getMainView();
\t\t\tmv.post(mv::requestFocus);
\t\t});'''
if new_attack not in text:
    if old_attack not in text:
        raise SystemExit("CombatView attack listener anchor not found")
    text = text.replace(old_attack, new_attack, 1)

old_selected = '''\tprivate void updateSelectedMonster(Monster selectedMonster) {
\t\tif (currentMonster != null && currentMonster == selectedMonster) return;

\t\tattackMoveButton.setEnabled(true);'''
new_selected = '''\tprivate void updateSelectedMonster(Monster selectedMonster) {
\t\tif (currentMonster != null && currentMonster == selectedMonster) return;

\t\tankiCombatReview.onTargetChanged(selectedMonster != null);
\t\tattackMoveButton.setEnabled(true);'''
if new_selected not in text:
    if old_selected not in text:
        raise SystemExit("CombatView selected monster anchor not found")
    text = text.replace(old_selected, new_selected, 1)

replacements = [
(
'''\t@Override
\tpublic void onCombatStarted() {
\t\tshow();
\t\tupdateTurnInfo(null);
\t}''',
'''\t@Override
\tpublic void onCombatStarted() {
\t\tankiCombatReview.onCombatStarted();
\t\tshow();
\t\tupdateTurnInfo(null);
\t}'''
),
(
'''\t@Override
\tpublic void onCombatEnded() {
\t\thide();
\t}''',
'''\t@Override
\tpublic void onCombatEnded() {
\t\tankiCombatReview.onCombatEnded();
\t\thide();
\t}'''
),
(
'''\t@Override
\tpublic void onNewPlayerTurn() {
\t\tupdateTurnInfo(null);
\t}''',
'''\t@Override
\tpublic void onNewPlayerTurn() {
\t\tupdateTurnInfo(null);
\t\tankiCombatReview.onNewPlayerTurn();
\t}'''
),
(
'''\t@Override
\tpublic void onMonsterIsAttacking(Monster m) {
\t\tupdateTurnInfo(m);
\t}''',
'''\t@Override
\tpublic void onMonsterIsAttacking(Monster m) {
\t\tankiCombatReview.onMonsterTurn();
\t\tupdateTurnInfo(m);
\t}'''
),
]
for old, new in replacements:
    if new in text:
        continue
    if old not in text:
        raise SystemExit("CombatView callback anchor not found:\n" + old)
    text = text.replace(old, new, 1)

combat_java.write_text(text)

print("Andor's Trail Anki combat integration applied.")
